from __future__ import annotations

import asyncio
import random
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urljoin
from uuid import uuid4

import httpx

from app.arxiv.http import PermitLease, validate_official_endpoint

_ARXIV_ID = re.compile(r"(?:[0-9]{4}\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})")
_REDIRECTS = {301, 302, 303, 307, 308}
_RETRYABLE = {429, 500, 502, 503, 504}
_ALLOWED_CONTENT_TYPES = {
    "application/gzip",
    "application/octet-stream",
    "application/x-eprint-tar",
    "application/x-gzip",
    "application/x-tar",
    "application/zip",
    "text/plain",
    "text/x-tex",
}


class SourceUnavailableError(Exception):
    """The official endpoint reports that no downloadable source exists."""


class SourceDownloadSecurityError(Exception):
    """The response violates a configured Source download boundary."""


@dataclass(frozen=True, slots=True)
class DownloadedSource:
    path: Path
    size_bytes: int
    content_type: str


class SourceDownloader:
    def __init__(
        self,
        client: httpx.AsyncClient,
        lease: PermitLease,
        *,
        base_url: str,
        allowed_hosts: frozenset[str],
        user_agent: str,
        maximum_bytes: int,
        maximum_redirects: int,
        maximum_retries: int,
    ) -> None:
        validate_official_endpoint(base_url, allowed_hosts)
        if not user_agent.strip() or len(user_agent) > 300:
            raise ValueError("arXiv user agent is invalid")
        if maximum_bytes < 1:
            raise ValueError("maximum Source size must be positive")
        if not 0 <= maximum_redirects <= 5 or not 0 <= maximum_retries <= 10:
            raise ValueError("Source retry or redirect limit is invalid")
        self._client = client
        self._lease = lease
        self._base_url = base_url.rstrip("/")
        self._allowed_hosts = allowed_hosts
        self._user_agent = user_agent
        self._maximum_bytes = maximum_bytes
        self._maximum_redirects = maximum_redirects
        self._maximum_retries = maximum_retries

    async def download(self, arxiv_id: str, destination: Path) -> DownloadedSource:
        if _ARXIV_ID.fullmatch(arxiv_id) is None:
            raise ValueError("arXiv ID is invalid")
        destination.mkdir(parents=True, exist_ok=True)
        target = destination / f"source-{uuid4().hex}.bin"
        url = f"{self._base_url}/{arxiv_id}"
        validate_official_endpoint(url, self._allowed_hosts)
        try:
            for attempt in range(self._maximum_retries + 1):
                try:
                    return await self._download_attempt(url, target)
                except (httpx.TimeoutException, httpx.NetworkError, _RetryableSourceError):
                    target.unlink(missing_ok=True)
                    if attempt == self._maximum_retries:
                        raise
                    await asyncio.sleep(
                        min(8.0, 0.25 * (2**attempt)) + random.uniform(0.0, 0.2)
                    )
        except Exception:
            target.unlink(missing_ok=True)
            raise
        raise RuntimeError("Source download exhausted retry attempts")

    async def _download_attempt(self, initial_url: str, target: Path) -> DownloadedSource:
        url = initial_url
        for redirect_count in range(self._maximum_redirects + 1):
            await self._lease.await_permit()
            async with self._client.stream(
                "GET",
                url,
                headers={
                    "Accept": (
                        "application/x-eprint-tar, application/gzip, "
                        "application/octet-stream"
                    ),
                    "User-Agent": self._user_agent,
                },
                follow_redirects=False,
            ) as response:
                if response.status_code in {404, 410}:
                    raise SourceUnavailableError("arXiv Source is unavailable")
                if response.status_code in _RETRYABLE:
                    if response.status_code == 429:
                        raise _RetryableSourceError("arXiv Source request was rate limited")
                    raise _RetryableSourceError("arXiv Source service is temporarily unavailable")
                if response.status_code in _REDIRECTS:
                    if redirect_count >= self._maximum_redirects:
                        raise SourceDownloadSecurityError("Source redirect limit was exceeded")
                    location = response.headers.get("location")
                    if not location:
                        raise SourceDownloadSecurityError("Source redirect has no location")
                    url = urljoin(url, location)
                    try:
                        validate_official_endpoint(url, self._allowed_hosts)
                    except ValueError as exception:
                        raise SourceDownloadSecurityError(str(exception)) from exception
                    continue
                response.raise_for_status()
                content_type = response.headers.get("content-type", "application/octet-stream")
                content_type = content_type.split(";", 1)[0].strip().lower()
                if content_type not in _ALLOWED_CONTENT_TYPES:
                    raise SourceDownloadSecurityError("Source MIME type is not allowed")
                declared = response.headers.get("content-length")
                try:
                    declared_size = int(declared) if declared is not None else None
                except ValueError as exception:
                    raise SourceDownloadSecurityError(
                        "Source content length is invalid"
                    ) from exception
                if declared_size is not None and (
                    declared_size < 0 or declared_size > self._maximum_bytes
                ):
                    raise SourceDownloadSecurityError("Source exceeded the configured size limit")
                size = 0
                with target.open("wb") as stream:
                    async for chunk in response.aiter_bytes():
                        size += len(chunk)
                        if size > self._maximum_bytes:
                            raise SourceDownloadSecurityError(
                                "Source exceeded the configured size limit"
                            )
                        stream.write(chunk)
                if size == 0:
                    raise SourceDownloadSecurityError("Source response was empty")
                if not _matches_source_header(target, content_type):
                    raise SourceDownloadSecurityError("Source MIME and file header do not match")
                return DownloadedSource(target, size, content_type)
        raise SourceDownloadSecurityError("Source redirect limit was exceeded")


class _RetryableSourceError(httpx.HTTPError):
    pass


def _matches_source_header(path: Path, content_type: str) -> bool:
    header = path.read_bytes()[:512]
    archive = (
        header.startswith(b"\x1f\x8b")
        or header.startswith(b"PK\x03\x04")
        or (len(header) > 262 and header[257:262] == b"ustar")
    )
    tex = b"\\documentclass" in header or b"\\begin{document}" in header
    if content_type.startswith("text/"):
        return tex
    return archive or tex
