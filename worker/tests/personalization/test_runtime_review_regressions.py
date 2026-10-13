from __future__ import annotations

import asyncio
import socket
import ssl
from typing import Any

import httpcore
import httpx
import pytest
from httpcore._backends.anyio import AnyIOBackend
from httpcore._backends.base import AsyncNetworkStream
from ray import cloudpickle

from app.config import PersonalizationSettings
from app.personalization import ray_executor
from app.personalization.openai_client import OpenAIEmailClient, TransientGenerationError
from app.personalization.runtime_config import PublicNetworkBackend, PublicProviderTransport
from personalization.helpers import command
from personalization.test_ray_executor import FakeRemote


def test_remote_settings_error_has_no_serialized_credentials(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("PERSONALIZATION_RUNTIME_CONFIG_URL", raising=False)
    monkeypatch.setenv("PERSONALIZATION_RUNTIME_CONFIG_TOKEN", "s3cr")
    active = command(1)
    with pytest.raises(Exception) as error:
        ray_executor._generate_personalization(
            active.model_dump_json(by_alias=True),
            active.payload.targets[0].model_dump_json(by_alias=True),
        )
    serialized = cloudpickle.dumps(error.value)  # type: ignore[no-untyped-call]
    assert b"s3cr" not in serialized
    assert type(cloudpickle.loads(serialized)) is RuntimeError


def answers(*addresses: str) -> list[tuple[Any, ...]]:
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, 443)) for ip in addresses]


@pytest.mark.asyncio
async def test_pinned_addresses_fall_back_with_shared_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        socket, "getaddrinfo", lambda *args, **kwargs: answers("8.8.8.8", "1.1.1.1")
    )
    attempts: list[tuple[str, float | None]] = []
    stream = AsyncNetworkStream()

    async def connect(self: object, host: str, port: int, **kwargs: Any) -> AsyncNetworkStream:
        attempts.append((host, kwargs["timeout"]))
        if host == "8.8.8.8":
            await asyncio.sleep(0.02)
            raise httpcore.ConnectError("unreachable")
        return stream

    monkeypatch.setattr(AnyIOBackend, "connect_tcp", connect)
    assert await PublicNetworkBackend().connect_tcp("example.com", 443, timeout=1) is stream
    assert [host for host, _ in attempts] == ["8.8.8.8", "1.1.1.1"]
    first, second = attempts[0][1], attempts[1][1]
    assert first is not None and second is not None
    assert 0 < second < first <= 1


@pytest.mark.asyncio
async def test_mixed_private_dns_never_connects(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        socket, "getaddrinfo", lambda *args, **kwargs: answers("8.8.8.8", "127.0.0.1")
    )
    connected: list[str] = []

    async def connect(self: object, host: str, port: int, **kwargs: Any) -> AsyncNetworkStream:
        connected.append(host)
        return AsyncNetworkStream()

    monkeypatch.setattr(AnyIOBackend, "connect_tcp", connect)
    with pytest.raises(httpcore.ConnectError):
        await PublicNetworkBackend().connect_tcp("example.com", 443)
    assert connected == []


@pytest.mark.asyncio
async def test_connection_deadline_is_not_reset_between_addresses(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        socket, "getaddrinfo", lambda *args, **kwargs: answers("8.8.8.8", "1.1.1.1")
    )
    connected: list[str] = []

    async def connect(self: object, host: str, port: int, **kwargs: Any) -> AsyncNetworkStream:
        connected.append(host)
        await asyncio.sleep(0.04)
        if host == "8.8.8.8":
            raise httpcore.ConnectError("unreachable")
        return AsyncNetworkStream()

    monkeypatch.setattr(AnyIOBackend, "connect_tcp", connect)
    with pytest.raises(httpcore.ConnectTimeout):
        await PublicNetworkBackend().connect_tcp("example.com", 443, timeout=0.06)
    assert connected == ["8.8.8.8", "1.1.1.1"]


@pytest.mark.asyncio
async def test_transport_pins_ip_but_preserves_verified_original_tls_identity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(socket, "getaddrinfo", lambda *args, **kwargs: answers("8.8.8.8"))
    identities: list[str | None] = []
    connected: list[str] = []

    class Stream(AsyncNetworkStream):
        async def start_tls(
            self,
            ssl_context: ssl.SSLContext,
            server_hostname: str | None = None,
            timeout: float | None = None,
        ) -> AsyncNetworkStream:
            assert ssl_context.check_hostname
            assert ssl_context.verify_mode == ssl.CERT_REQUIRED
            identities.append(server_hostname)
            return self

        async def write(self, buffer: bytes, timeout: float | None = None) -> None:
            pass

        async def read(self, max_bytes: int, timeout: float | None = None) -> bytes:
            return b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"

        async def aclose(self) -> None:
            pass

        def get_extra_info(self, info: str) -> Any:
            return None

    async def connect(self: object, host: str, port: int, **kwargs: Any) -> AsyncNetworkStream:
        connected.append(host)
        return Stream()

    monkeypatch.setattr(AnyIOBackend, "connect_tcp", connect)
    async with httpx.AsyncClient(transport=PublicProviderTransport(), trust_env=False) as http:
        response = await http.get("https://provider.example/v1/test")
    assert response.text == "ok"
    assert connected == ["8.8.8.8"]
    assert identities == ["provider.example"]


@pytest.mark.parametrize(
    "code", ["RATE_LIMITED", "PROVIDER_UNAVAILABLE", "INVALID_PROVIDER_OUTPUT", "s3cr"]
)
def test_task_to_consumer_preserves_only_safe_transient_codes(
    monkeypatch: pytest.MonkeyPatch,
    code: str,
) -> None:
    monkeypatch.setenv("PERSONALIZATION_ENABLED", "true")
    monkeypatch.setenv("PERSONALIZATION_PROVIDER", "openai")
    monkeypatch.setenv("PERSONALIZATION_API_KEY", "s3cr")
    monkeypatch.delenv("PERSONALIZATION_RUNTIME_CONFIG_URL", raising=False)
    monkeypatch.delenv("PERSONALIZATION_RUNTIME_CONFIG_TOKEN", raising=False)

    async def generate(self: object, *args: object) -> object:
        raise TransientGenerationError(code, "s3cr provider body")

    monkeypatch.setattr(OpenAIEmailClient, "generate", generate)
    active = command(1)
    with pytest.raises(RuntimeError) as error:
        ray_executor._generate_personalization(
            active.model_dump_json(by_alias=True),
            active.payload.targets[0].model_dump_json(by_alias=True),
        )
    serialized = cloudpickle.dumps(error.value)  # type: ignore[no-untyped-call]
    assert b"s3cr" not in serialized
    failure = cloudpickle.loads(serialized)

    def result(reference: object) -> object:
        raise failure

    executor = ray_executor.RayPersonalizationExecutor(
        PersonalizationSettings(enabled=True, api_key="s3cr"),
        remote_function=FakeRemote(),
        result_getter=result,
        initializer=lambda address: None,
        shutdown=lambda: None,
    )
    output = asyncio.run(executor.execute(active))[0]
    assert output.payload.error_code == (code if code != "s3cr" else "GENERATION_FAILED")
    assert output.payload.provider == "openai"
    assert "s3cr" not in output.model_dump_json()
