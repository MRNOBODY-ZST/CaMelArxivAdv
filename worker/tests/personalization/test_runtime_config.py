from __future__ import annotations

import httpx
import pytest
from pydantic import ValidationError

from app.config import PersonalizationSettings


def test_runtime_mode_starts_without_local_key() -> None:
    settings = PersonalizationSettings(
        enabled=True,
        runtime_config_url="http://backend-api:8080",
        runtime_config_token="service-secret",
        api_key=None,
    )
    assert settings.runtime_config_url == "http://backend-api:8080"
    assert "service-secret" not in repr(settings)


@pytest.mark.parametrize(
    "values",
    [
        {"runtime_config_url": "http://backend-api:8080"},
        {"runtime_config_token": "service-secret"},
    ],
)
def test_runtime_requires_both_url_and_token(values: dict[str, str]) -> None:
    with pytest.raises(ValidationError):
        PersonalizationSettings.model_validate(values)


def test_settings_validation_error_hides_credentials() -> None:
    with pytest.raises(ValidationError) as error:
        PersonalizationSettings(runtime_config_token="s3cr")
    assert "s3cr" not in str(error.value)


@pytest.mark.asyncio
async def test_snapshot_is_fetched_by_job_without_fallback() -> None:
    from app.personalization.runtime_config import resolve_configuration

    settings = PersonalizationSettings(
        runtime_config_url="http://backend-api:8080",
        runtime_config_token="service-secret",
    )
    paths: list[str] = []

    def reply(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        assert request.headers["authorization"] == "Bearer service-secret"
        return httpx.Response(
            200,
            json={
                "provider": "anthropic",
                "model": "pinned-model",
                "apiBaseUrl": "https://EXAMPLE.com/",
                "apiAuthScheme": "bearer",
                "apiKey": "provider-secret",
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(reply)) as http:
        first = await resolve_configuration(settings, "job-a", http)
        second = await resolve_configuration(settings, "job-a", http)
    assert first.model == second.model == "pinned-model"
    assert first.api_base_url == "https://example.com/v1"
    assert "provider-secret" not in repr(first)
    assert paths == ["/internal/personalization/jobs/job-a/configuration"] * 2


@pytest.mark.parametrize("status", [302, 401, 404, 500])
@pytest.mark.asyncio
async def test_snapshot_http_failure_never_uses_environment(status: int) -> None:
    from app.personalization.runtime_config import resolve_configuration

    settings = PersonalizationSettings(
        enabled=True,
        api_key="fallback-secret",
        runtime_config_url="http://backend-api:8080",
        runtime_config_token="service-secret",
    )
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(status, text="provider-secret")
        )
    ) as http:
        with pytest.raises(RuntimeError) as error:
            await resolve_configuration(settings, "job-a", http)
    assert "secret" not in str(error.value)


@pytest.mark.parametrize(
    "origin",
    [
        "http://example.com",
        "https://127.0.0.1",
        "https://[::1]",
        "https://localhost",
        "https://169.254.169.254",
        "https://user:pass@example.com",
        "https://example.com?key=x",
        "https://224.0.0.1",
    ],
)
def test_provider_origin_rejects_unsafe_destinations(origin: str) -> None:
    from app.personalization.runtime_config import normalize_origin

    with pytest.raises(ValueError):
        normalize_origin(origin)


@pytest.mark.asyncio
async def test_connection_pins_validated_ip_and_rejects_rebinding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import socket

    from httpcore._backends.anyio import AnyIOBackend

    from app.personalization.runtime_config import PublicNetworkBackend

    connected: list[str] = []

    async def connect(self: object, host: str, port: int, **kwargs: object) -> object:
        connected.append(host)
        return object()

    monkeypatch.setattr(AnyIOBackend, "connect_tcp", connect)
    monkeypatch.setattr(
        socket,
        "getaddrinfo",
        lambda *args, **kwargs: [
            (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443)),
        ],
    )
    backend = PublicNetworkBackend()
    await backend.connect_tcp("example.com", 443)
    assert connected == ["8.8.8.8"]
    monkeypatch.setattr(
        socket,
        "getaddrinfo",
        lambda *args, **kwargs: [
            (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 443)),
        ],
    )
    with pytest.raises(Exception, match="public"):
        await backend.connect_tcp("example.com", 443)
    assert connected == ["8.8.8.8"]


@pytest.mark.parametrize(
    "patch",
    [
        {"apiKey": ""},
        {"apiKey": None},
        {"provider": "unsupported"},
        {"model": ""},
        {"model": "   "},
        {"unexpected": "secret"},
        {"apiBaseUrl": "https://127.0.0.1"},
    ],
)
@pytest.mark.asyncio
async def test_invalid_snapshot_is_sanitized(patch: dict[str, object]) -> None:
    from app.personalization.runtime_config import resolve_configuration

    payload = {
        "provider": "openai",
        "model": "model-a",
        "apiKey": "provider-secret",
        "apiBaseUrl": "https://example.com",
        "apiAuthScheme": "bearer",
        **patch,
    }
    settings = PersonalizationSettings(
        runtime_config_url="http://backend-api:8080", runtime_config_token="service-secret"
    )
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=payload),
        )
    ) as http:
        with pytest.raises(RuntimeError) as error:
            await resolve_configuration(settings, "job-a", http)
    assert "secret" not in str(error.value)


def test_explicit_prefix_is_preserved_for_anthropic() -> None:
    from app.personalization.runtime_config import normalize_origin

    assert normalize_origin("https://EXAMPLE.com:443/custom/") == "https://example.com/custom"


@pytest.mark.asyncio
async def test_standalone_openai_preserves_legacy_bearer_authentication() -> None:
    from app.personalization.runtime_config import resolve_configuration

    settings = PersonalizationSettings(enabled=True, api_key="legacy-key")
    async with httpx.AsyncClient() as http:
        resolved = await resolve_configuration(settings, "job-a", http)
    assert resolved.api_auth_scheme == "bearer"
