from __future__ import annotations

import asyncio
import re
import tempfile
import time
from collections.abc import Callable
from dataclasses import dataclass
from multiprocessing import get_context
from multiprocessing.process import BaseProcess
from pathlib import Path
from typing import Literal, Protocol

import httpx
from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, ValidationError

from app.arxiv.source_downloader import (
    DownloadedSource,
    SourceDownloadSecurityError,
    SourceUnavailableError,
)
from app.extraction.archive_guard import (
    ArchiveLimits,
    ArchiveReport,
    ArchiveSecurityError,
    extract_source,
)
from app.extraction.contact_extractor import extract_contacts
from app.extraction.models import ExtractionDocument
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


@dataclass(frozen=True, slots=True)
class ParsedSource:
    report: ArchiveReport
    document: ExtractionDocument


class SourceParser(Protocol):
    async def parse(
        self,
        source_path: Path,
        extracted_dir: Path,
        *,
        limits: ArchiveLimits,
        maximum_include_depth: int,
        maximum_files: int,
        metadata_authors: tuple[str, ...],
    ) -> ParsedSource: ...


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
_MAXIMUM_PARSE_RESPONSE_BYTES = 768 * 1024


class _ParseSuccess(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    status: Literal["OK"] = "OK"
    source_format: str
    extracted_bytes: int
    file_count: int
    document: ExtractionDocument


class _ParseFailure(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    status: Literal[
        "ARCHIVE_SECURITY",
        "TEX_DISCOVERY",
        "CONTENT_INVALID",
        "INFRASTRUCTURE_TIMEOUT",
        "UNEXPECTED",
    ]
    error_type: str | None = Field(default=None, pattern=r"^[A-Za-z][A-Za-z0-9_]{0,79}$")


_PARSE_RESPONSE: TypeAdapter[_ParseSuccess | _ParseFailure] = TypeAdapter(
    _ParseSuccess | _ParseFailure
)
type ParseProcessTarget = Callable[
    [Path, Path, Path, ArchiveLimits, int, int, tuple[str, ...]], None
]


class SubprocessSourceParser:
    def __init__(
        self,
        *,
        start_method: str = "spawn",
        process_target: ParseProcessTarget | None = None,
    ) -> None:
        self._context = get_context(start_method)
        self._process_target = process_target or _parse_source_process

    async def parse(
        self,
        source_path: Path,
        extracted_dir: Path,
        *,
        limits: ArchiveLimits,
        maximum_include_depth: int,
        maximum_files: int,
        metadata_authors: tuple[str, ...],
    ) -> ParsedSource:
        response_path = extracted_dir.parent / "parse-response.json"
        process: BaseProcess = self._context.Process(  # type: ignore[attr-defined]
            target=self._process_target,
            args=(
                source_path,
                extracted_dir,
                response_path,
                limits,
                maximum_include_depth,
                maximum_files,
                metadata_authors,
            ),
            daemon=True,
        )
        process.start()
        join_task = asyncio.create_task(asyncio.to_thread(process.join))
        try:
            await asyncio.shield(join_task)
        except asyncio.CancelledError:
            cleanup_task = asyncio.create_task(
                _terminate_process(process, join_task)
            )
            await _await_uninterruptibly(cleanup_task)
            process.close()
            raise
        exit_code = process.exitcode
        process.close()
        if exit_code != 0 or not response_path.is_file():
            raise RuntimeError("Source parsing subprocess exited without a valid result")
        try:
            response = _PARSE_RESPONSE.validate_json(response_path.read_bytes())
        except (OSError, ValidationError, ValueError) as exception:
            raise RuntimeError(
                "Source parsing subprocess returned an invalid result"
            ) from exception
        if isinstance(response, _ParseSuccess):
            return ParsedSource(
                ArchiveReport(
                    response.source_format,
                    response.extracted_bytes,
                    response.file_count,
                ),
                response.document,
            )
        if response.status == "ARCHIVE_SECURITY":
            raise ArchiveSecurityError("Source archive was rejected")
        if response.status == "TEX_DISCOVERY":
            raise TexDiscoveryError("Source TeX discovery failed")
        if response.status == "CONTENT_INVALID":
            raise SourceContentValidationError
        if response.status == "INFRASTRUCTURE_TIMEOUT":
            raise TimeoutError("Source parsing dependency timed out")
        raise RuntimeError(
            "Source parsing subprocess failed: " + (response.error_type or "Exception")
        )


async def _terminate_process(
    process: BaseProcess, join_task: asyncio.Task[None]
) -> None:
    if process.is_alive():
        process.terminate()
    try:
        await asyncio.wait_for(asyncio.shield(join_task), timeout=2.0)
    except TimeoutError:
        if process.is_alive():
            process.kill()
        await join_task
    if process.is_alive():
        raise RuntimeError("Source parsing subprocess could not be terminated")


async def _await_uninterruptibly(task: asyncio.Task[None]) -> None:
    while not task.done():
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            continue
    task.result()


def _parse_source_process(
    source_path: Path,
    extracted_dir: Path,
    response_path: Path,
    limits: ArchiveLimits,
    maximum_include_depth: int,
    maximum_files: int,
    metadata_authors: tuple[str, ...],
) -> None:
    response: _ParseSuccess | _ParseFailure
    try:
        report = extract_source(source_path, extracted_dir, limits)
        corpus = discover_tex(
            extracted_dir,
            maximum_include_depth=maximum_include_depth,
            maximum_files=maximum_files,
        )
        document = (
            extract_contacts(corpus, metadata_authors)
            if metadata_authors
            else extract_contacts(corpus)
        )
        response = _ParseSuccess(
            source_format=report.source_format,
            extracted_bytes=report.extracted_bytes,
            file_count=report.file_count,
            document=document,
        )
    except ArchiveSecurityError:
        response = _ParseFailure(status="ARCHIVE_SECURITY")
    except TexDiscoveryError:
        response = _ParseFailure(status="TEX_DISCOVERY")
    except ValidationError as exception:
        response = _ParseFailure(
            status=(
                "CONTENT_INVALID"
                if _is_source_content_validation(exception)
                else "UNEXPECTED"
            ),
            error_type=None if _is_source_content_validation(exception) else "ValidationError",
        )
    except TimeoutError:
        response = _ParseFailure(status="INFRASTRUCTURE_TIMEOUT")
    except Exception as exception:
        error_type = type(exception).__name__
        response = _ParseFailure(
            status="UNEXPECTED",
            error_type=(
                error_type
                if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", error_type)
                else "Exception"
            ),
        )
    _write_parse_response(response_path, response)


def _write_parse_response(
    response_path: Path, response: _ParseSuccess | _ParseFailure
) -> None:
    encoded = response.model_dump_json().encode("utf-8")
    if len(encoded) > _MAXIMUM_PARSE_RESPONSE_BYTES:
        encoded = _ParseFailure(status="CONTENT_INVALID").model_dump_json().encode(
            "utf-8"
        )
    temporary = response_path.with_suffix(".tmp")
    temporary.write_bytes(encoded)
    temporary.replace(response_path)


def _is_source_content_validation(exception: ValidationError) -> bool:
    errors = exception.errors()
    return bool(errors) and all(
        error["type"] in _SOURCE_CONTENT_VALIDATION_TYPES for error in errors
    )


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
        parser: SourceParser | None = None,
    ) -> None:
        if maximum_include_depth < 1 or maximum_parse_seconds < 1:
            raise ValueError("Source parsing limits must be positive")
        self._downloader = downloader
        self._limits = archive_limits
        self._maximum_include_depth = maximum_include_depth
        self._maximum_parse_seconds = maximum_parse_seconds
        self._temporary_root = temporary_root
        self._parser_version = parser_version
        self._parser = parser or SubprocessSourceParser()

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
                parse_timeout = asyncio.timeout(self._maximum_parse_seconds)
                try:
                    async with parse_timeout:
                        result = await self._extract(
                            target, work_path, started, downloaded
                        )
                except TimeoutError as exception:
                    if parse_timeout.expired():
                        raise SourceParseTimeoutError from exception
                    raise
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
        try:
            parsed = await self._parser.parse(
                downloaded.path,
                extracted_dir,
                limits=self._limits,
                maximum_include_depth=self._maximum_include_depth,
                maximum_files=self._limits.maximum_file_count,
                metadata_authors=target.metadata_authors,
            )
        except ValidationError as exception:
            if _is_source_content_validation(exception):
                raise SourceContentValidationError from exception
            raise
        report = parsed.report
        document = parsed.document
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
