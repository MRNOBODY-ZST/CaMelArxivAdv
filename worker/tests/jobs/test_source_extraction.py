from __future__ import annotations

import io
import tarfile
from pathlib import Path
from uuid import uuid4

import pytest

from app.arxiv.source_downloader import (
    DownloadedSource,
    SourceDownloadSecurityError,
    SourceUnavailableError,
)
from app.extraction.archive_guard import ArchiveLimits
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
