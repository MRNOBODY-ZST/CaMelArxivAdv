from __future__ import annotations

import asyncio
import io
import signal
import tarfile
import time
from collections.abc import Callable
from pathlib import Path
from typing import Never
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.arxiv.source_downloader import (
    DownloadedSource,
    SourceDownloadSecurityError,
    SourceUnavailableError,
)
from app.extraction.archive_guard import ArchiveLimits, ArchiveReport, extract_source
from app.extraction.contact_extractor import extract_contacts
from app.extraction.models import (
    Confidence,
    ExtractedAuthor,
    ExtractedContact,
    ExtractionDocument,
    ExtractionEvidence,
    TexCorpus,
)
from app.extraction.tex_discovery import discover_tex
from app.jobs.source_extraction import (
    ParsedSource,
    SourceExtractionRunner,
    SubprocessSourceParser,
)
from app.messaging.contracts import SourceTarget


class TarDownloader:
    async def download(self, arxiv_id: str, destination: Path) -> DownloadedSource:
        path = destination / "source.tar.gz"
        content = (
            b"\\documentclass{article}\n"
            b"\\author{Alice Example\\thanks{alice@uni.edu}}\n"
            b"\\begin{document}\n"
        )
        with tarfile.open(path, "w:gz") as archive:
            member = tarfile.TarInfo("paper/main.tex")
            member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
        return DownloadedSource(path, path.stat().st_size, "application/gzip")


class UnavailableDownloader:
    async def download(self, arxiv_id: str, destination: Path) -> DownloadedSource:
        raise SourceUnavailableError("missing")


class EscapingDownloader:
    async def download(self, arxiv_id: str, destination: Path) -> DownloadedSource:
        raise SourceDownloadSecurityError("redirect used an unapproved host")


class InvalidMetadataDownloader:
    async def download(self, arxiv_id: str, destination: Path) -> DownloadedSource:
        path = destination / "source.tar.gz"
        content = (
            b"\\documentclass{article}\n"
            + b"\\author{" + (b"A" * 301) + b"}\n"
            + b"\\begin{document}\n"
        )
        with tarfile.open(path, "w:gz") as archive:
            member = tarfile.TarInfo("paper/main.tex")
            member.size = len(content)
            archive.addfile(member, io.BytesIO(content))
        return DownloadedSource(path, path.stat().st_size, "application/gzip")


class TimedOutDownloader:
    async def download(self, arxiv_id: str, destination: Path) -> DownloadedSource:
        raise TimeoutError("upstream request timed out")


def late_writing_parser_process(
    source_path: Path,
    extracted_dir: Path,
    response_path: Path,
    limits: ArchiveLimits,
    maximum_include_depth: int,
    maximum_files: int,
    metadata_authors: tuple[str, ...],
) -> None:
    time.sleep(0.08)
    extracted_dir.mkdir(parents=True, exist_ok=True)
    (extracted_dir / "residue.tex").write_text(
        "late contact: residue@example.edu", encoding="utf-8"
    )


def infrastructure_timeout_parser_process(
    source_path: Path,
    extracted_dir: Path,
    response_path: Path,
    limits: ArchiveLimits,
    maximum_include_depth: int,
    maximum_files: int,
    metadata_authors: tuple[str, ...],
) -> None:
    response_path.write_text(
        '{"status":"INFRASTRUCTURE_TIMEOUT","error_type":null}',
        encoding="utf-8",
    )


def sigterm_ignoring_parser_process(
    source_path: Path,
    extracted_dir: Path,
    response_path: Path,
    limits: ArchiveLimits,
    maximum_include_depth: int,
    maximum_files: int,
    metadata_authors: tuple[str, ...],
) -> None:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    (extracted_dir.parent / "parser-ready").write_text("ready", encoding="utf-8")
    time.sleep(0.25)
    extracted_dir.mkdir(parents=True, exist_ok=True)
    (extracted_dir / "second-cancel.tex").write_text(
        "late contact: secondcancel@example.edu", encoding="utf-8"
    )


class DirectSourceParser:
    def __init__(
        self,
        *,
        archive_parser: Callable[[Path, Path, ArchiveLimits], ArchiveReport] = extract_source,
        contact_parser: Callable[..., ExtractionDocument] = extract_contacts,
    ) -> None:
        self._archive_parser = archive_parser
        self._contact_parser = contact_parser

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
        report = self._archive_parser(source_path, extracted_dir, limits)
        corpus = discover_tex(
            extracted_dir,
            maximum_include_depth=maximum_include_depth,
            maximum_files=maximum_files,
        )
        document = (
            self._contact_parser(corpus, metadata_authors)
            if metadata_authors
            else self._contact_parser(corpus)
        )
        return ParsedSource(report, document)


