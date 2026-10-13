from __future__ import annotations

from typing import Any

import httpx
import pytest
from pydantic import SecretStr

from app.config import PersonalizationSettings
from app.personalization import ray_executor
from app.personalization.contracts import GeneratedEmail
from app.personalization.ray_executor import RayPersonalizationExecutor
from personalization.helpers import command


class FakeRemote:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def remote(self, command_json: str, target_json: str) -> tuple[str, str]:
        self.calls.append((command_json, target_json))
        return command_json, target_json


@pytest.mark.asyncio
async def test_fans_out_in_bounded_batches_and_preserves_partial_results() -> None:
    remote = FakeRemote()
    initialized: list[str] = []
    active = command(3)

    def get_result(reference: object) -> GeneratedEmail:
        assert isinstance(reference, tuple)
        if "Author 1" in reference[1]:
            raise RuntimeError("provider task exhausted retries")
        return GeneratedEmail(
            subject="Paper-aware subject",
            html='<p>Hello</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
            text="Hello {{unsubscribe_url}}",
            rationale="Grounded in the paper.",
        )

    executor = RayPersonalizationExecutor(
        PersonalizationSettings(
            enabled=True,
            api_key=SecretStr("sk-test"),
            model="gpt-test",
            ray_address="ray://ray-head:10001",
            maximum_concurrency=2,
        ),
        remote_function=remote,
        result_getter=get_result,
        initializer=lambda address: initialized.append(address),
        shutdown=lambda: None,
    )

    results = await executor.execute(active)

    assert initialized == ["ray://ray-head:10001"]
    assert len(remote.calls) == 3
    assert [result.payload.status for result in results] == ["GENERATED", "FAILED", "GENERATED"]
    assert results[1].payload.error_code == "GENERATION_FAILED"
    assert {result.recipient_id for result in results} == {
        target.recipient_id for target in active.payload.targets
    }
    assert "email" not in "".join(call[0] + call[1] for call in remote.calls).casefold()


@pytest.mark.asyncio
async def test_disabled_provider_returns_failures_without_starting_ray() -> None:
    remote = FakeRemote()
    initialized: list[str] = []
    executor = RayPersonalizationExecutor(
        PersonalizationSettings(enabled=False),
        remote_function=remote,
        result_getter=lambda reference: reference,
        initializer=lambda address: initialized.append(address),
        shutdown=lambda: None,
    )

    results = await executor.execute(command(2))

    assert initialized == []
    assert remote.calls == []
    assert {result.payload.error_code for result in results} == {"PROVIDER_DISABLED"}


