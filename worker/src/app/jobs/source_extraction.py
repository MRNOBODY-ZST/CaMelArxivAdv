from __future__ import annotations

import asyncio
import tempfile
import time
from pathlib import Path
from typing import Literal, Protocol

import httpx
from pydantic import ValidationError

from app.arxiv.source_downloader import (
    DownloadedSource,
    SourceDownloadSecurityError,
    SourceUnavailableError,
)
from app.extraction.archive_guard import ArchiveLimits, ArchiveSecurityError, extract_source
from app.extraction.contact_extractor import extract_contacts
from app.extraction.tex_discovery import TexDiscoveryError, discover_tex
from app.messaging.contracts import (
    SourceAuthor,
    SourceContact,
    SourceEvidence,
    SourceExtractionResult,
    SourceTarget,
)


class Downloader(Protocol):
    async def download(self, arxiv_id: str, destination: Path) -> DownloadedSource: ...


class SourceContentValidationError(Exception):
    """Source-derived metadata violates a bounded extraction model."""


class SourceParseTimeoutError(Exception):
    """Bounded archive and TeX parsing exceeded its local deadline."""


_SOURCE_CONTENT_VALIDATION_TYPES = {
    "greater_than_equal",
    "less_than_equal",
    "string_too_long",
    "string_too_short",
    "too_long",
    "too_short",
    "value_error",
}
_MAXIMUM_SOURCE_RESULT_BYTES = 768 * 1024


