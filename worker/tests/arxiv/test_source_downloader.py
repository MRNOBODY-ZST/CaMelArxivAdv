from __future__ import annotations

from pathlib import Path

import httpx
import pytest

from app.arxiv.source_downloader import (
    SourceDownloader,
    SourceDownloadSecurityError,
    SourceUnavailableError,
)


class FakeLease:
    def __init__(self) -> None:
        self.calls = 0

    async def await_permit(self) -> None:
        self.calls += 1


@pytest.mark.asyncio
async def test_downloads_a_bounded_official_source_to_the_supplied_directory(
    tmp_path: Path,
) -> None:
    archive = b"\x1f\x8b" + (b"safe-source" * 20)
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            headers={"content-type": "application/gzip", "content-length": str(len(archive))},
            content=archive,
            request=request,
        )
    )
    lease = FakeLease()
    async with httpx.AsyncClient(transport=transport) as client:
        result = await SourceDownloader(
            client,
            lease,
            base_url="https://export.arxiv.org/e-print",
            allowed_hosts=frozenset({"export.arxiv.org"}),
            user_agent="CaMelArxivAdv/0.1 (ops@example.invalid)",
            maximum_bytes=1024,
            maximum_redirects=2,
            maximum_retries=0,
        ).download("2608.00001", tmp_path)

    assert result.path.parent == tmp_path
    assert result.path.read_bytes() == archive
    assert result.size_bytes == len(archive)
    assert result.content_type == "application/gzip"
    assert lease.calls == 1


@pytest.mark.asyncio
async def test_rejects_redirects_to_non_official_hosts_without_following_them(
    tmp_path: Path,
) -> None:
    requests: list[str] = []

    def redirect(request: httpx.Request) -> httpx.Response:
        requests.append(str(request.url))
        return httpx.Response(
            302, headers={"location": "https://attacker.invalid/archive"}, request=request
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(redirect)) as client:
        with pytest.raises(SourceDownloadSecurityError, match="approved host"):
            await SourceDownloader(
                client,
                FakeLease(),
                base_url="https://export.arxiv.org/e-print",
                allowed_hosts=frozenset({"export.arxiv.org"}),
                user_agent="CaMelArxivAdv/0.1 (ops@example.invalid)",
                maximum_bytes=1024,
                maximum_redirects=2,
                maximum_retries=0,
            ).download("2608.00001", tmp_path)

    assert requests == ["https://export.arxiv.org/e-print/2608.00001"]
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_aborts_when_stream_exceeds_limit_even_without_content_length(
    tmp_path: Path,
) -> None:
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            headers={"content-type": "application/octet-stream"},
            content=b"\\documentclass{article}" + (b"x" * 1024),
            request=request,
        )
    )
    async with httpx.AsyncClient(transport=transport) as client:
        with pytest.raises(SourceDownloadSecurityError, match="size limit"):
            await SourceDownloader(
                client,
                FakeLease(),
                base_url="https://export.arxiv.org/e-print",
                allowed_hosts=frozenset({"export.arxiv.org"}),
                user_agent="CaMelArxivAdv/0.1 (ops@example.invalid)",
                maximum_bytes=128,
                maximum_redirects=0,
                maximum_retries=0,
            ).download("2608.00001", tmp_path)

    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_source_not_found_is_a_normal_unavailable_outcome(tmp_path: Path) -> None:
    transport = httpx.MockTransport(lambda request: httpx.Response(404, request=request))
    async with httpx.AsyncClient(transport=transport) as client:
        with pytest.raises(SourceUnavailableError):
            await SourceDownloader(
                client,
                FakeLease(),
                base_url="https://export.arxiv.org/e-print",
                allowed_hosts=frozenset({"export.arxiv.org"}),
                user_agent="CaMelArxivAdv/0.1 (ops@example.invalid)",
                maximum_bytes=1024,
                maximum_redirects=0,
                maximum_retries=0,
            ).download("2608.00001", tmp_path)


@pytest.mark.asyncio
@pytest.mark.parametrize("arxiv_id", ["", "../etc/passwd", "https://attacker.invalid/x", "2608.1"])
async def test_rejects_invalid_arxiv_ids_before_network(
    tmp_path: Path, arxiv_id: str
) -> None:
    def unexpected(request: httpx.Request) -> httpx.Response:
        raise AssertionError(f"unexpected request: {request.url}")

    async with httpx.AsyncClient(transport=httpx.MockTransport(unexpected)) as client:
        with pytest.raises(ValueError, match="arXiv ID"):
            await SourceDownloader(
                client,
                FakeLease(),
                base_url="https://export.arxiv.org/e-print",
                allowed_hosts=frozenset({"export.arxiv.org"}),
                user_agent="CaMelArxivAdv/0.1 (ops@example.invalid)",
                maximum_bytes=1024,
                maximum_redirects=0,
                maximum_retries=0,
            ).download(arxiv_id, tmp_path)