def extraction_limits() -> ArchiveLimits:
    return ArchiveLimits(
        maximum_extracted_bytes=100_000,
        maximum_single_file_bytes=50_000,
        maximum_file_count=20,
        maximum_directory_depth=5,
        maximum_compression_ratio=100.0,
    )


@pytest.mark.asyncio
async def test_runner_extracts_structured_result_and_deletes_entire_temp_job(
    tmp_path: Path,
) -> None:
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "SUCCEEDED"
    assert result.cleanup_confirmed is True
    assert result.source_format == "TAR_GZIP"
    assert result.contacts[0].normalized_email == "alice@uni.edu"
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_unavailable_source_is_terminal_and_temp_job_is_still_deleted(
    tmp_path: Path,
) -> None:
    runner = SourceExtractionRunner(
        UnavailableDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "SOURCE_UNAVAILABLE"
    assert result.error_code == "SOURCE_UNAVAILABLE"
    assert result.cleanup_confirmed is True
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_download_policy_violation_is_security_rejected(tmp_path: Path) -> None:
    runner = SourceExtractionRunner(
        EscapingDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "SECURITY_REJECTED"
    assert result.error_code == "SOURCE_SECURITY_REJECTED"
    assert "unapproved host" not in (result.error_summary or "")
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_download_timeout_propagates_for_command_retry(tmp_path: Path) -> None:
    runner = SourceExtractionRunner(
        TimedOutDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    with pytest.raises(TimeoutError, match="upstream request timed out"):
        await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_parse_timeout_remains_a_bounded_item_failure(
    tmp_path: Path,
) -> None:
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=SubprocessSourceParser(
            start_method="spawn",
            process_target=late_writing_parser_process,
        ),
    )
    runner._maximum_parse_seconds = 0.01

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "FAILED"
    assert result.error_code == "SOURCE_PARSE_TIMEOUT"
    assert list(tmp_path.iterdir()) == []
    await asyncio.sleep(0.12)
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_infrastructure_timeout_inside_parse_stage_propagates_for_retry(
    tmp_path: Path,
) -> None:
    def infrastructure_timeout(
        source_path: Path, extracted_dir: Path, limits: ArchiveLimits
    ) -> Never:
        raise TimeoutError("storage operation timed out")

    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=DirectSourceParser(archive_parser=infrastructure_timeout),
    )

    with pytest.raises(TimeoutError, match="storage operation timed out"):
        await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_subprocess_infrastructure_timeout_response_propagates_for_retry(
    tmp_path: Path,
) -> None:
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=SubprocessSourceParser(
            start_method="spawn",
            process_target=infrastructure_timeout_parser_process,
        ),
    )

    with pytest.raises(TimeoutError, match="Source parsing dependency timed out"):
        await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_repeated_cancellation_cannot_interrupt_subprocess_cleanup(
    tmp_path: Path,
) -> None:
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=SubprocessSourceParser(
            start_method="spawn",
            process_target=sigterm_ignoring_parser_process,
        ),
    )
    task = asyncio.create_task(
        runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))
    )
    async with asyncio.timeout(2):
        while not tuple(tmp_path.rglob("parser-ready")):
            await asyncio.sleep(0.01)

    task.cancel()
    await asyncio.sleep(0.05)
    task.cancel()

    with pytest.raises(asyncio.CancelledError):
        await task
    await asyncio.sleep(0.3)
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_invalid_source_metadata_fails_only_that_item_without_leaking_content(
    tmp_path: Path,
) -> None:
    runner = SourceExtractionRunner(
        InvalidMetadataDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "FAILED"
    assert result.error_code == "SOURCE_CONTENT_INVALID"
    assert result.error_summary == "Source metadata exceeded supported parsing boundaries"
    assert "A" * 20 not in (result.error_summary or "")
    assert result.cleanup_confirmed is True
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_aggregate_source_result_over_kafka_budget_is_an_item_content_failure(
    tmp_path: Path,
) -> None:
    def oversized_document(_corpus: TexCorpus) -> ExtractionDocument:
        affiliations = (
            "A" * 200,
            "B" * 200,
            "C" * 200,
            "D" * 200,
            "E" * 1999,
        )
        return ExtractionDocument(
            document_class="article",
            files_inspected=1,
            authors=tuple(
                ExtractedAuthor(
                    order=index,
                    name=f"Bounded Author {index}",
                    affiliations=affiliations,
                )
                for index in range(1, 501)
            ),
        )

    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=DirectSourceParser(contact_parser=oversized_document),
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "FAILED"
    assert result.error_code == "SOURCE_CONTENT_INVALID"
    assert result.authors == ()
    assert result.contacts == ()
    assert len(result.model_dump_json(by_alias=True).encode("utf-8")) < 2_000
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_unsafe_evidence_is_classified_as_an_item_content_failure(
    tmp_path: Path,
) -> None:
    def document_with_unsafe_outbound_evidence(
        _corpus: TexCorpus,
    ) -> ExtractionDocument:
        return ExtractionDocument(
            document_class="article",
            files_inspected=1,
            contacts=(
                ExtractedContact(
                    normalized_email="alice@uni.edu",
                    display_email="alice@uni.edu",
                    domain="uni.edu",
                    syntax_valid=True,
                    confidence=Confidence.LOW,
                    evidence=(
                        ExtractionEvidence(
                            source_relative_path="../outside.tex",
                            rule_name="PAPER_LEVEL_FRONT_MATTER_EMAIL",
                            line_number=1,
                            logical_location="AUTHOR_FRONT_MATTER",
                            masked_context="al***@uni.edu",
                        ),
                    ),
                ),
            ),
        )

    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=DirectSourceParser(contact_parser=document_with_unsafe_outbound_evidence),
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "FAILED"
    assert result.error_code == "SOURCE_CONTENT_INVALID"
    assert result.contacts == ()
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_unexpected_extractor_error_propagates_after_cleanup(
    tmp_path: Path,
) -> None:
    def fail_unexpectedly(_corpus: TexCorpus) -> ExtractionDocument:
        raise RuntimeError("parser invariant failed")

    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=DirectSourceParser(contact_parser=fail_unexpectedly),
    )

    with pytest.raises(RuntimeError, match="parser invariant failed"):
        await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_parser_model_shape_validation_propagates_after_cleanup(
    tmp_path: Path,
) -> None:
    def parser_with_missing_required_field(_corpus: TexCorpus) -> ExtractionDocument:
        return ExtractionDocument(document_class="article")  # type: ignore[call-arg]

    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=DirectSourceParser(contact_parser=parser_with_missing_required_field),
    )

    with pytest.raises(ValidationError, match="files_inspected"):
        await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