class SourceExtractionRunner:
    def __init__(
        self,
        downloader: Downloader,
        *,
        archive_limits: ArchiveLimits,
        maximum_include_depth: int,
        maximum_parse_seconds: float,
        temporary_root: Path | None,
        parser_version: str,
    ) -> None:
        if maximum_include_depth < 1 or maximum_parse_seconds < 1:
            raise ValueError("Source parsing limits must be positive")
        self._downloader = downloader
        self._limits = archive_limits
        self._maximum_include_depth = maximum_include_depth
        self._maximum_parse_seconds = maximum_parse_seconds
        self._temporary_root = temporary_root
        self._parser_version = parser_version

    async def run(self, target: SourceTarget) -> SourceExtractionResult:
        if self._temporary_root is not None:
            self._temporary_root.mkdir(parents=True, exist_ok=True)
        started = time.monotonic()
        result: SourceExtractionResult
        work_path: Path | None = None
        with tempfile.TemporaryDirectory(
            prefix="camel-arxiv-source-",
            dir=self._temporary_root,
        ) as work:
            work_path = Path(work)
            try:
                download_dir = work_path / "download"
                download_dir.mkdir(parents=True, exist_ok=True)
                downloaded = await self._downloader.download(
                    target.arxiv_id, download_dir
                )
                try:
                    async with asyncio.timeout(self._maximum_parse_seconds):
                        result = await self._extract(
                            target, work_path, started, downloaded
                        )
                except TimeoutError as exception:
                    raise SourceParseTimeoutError from exception
            except SourceUnavailableError:
                result = self._failure(
                    target,
                    started,
                    "SOURCE_UNAVAILABLE",
                    "SOURCE_UNAVAILABLE",
                    "Official arXiv Source is unavailable",
                )
            except (SourceDownloadSecurityError, ArchiveSecurityError):
                result = self._failure(
                    target,
                    started,
                    "SECURITY_REJECTED",
                    "SOURCE_SECURITY_REJECTED",
                    "Source package was rejected by the configured security policy",
                )
            except TexDiscoveryError:
                result = self._failure(
                    target,
                    started,
                    "FAILED",
                    "TEX_DISCOVERY_FAILED",
                    "No bounded TeX document graph could be analyzed",
                )
            except SourceContentValidationError:
                result = self._failure(
                    target,
                    started,
                    "FAILED",
                    "SOURCE_CONTENT_INVALID",
                    "Source metadata exceeded supported parsing boundaries",
                )
            except SourceParseTimeoutError:
                result = self._failure(
                    target,
                    started,
                    "FAILED",
                    "SOURCE_PARSE_TIMEOUT",
                    "Source parsing exceeded the configured deadline",
                )
            except httpx.HTTPStatusError as exception:
                if exception.response.status_code >= 500:
                    raise
                result = self._failure(
                    target,
                    started,
                    "FAILED",
                    "SOURCE_HTTP_REJECTED",
                    "Official arXiv Source request was rejected",
                )
        if work_path is None or work_path.exists():
            raise RuntimeError("Source temporary directory cleanup could not be confirmed")
        return result

    async def _extract(
        self,
        target: SourceTarget,
        work: Path,
        started: float,
        downloaded: DownloadedSource,
    ) -> SourceExtractionResult:
        extracted_dir = work / "extracted"
        report = await asyncio.to_thread(
            extract_source, downloaded.path, extracted_dir, self._limits
        )
        try:
            corpus = await asyncio.to_thread(
                discover_tex,
                extracted_dir,
                maximum_include_depth=self._maximum_include_depth,
                maximum_files=self._limits.maximum_file_count,
            )
            document = await (
                asyncio.to_thread(
                    extract_contacts, corpus, target.metadata_authors
                )
                if target.metadata_authors
                else asyncio.to_thread(extract_contacts, corpus)
            )
        except ValidationError as exception:
            errors = exception.errors()
            if errors and all(
                error["type"] in _SOURCE_CONTENT_VALIDATION_TYPES
                for error in errors
            ):
                raise SourceContentValidationError from exception
            raise
        authors = tuple(
            SourceAuthor(
                order=item.order,
                name=item.name,
                affiliations=item.affiliations,
                corresponding=item.corresponding,
            )
            for item in document.authors
        )
        contacts = tuple(
            SourceContact(
                normalized_email=item.normalized_email,
                display_email=item.display_email,
                domain=item.domain,
                syntax_valid=item.syntax_valid,
                example_address=item.example_address,
                author_order=item.author_order,
                confidence=item.confidence.value,
                corresponding=item.corresponding,
                evidence=tuple(
                    SourceEvidence(
                        source_relative_path=evidence.source_relative_path,
                        rule_name=evidence.rule_name,
                        line_number=evidence.line_number,
                        logical_location=evidence.logical_location,
                        masked_context=evidence.masked_context,
                    )
                    for evidence in item.evidence
                ),
            )
            for item in document.contacts
        )
        result = SourceExtractionResult(
            paper_id=target.paper_id,
            arxiv_id=target.arxiv_id,
            parser_version=self._parser_version,
            status="SUCCEEDED",
            cleanup_confirmed=True,
            source_format=report.source_format,
            archive_size_bytes=downloaded.size_bytes,
            extracted_size_bytes=report.extracted_bytes,
            files_inspected=document.files_inspected,
            duration_ms=_elapsed(started),
            document_class=document.document_class,
            authors=authors,
            contacts=contacts,
        )
        if len(result.model_dump_json(by_alias=True).encode("utf-8")) > (
            _MAXIMUM_SOURCE_RESULT_BYTES
        ):
            raise SourceContentValidationError
        return result

    def _failure(
        self,
        target: SourceTarget,
        started: float,
        status: Literal["FAILED", "SECURITY_REJECTED", "SOURCE_UNAVAILABLE"],
        error_code: str,
        summary: str,
    ) -> SourceExtractionResult:
        return SourceExtractionResult(
            paper_id=target.paper_id,
            arxiv_id=target.arxiv_id,
            parser_version=self._parser_version,
            status=status,
            cleanup_confirmed=True,
            duration_ms=_elapsed(started),
            error_code=error_code,
            error_summary=summary,
        )


def _elapsed(started: float) -> int:
    return max(0, round((time.monotonic() - started) * 1000))