@pytest.mark.parametrize("auth_scheme", ["x-api-key", "bearer"])
def test_remote_task_selects_anthropic_protocol_from_runtime_configuration(
    monkeypatch: pytest.MonkeyPatch,
    auth_scheme: str,
) -> None:
    for key, value in {
        "PERSONALIZATION_ENABLED": "true",
        "PERSONALIZATION_PROVIDER": "anthropic",
        "PERSONALIZATION_MODEL": "claude-opus-4-6",
        "PERSONALIZATION_API_KEY": "test-only",
        "PERSONALIZATION_API_BASE_URL": "https://gateway.example/v1",
        "PERSONALIZATION_API_AUTH_SCHEME": auth_scheme,
    }.items():
        monkeypatch.setenv(key, value)
    original_client = httpx.AsyncClient

    def reply(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/messages"
        if auth_scheme == "bearer":
            assert request.headers["authorization"] == "Bearer test-only"
            assert "x-api-key" not in request.headers
        else:
            assert request.headers["x-api-key"] == "test-only"
        return httpx.Response(
            200,
            json={
                "type": "message",
                "stop_reason": "tool_use",
                "content": [
                    {
                        "type": "tool_use",
                        "name": "personalized_email",
                        "input": {
                            "subject": "A researched draft",
                            "html": "<p>{{unsubscribe_url}}</p>",
                            "text": "Unsubscribe: {{unsubscribe_url}}",
                            "rationale": "Public paper context.",
                        },
                    }
                ],
            },
        )

    monkeypatch.setattr(
        httpx,
        "AsyncClient",
        lambda **kwargs: original_client(
            transport=httpx.MockTransport(reply),
            **{key: value for key, value in kwargs.items() if key != "transport"},
        ),
    )
    active = command(1)
    output = ray_executor._generate_personalization(
        active.model_dump_json(by_alias=True),
        active.payload.targets[0].model_dump_json(by_alias=True),
    )
    email = output["email"]
    assert isinstance(email, dict)
    assert email["subject"] == "A researched draft"


@pytest.mark.asyncio
async def test_dynamic_executor_uses_actual_snapshot_metadata_when_env_disabled() -> None:
    remote = FakeRemote()
    executor = RayPersonalizationExecutor(
        PersonalizationSettings(
            runtime_config_url="http://backend-api:8080", runtime_config_token="service-secret"
        ),
        remote_function=remote,
        result_getter=lambda reference: {
            "provider": "anthropic",
            "model": "snapshot-model",
            "email": {
                "subject": "Draft",
                "html": "{{unsubscribe_url}}",
                "text": "{{unsubscribe_url}}",
                "rationale": "Context",
            },
        },
        initializer=lambda address: None,
        shutdown=lambda: None,
    )
    result = (await executor.execute(command(1)))[0]
    assert result.payload.status == "GENERATED"
    assert result.payload.provider == "anthropic"
    assert result.payload.model == "snapshot-model"
    assert "service-secret" not in str(remote.calls) + result.model_dump_json()


def test_remote_fetches_each_job_snapshot_inside_task(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PERSONALIZATION_ENABLED", "false")
    monkeypatch.setenv("PERSONALIZATION_RUNTIME_CONFIG_URL", "http://backend-api:8080")
    monkeypatch.setenv("PERSONALIZATION_RUNTIME_CONFIG_TOKEN", "service-secret")
    original_client = httpx.AsyncClient
    jobs: list[str] = []
    models: list[str] = []
    active = command(1)
    later = command(1)

    def reply(request: httpx.Request) -> httpx.Response:
        import json

        if request.url.host == "backend-api":
            jobs.append(request.url.path)
            return httpx.Response(
                200,
                json={
                    "provider": "openai",
                    "model": "model-a" if str(active.job_id) in request.url.path else "model-b",
                    "apiBaseUrl": "https://example.com/prefix",
                    "apiAuthScheme": "x-api-key",
                    "apiKey": "provider-secret",
                },
            )
        assert request.url.path == "/prefix/responses"
        assert request.headers["x-api-key"] == "provider-secret"
        models.append(json.loads(request.content)["model"])
        return httpx.Response(
            200,
            json={
                "output_text": json.dumps(
                    {
                        "subject": "Draft",
                        "html": "{{unsubscribe_url}}",
                        "text": "{{unsubscribe_url}}",
                        "rationale": "Context",
                    }
                )
            },
        )

    def client(**kwargs: Any) -> httpx.AsyncClient:
        kwargs.pop("transport", None)
        return original_client(transport=httpx.MockTransport(reply), **kwargs)

    monkeypatch.setattr(httpx, "AsyncClient", client)
    for item, expected in [(active, "model-a"), (later, "model-b"), (active, "model-a")]:
        output = ray_executor._generate_personalization(
            item.model_dump_json(by_alias=True),
            item.payload.targets[0].model_dump_json(by_alias=True),
        )
        assert output["model"] == expected
        assert "secret" not in str(output)
    assert jobs == [
        f"/internal/personalization/jobs/{item.job_id}/configuration"
        for item in (active, later, active)
    ]
    assert models == ["model-a", "model-b", "model-a"]


@pytest.mark.asyncio
async def test_dynamic_failure_metadata_never_claims_environment_model() -> None:
    def fail(reference: object) -> object:
        raise RuntimeError("service-secret must not leave exception boundary")

    executor = RayPersonalizationExecutor(
        PersonalizationSettings(
            runtime_config_url="http://backend-api:8080", runtime_config_token="service-secret"
        ),
        remote_function=FakeRemote(),
        result_getter=fail,
        initializer=lambda address: None,
        shutdown=lambda: None,
    )
    result = (await executor.execute(command(1)))[0]
    assert result.payload.provider == result.payload.model == "unknown"
    assert "service-secret" not in result.model_dump_json()


@pytest.mark.asyncio
async def test_permanent_failure_uses_provider_snapshot_metadata() -> None:
    executor = RayPersonalizationExecutor(
        PersonalizationSettings(
            runtime_config_url="http://backend-api:8080", runtime_config_token="service-secret"
        ),
        remote_function=FakeRemote(),
        result_getter=lambda reference: {
            "provider": "anthropic",
            "model": "model-frozen",
            "errorCode": "PROVIDER_REJECTED",
        },
        initializer=lambda address: None,
        shutdown=lambda: None,
    )
    result = (await executor.execute(command(1)))[0]
    assert result.payload.provider == "anthropic"
    assert result.payload.model == "model-frozen"
    assert result.payload.error_code == "PROVIDER_REJECTED"