@pytest.mark.parametrize("unsafe_value", ["X" * 2001, "Example Lab\u0001"])
async def test_backend_unsafe_affiliation_is_classified_as_item_content_failure(
    tmp_path: Path,
    unsafe_value: str,
) -> None:
    def document_with_unsafe_affiliation(_corpus: TexCorpus) -> ExtractionDocument:
        return ExtractionDocument(
            document_class="article",
            files_inspected=1,
            authors=(
                ExtractedAuthor(
                    order=1,
                    name="Alice Example",
                    affiliations=(unsafe_value,),
                ),
            ),
        )

    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=DirectSourceParser(contact_parser=document_with_unsafe_affiliation),
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "FAILED"
    assert result.error_code == "SOURCE_CONTENT_INVALID"
    assert result.authors == ()
    assert result.contacts == ()
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_backend_invalid_normalized_email_is_classified_as_item_content_failure(
    tmp_path: Path,
) -> None:
    def document_with_invalid_email(_corpus: TexCorpus) -> ExtractionDocument:
        return ExtractionDocument(
            document_class="article",
            files_inspected=1,
            contacts=(
                ExtractedContact(
                    normalized_email="$alice@example.org$",
                    display_email="$alice@example.org$",
                    domain="example.org$",
                    syntax_valid=True,
                    confidence=Confidence.LOW,
                    evidence=(
                        ExtractionEvidence(
                            source_relative_path="main.tex",
                            rule_name="PAPER_LEVEL_FRONT_MATTER_EMAIL",
                            line_number=1,
                            logical_location="AUTHOR_FRONT_MATTER",
                            masked_context="Contact: al***@example.org",
                        ),
                    ),
                ),
            ),
        )

    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
        parser=DirectSourceParser(contact_parser=document_with_invalid_email),
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "FAILED"
    assert result.error_code == "SOURCE_CONTENT_INVALID"
    assert result.contacts == ()
    assert list(tmp_path.iterdir()) == []
