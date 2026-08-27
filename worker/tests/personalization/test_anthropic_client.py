from __future__ import annotations

import json

import httpx
import pytest
from pydantic import SecretStr

from app.personalization.openai_client import PermanentGenerationError, TransientGenerationError
from personalization.helpers import command


def generated() -> dict[str, str]:
    return {
        "subject": "About Paper 0",
        "html": '<p>About the public abstract.</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
        "text": "About the public abstract. {{unsubscribe_url}}",
        "rationale": "Grounded in the supplied abstract.",
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("base", ["https://gateway.example", "https://gateway.example/v1/"])
async def test_messages_request_uses_anthropic_auth_and_parses_structured_tool_output(
    base: str,
) -> None:
    from app.personalization.anthropic_client import AnthropicEmailClient

    async def handler(request: httpx.Request) -> httpx.Response:
        assert str(request.url) == "https://gateway.example/v1/messages"
        assert request.headers["x-api-key"] == "private-test-key"
        assert request.headers["anthropic-version"] == "2023-06-01"
        assert "authorization" not in request.headers
        body = json.loads(request.content)
        assert body["model"] == "claude-opus-4-6"
        assert body["max_tokens"] > 0
        assert body["tool_choice"] == {"type": "tool", "name": "personalized_email"}
        assert set(body["tools"][0]["input_schema"]["required"]) == {
            "subject",
            "html",
            "text",
            "rationale",
        }
        assert "private-test-key" not in request.content.decode()
        assert "recipientId" not in body["messages"][0]["content"]
        return httpx.Response(
            200,
            json={
                "type": "message",
                "stop_reason": "tool_use",
                "content": [
                    {
                        "type": "tool_use",
                        "id": "toolu_test",
                        "name": "personalized_email",
                        "input": generated(),
                    },
                ],
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        active = command(1)
        output = await AnthropicEmailClient(
            http, SecretStr("private-test-key"), "claude-opus-4-6", base
        ).generate(active, active.payload.targets[0])
    assert output.subject == "About Paper 0"
    assert output.text.endswith("{{unsubscribe_url}}")


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "status,code,retryable",
    [
        (401, "AUTHENTICATION_FAILED", False),
        (403, "AUTHENTICATION_FAILED", False),
        (400, "PROVIDER_REJECTED", False),
        (429, "RATE_LIMITED", True),
        (503, "PROVIDER_UNAVAILABLE", True),
    ],
)
async def test_provider_errors_are_classified_without_leaking_body(
    status: int, code: str, retryable: bool
) -> None:
    from app.personalization.anthropic_client import AnthropicEmailClient

    transport = httpx.MockTransport(
        lambda request: httpx.Response(status, json={"error": {"message": "private-test-key"}})
    )
    async with httpx.AsyncClient(transport=transport) as http:
        active = command(1)
        with pytest.raises(
            TransientGenerationError if retryable else PermanentGenerationError
        ) as error:
            await AnthropicEmailClient(
                http, SecretStr("private-test-key"), "claude-opus-4-6"
            ).generate(active, active.payload.targets[0])
    assert error.value.code == code
    assert "private-test-key" not in str(error.value)


@pytest.mark.asyncio
async def test_gateway_disconnect_is_retryable_without_exposing_transport_details() -> None:
    from app.personalization.anthropic_client import AnthropicEmailClient

    async def disconnect(request: httpx.Request) -> httpx.Response:
        raise httpx.RemoteProtocolError("private transport details", request=request)

    async with httpx.AsyncClient(transport=httpx.MockTransport(disconnect)) as http:
        active = command(1)
        with pytest.raises(TransientGenerationError) as error:
            await AnthropicEmailClient(
                http, SecretStr("private-test-key"), "claude-opus-4-6"
            ).generate(active, active.payload.targets[0])
    assert error.value.code == "PROVIDER_UNAVAILABLE"
    assert "private" not in str(error.value)


@pytest.mark.asyncio
async def test_compatible_gateway_can_use_bearer_auth_without_changing_messages_format() -> None:
    from app.personalization.anthropic_client import AnthropicEmailClient

    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["authorization"] == "Bearer private-test-key"
        assert "x-api-key" not in request.headers
        assert request.headers["anthropic-version"] == "2023-06-01"
        assert request.url.path == "/v1/messages"
        return httpx.Response(
            200,
            json={
                "type": "message",
                "stop_reason": "tool_use",
                "content": [
                    {"type": "tool_use", "name": "personalized_email", "input": generated()}
                ],
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        active = command(1)
        output = await AnthropicEmailClient(
            http, SecretStr("private-test-key"), "claude-opus-4-6", auth_scheme="bearer"
        ).generate(active, active.payload.targets[0])
    assert output.subject == "About Paper 0"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "payload",
    [
        {"type": "message", "stop_reason": "max_tokens", "content": []},
        {
            "type": "message",
            "stop_reason": "end_turn",
            "content": [{"type": "text", "text": "not JSON"}],
        },
        {
            "type": "message",
            "stop_reason": "tool_use",
            "content": [{"type": "tool_use", "name": "other_tool", "input": generated()}],
        },
        {
            "type": "message",
            "stop_reason": "tool_use",
            "content": [
                {
                    "type": "tool_use",
                    "name": "personalized_email",
                    "input": {**generated(), "text": "missing unsubscribe"},
                }
            ],
        },
    ],
)
async def test_rejects_truncated_wrong_tool_or_noncompliant_output(
    payload: dict[str, object],
) -> None:
    from app.personalization.anthropic_client import AnthropicEmailClient

    transport = httpx.MockTransport(lambda request: httpx.Response(200, json=payload))
    async with httpx.AsyncClient(transport=transport) as http:
        active = command(1)
        with pytest.raises(PermanentGenerationError) as error:
            await AnthropicEmailClient(
                http, SecretStr("private-test-key"), "claude-opus-4-6"
            ).generate(active, active.payload.targets[0])
    assert error.value.code == "INVALID_PROVIDER_OUTPUT"
