from __future__ import annotations

import traceback
from collections.abc import AsyncIterator, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import httpx
import pytest

from app.arxiv.source_downloader import (
    SourceDownloader,
    SourceDownloadSecurityError,
    SourceUnavailableError,
)
from app.arxiv.source_errors import SourceUpstreamTransientError


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


def boundary_downloader(
    client: httpx.AsyncClient, lease: FakeLease, *, retries: int = 1
) -> SourceDownloader:
    return SourceDownloader(
        client,
        lease,
        base_url="https://export.arxiv.org/e-print",
        allowed_hosts=frozenset({"export.arxiv.org"}),
        user_agent="synthetic-test",
        maximum_bytes=1024,
        maximum_redirects=2,
        maximum_retries=retries,
    )


async def download_error(downloader: SourceDownloader, tmp_path: Path) -> Exception:
    try:
        await downloader.download("2608.00001", tmp_path)
    except Exception as error:
        return error
    raise AssertionError("Expected a failed download")


async def no_backoff(seconds: float) -> None:
    pass


def assert_safe_transient(error: Exception, reason: str) -> None:
    __tracebackhide__ = True
    actual_type = type(error).__name__
    actual_reason = getattr(error, "reason", None)
    assert actual_type == "SourceUpstreamTransientError"
    assert actual_reason == reason
    diagnostic = str(error) + repr(error) + "".join(traceback.format_exception(error))
    # Only fixed categories may reach public diagnostics, not the original HTTP data.
    assert "SYNTHETIC_SECRET" not in diagnostic
    assert error.__cause__ is None
    assert error.__context__ is None
    assert isinstance(error, SourceUpstreamTransientError)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "status,reason",
    [
        (429, "RATE_LIMITED"),
        (500, "UNAVAILABLE"),
        (502, "UNAVAILABLE"),
        (503, "UNAVAILABLE"),
        (504, "UNAVAILABLE"),
    ],
)
async def test_transient_reason_is_bound_to_approved_download_status(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    status: int,
    reason: str,
) -> None:
    monkeypatch.setattr("app.arxiv.source_downloader.asyncio.sleep", no_backoff)
    requests = 0

    def respond(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        return httpx.Response(status, headers={"x-synthetic": "SYNTHETIC_SECRET"},
                              content=b"SYNTHETIC_SECRET", request=request)

    lease = FakeLease()
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        error = await download_error(boundary_downloader(client, lease), tmp_path)
    assert_safe_transient(error, reason)
    assert requests == lease.calls == 2
    assert list(tmp_path.iterdir()) == []


class FailingHttpStream(httpx.AsyncByteStream):
    def __init__(self, failure: Exception, seam: str) -> None:
        self.failure = failure
        self.seam = seam
        self.closed = False

    async def __aiter__(self) -> AsyncIterator[bytes]:
        yield b"\\documentclass{article}"
        if self.seam == "read":
            raise self.failure

    async def aclose(self) -> None:
        self.closed = True
        if self.seam == "close":
            raise self.failure


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "error_type,reason",
    [
        (httpx.ConnectError, "NETWORK"),
        (httpx.ReadError, "NETWORK"),
        (httpx.ConnectTimeout, "TIMEOUT"),
        (httpx.ReadTimeout, "TIMEOUT"),
    ],
)
@pytest.mark.parametrize("seam", ["open", "read", "close"])
async def test_transient_reason_is_bound_to_real_http_operations(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    error_type: type[httpx.RequestError],
    reason: str,
    seam: str,
) -> None:
    monkeypatch.setattr("app.arxiv.source_downloader.asyncio.sleep", no_backoff)
    requests = 0
    streams: list[FailingHttpStream] = []

    def respond(request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        failure = error_type(
            "SYNTHETIC_SECRET user@example.invalid https://secret.invalid/?token=hidden",
            request=httpx.Request("GET", "https://SYNTHETIC_SECRET.invalid"),
        )
        if seam == "open":
            raise failure
        stream = FailingHttpStream(failure, seam)
        streams.append(stream)
        return httpx.Response(
            200, headers={"content-type": "text/plain"}, stream=stream, request=request
        )

    lease = FakeLease()
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        error = await download_error(boundary_downloader(client, lease), tmp_path)
    assert_safe_transient(error, reason)
    assert requests == lease.calls == 2
    assert all(stream.closed for stream in streams)
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [400, 401, 403, 408, 501, 505])
async def test_other_http_rejections_are_not_transient(tmp_path: Path, status: int) -> None:
    lease = FakeLease()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(status, request=request))
    ) as client:
        error = await download_error(boundary_downloader(client, lease), tmp_path)
    assert isinstance(error, httpx.HTTPStatusError)
    assert not hasattr(error, "reason")
    assert lease.calls == 1
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_source_gone_remains_terminal(tmp_path: Path) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(410, request=request))
    ) as client:
        error = await download_error(boundary_downloader(client, FakeLease()), tmp_path)
    assert isinstance(error, SourceUnavailableError)
    assert not hasattr(error, "reason")


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    [
        RuntimeError("permit unavailable"),
        httpx.ConnectError("permit dependency"),
        httpx.ReadTimeout("permit dependency"),
        TimeoutError("permit dependency"),
    ],
)
async def test_permit_failures_never_acquire_source_reasons(
    tmp_path: Path,
    failure: Exception,
) -> None:
    class FailedLease(FakeLease):
        async def await_permit(self) -> None:
            self.calls += 1
            raise failure

    def unexpected(request: httpx.Request) -> httpx.Response:
        raise AssertionError("Permit failure must prevent requests")

    lease = FailedLease()
    async with httpx.AsyncClient(transport=httpx.MockTransport(unexpected)) as client:
        error = await download_error(boundary_downloader(client, lease), tmp_path)
    assert error is failure
    assert not hasattr(error, "reason")
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
async def test_retries_transient_status_then_returns_actual_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("app.arxiv.source_downloader.asyncio.sleep", no_backoff)
    attempts = 0

    def respond(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(
            503 if attempts == 1 else 200, content=b"\\documentclass{article}", request=request
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        result = await boundary_downloader(client, FakeLease()).download("2608.00001", tmp_path)
    assert result.path.read_bytes() == b"\\documentclass{article}"
    assert attempts == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("seam", ["mkdir", "open", "write", "file_close", "header_read"])
@pytest.mark.parametrize(
    "failure_type", [OSError, TimeoutError, httpx.ReadTimeout, httpx.ConnectError]
)
async def test_disk_operations_never_acquire_source_reasons(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    seam: str,
    failure_type: type[Exception],
) -> None:
    failure = failure_type("local storage failure")
    original_open = Path.open

    class FailedWriter:
        def write(self, chunk: bytes) -> None:
            raise failure

    @contextmanager
    def failed_stream(path: Path) -> Iterator[Any]:
        if seam == "open":
            raise failure
        if seam == "write":
            yield FailedWriter()
        else:
            with original_open(path, "wb") as stream:
                yield stream
            raise failure

    def open_path(path: Path, *args: Any, **kwargs: Any) -> Any:
        return failed_stream(path)

    def fail_path(path: Path, *args: Any, **kwargs: Any) -> Any:
        raise failure

    if seam in {"open", "write", "file_close"}:
        monkeypatch.setattr(Path, "open", open_path)
    elif seam == "mkdir":
        monkeypatch.setattr(Path, "mkdir", fail_path)
    else:
        monkeypatch.setattr(Path, "read_bytes", fail_path)

    lease = FakeLease()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200, content=b"\\documentclass{article}", request=request
            )
        )
    ) as client:
        error = await download_error(boundary_downloader(client, lease), tmp_path)
    assert error is failure
    assert not hasattr(error, "reason")
    assert lease.calls == (0 if seam == "mkdir" else 1)
    assert not tuple(tmp_path.glob("source-*.bin"))


