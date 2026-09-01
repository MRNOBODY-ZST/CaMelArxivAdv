from __future__ import annotations

import io
import tarfile
from pathlib import Path
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.arxiv.source_downloader import (
    DownloadedSource,
    SourceDownloadSecurityError,
    SourceUnavailableError,
)
from app.extraction.archive_guard import ArchiveLimits
from app.extraction.models import (
    Confidence,
    ExtractedAuthor,
    ExtractedContact,
    ExtractionDocument,
    ExtractionEvidence,
)
from app.jobs.source_extraction import SourceExtractionRunner
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
async def test_unsafe_evidence_is_classified_as_an_item_content_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def document_with_unsafe_outbound_evidence(_corpus: object) -> ExtractionDocument:
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

    monkeypatch.setattr(
        "app.jobs.source_extraction.extract_contacts",
        document_with_unsafe_outbound_evidence,
    )
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "FAILED"
    assert result.error_code == "SOURCE_CONTENT_INVALID"
    assert result.contacts == ()
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_unexpected_extractor_error_propagates_after_cleanup(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fail_unexpectedly(_corpus: object) -> ExtractionDocument:
        raise RuntimeError("parser invariant failed")

    monkeypatch.setattr(
        "app.jobs.source_extraction.extract_contacts",
        fail_unexpectedly,
    )
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    with pytest.raises(RuntimeError, match="parser invariant failed"):
        await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_parser_model_shape_validation_propagates_after_cleanup(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def parser_with_missing_required_field(_corpus: object) -> ExtractionDocument:
        return ExtractionDocument(document_class="article")  # type: ignore[call-arg]

    monkeypatch.setattr(
        "app.jobs.source_extraction.extract_contacts",
        parser_with_missing_required_field,
    )
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    with pytest.raises(ValidationError, match="files_inspected"):
        await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
@pytest.mark.parametrize("unsafe_value", ["X" * 2001, "Example Lab\u0001"])
async def test_backend_unsafe_affiliation_is_classified_as_item_content_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    unsafe_value: str,
) -> None:
    def document_with_unsafe_affiliation(_corpus: object) -> ExtractionDocument:
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

    monkeypatch.setattr(
        "app.jobs.source_extraction.extract_contacts",
        document_with_unsafe_affiliation,
    )
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
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
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def document_with_invalid_email(_corpus: object) -> ExtractionDocument:
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

    monkeypatch.setattr(
        "app.jobs.source_extraction.extract_contacts",
        document_with_invalid_email,
    )
    runner = SourceExtractionRunner(
        TarDownloader(),
        archive_limits=extraction_limits(),
        maximum_include_depth=8,
        maximum_parse_seconds=5,
        temporary_root=tmp_path,
        parser_version="phase4-test",
    )

    result = await runner.run(SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"))

    assert result.status == "FAILED"
    assert result.error_code == "SOURCE_CONTENT_INVALID"
    assert result.contacts == ()
    assert list(tmp_path.iterdir()) == []
