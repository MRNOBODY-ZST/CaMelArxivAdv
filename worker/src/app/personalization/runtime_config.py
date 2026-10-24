"""Job-local credentials and a DNS-pinned, public-only provider transport."""

from __future__ import annotations

import asyncio
import ipaddress
import socket
from collections.abc import Iterable
from typing import Literal
from urllib.parse import urlsplit, urlunsplit

import httpcore
import httpx
from httpcore._backends.anyio import AnyIOBackend
from httpcore._backends.base import SOCKET_OPTION, AsyncNetworkStream
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

from app.config import PersonalizationSettings


def _public_address(value: str) -> bool:
    address = ipaddress.ip_address(value)
    return address.is_global and not address.is_multicast and not address.is_reserved


def normalize_origin(value: str) -> str:
    parsed = urlsplit(value)
    host = parsed.hostname
    if (
        parsed.scheme != "https"
        or not host
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or "?" in value
        or "#" in value
    ):
        raise ValueError("Provider origin must be public HTTPS")
    if host.rstrip(".").lower() == "localhost" or host.lower().endswith(".localhost"):
        raise ValueError("Provider origin must be public HTTPS")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        address = None
    if address is not None and not _public_address(host):
        raise ValueError("Provider origin must be public HTTPS")
    authority = f"[{host}]" if ":" in host else host.lower()
    if parsed.port and parsed.port != 443:
        authority += f":{parsed.port}"
    return urlunsplit(("https", authority, parsed.path.rstrip("/") or "/v1", "", ""))


class RuntimeConfiguration(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        populate_by_name=True,
        hide_input_in_errors=True,
        strict=True,
        str_strip_whitespace=True,
    )
    provider: Literal["openai", "anthropic"]
    model: str = Field(min_length=1, max_length=120)
    api_base_url: str = Field(alias="apiBaseUrl")
    api_auth_scheme: Literal["x-api-key", "bearer"] = Field(alias="apiAuthScheme")
    api_key: SecretStr = Field(alias="apiKey")

    _normalize = field_validator("api_base_url")(normalize_origin)

    @field_validator("api_key")
    @classmethod
    def nonempty_key(cls, value: SecretStr) -> SecretStr:
        if not value.get_secret_value().strip():
            raise ValueError("Provider key is required")
        return value


async def resolve_configuration(
    settings: PersonalizationSettings,
    job_id: str,
    http: httpx.AsyncClient,
) -> RuntimeConfiguration:
    if settings.runtime_config_url:
        try:
            token = settings.runtime_config_token
            assert token is not None
            response = await http.get(
                f"{settings.runtime_config_url.rstrip('/')}/internal/personalization/jobs/"
                f"{job_id}/configuration",
                headers={"Authorization": f"Bearer {token.get_secret_value()}"},
                follow_redirects=False,
            )
            response.raise_for_status()
            return RuntimeConfiguration.model_validate(response.json())
        except Exception:
            raise RuntimeError("Runtime configuration unavailable or invalid") from None
    if not settings.enabled or settings.api_key is None:
        raise RuntimeError("Personalization provider is disabled")
    return RuntimeConfiguration.model_validate(
        {
            "provider": settings.provider,
            "model": settings.model,
            "apiBaseUrl": settings.api_base_url,
            # Standalone OpenAI historically always sent bearer authentication;
            # runtime snapshots explicitly control either supported scheme.
            "apiAuthScheme": "bearer"
            if settings.provider == "openai"
            else settings.api_auth_scheme,
            "apiKey": settings.api_key,
        }
    )


class PublicNetworkBackend(AnyIOBackend):
    async def connect_tcp(
        self,
        host: str,
        port: int,
        timeout: float | None = None,
        local_address: str | None = None,
        socket_options: Iterable[SOCKET_OPTION] | None = None,
    ) -> AsyncNetworkStream:
        # Resolve once at connection time, validate ALL answers, then connect to a
        # numeric address. TLS/SNI remain the original hostname in httpcore.
        loop = asyncio.get_running_loop()
        deadline = None if timeout is None else loop.time() + timeout
        try:
            async with asyncio.timeout_at(deadline):
                answers = await asyncio.to_thread(
                    socket.getaddrinfo,
                    host,
                    port,
                    type=socket.SOCK_STREAM,
                )
                addresses = list(dict.fromkeys(str(answer[4][0]) for answer in answers))
                if not addresses or any(not _public_address(ip) for ip in addresses):
                    raise httpcore.ConnectError("Provider destination must be public")
                for index, address in enumerate(addresses):
                    remaining = None if deadline is None else max(0.0, deadline - loop.time())
                    try:
                        return await super().connect_tcp(
                            address,
                            port,
                            timeout=remaining,
                            local_address=local_address,
                            socket_options=socket_options,
                        )
                    except (httpcore.ConnectError, httpcore.ConnectTimeout):
                        if index == len(addresses) - 1:
                            raise
        except TimeoutError:
            raise httpcore.ConnectTimeout("Provider connection timed out") from None
        raise httpcore.ConnectError("Provider connection failed")


class PublicProviderTransport(httpx.AsyncHTTPTransport):
    def __init__(self) -> None:
        super().__init__(trust_env=False)
        # HTTPX has no public network_backend injection. Keep this adaptation
        # isolated; the pool otherwise retains HTTPX's verified TLS defaults.
        self._pool._network_backend = PublicNetworkBackend()