@pytest.mark.asyncio
async def test_cleanup_failure_is_not_reclassified_as_upstream(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    failure = httpx.ReadTimeout("local cleanup failure")

    def fail_unlink(path: Path, missing_ok: bool = False) -> None:
        raise failure

    monkeypatch.setattr(Path, "unlink", fail_unlink)
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(503, request=request))
    ) as client:
        error = await download_error(boundary_downloader(client, FakeLease()), tmp_path)
    assert error is failure
    assert not hasattr(error, "reason")


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    [
        httpx.ReadTimeout("disk timeout"),
        httpx.ConnectError("disk dependency"),
        TimeoutError("disk timeout"),
        OSError("disk failure"),
    ],
)
async def test_body_failure_keeps_precedence_over_http_close_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    failure: Exception,
) -> None:
    def fail_open(path: Path, *args: Any, **kwargs: Any) -> Any:
        raise failure

    monkeypatch.setattr(Path, "open", fail_open)
    stream = FailingHttpStream(httpx.ReadTimeout("HTTP close failure"), "close")
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, stream=stream, request=request)
        )
    ) as client:
        error = await download_error(boundary_downloader(client, FakeLease()), tmp_path)
    assert error is failure
    assert not hasattr(error, "reason")
    assert stream.closed
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "status,reason", [(302, "TIMEOUT"), (429, "RATE_LIMITED"), (503, "UNAVAILABLE")]
)
async def test_http_context_exit_is_classified_only_without_prior_failure(
    tmp_path: Path,
    status: int,
    reason: str,
) -> None:
    stream = FailingHttpStream(httpx.ReadTimeout("HTTP close failure"), "close")
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                status, headers={"location": "/e-print/2608.00001"}, stream=stream, request=request
            )
        )
    ) as client:
        error = await download_error(boundary_downloader(client, FakeLease(), retries=0), tmp_path)
    assert_safe_transient(error, reason)
    assert stream.closed
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "headers,body",
    [
        ({"content-type": "text/html"}, b"\\documentclass{article}"),
        ({"content-type": "text/plain"}, b"not TeX"),
        ({"content-type": "application/gzip"}, b"not archive"),
        ({"content-length": "-1"}, b"\\documentclass{article}"),
        ({"content-length": "2048"}, b"\\documentclass{article}"),
        ({"content-length": "invalid"}, b"\\documentclass{article}"),
        ({}, b""),
    ],
)
async def test_response_policy_refusals_do_not_acquire_transient_reasons(
    tmp_path: Path,
    headers: dict[str, str],
    body: bytes,
) -> None:
    lease = FakeLease()
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, headers=headers, content=body, request=request)
        )
    ) as client:
        error = await download_error(boundary_downloader(client, lease), tmp_path)
    assert isinstance(error, SourceDownloadSecurityError)
    assert not hasattr(error, "reason")
    assert lease.calls == 1
    assert list(tmp_path.iterdir()) == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    [
        TimeoutError("outer deadline"),
        httpx.RemoteProtocolError("unexpected protocol"),
        httpx.DecodingError("decoder error"),
    ],
)
async def test_other_http_operation_failures_do_not_acquire_transient_reasons(
    tmp_path: Path,
    failure: Exception,
) -> None:
    def respond(request: httpx.Request) -> httpx.Response:
        raise failure

    lease = FakeLease()
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        error = await download_error(boundary_downloader(client, lease), tmp_path)
    assert error is failure
    assert not hasattr(error, "reason")
    assert lease.calls == 1


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
