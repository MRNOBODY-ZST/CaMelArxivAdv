from __future__ import annotations

import json
from datetime import UTC, datetime
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from app.personalization.contracts import PersonalizationCommand
from app.personalization.openai_client import (
    OpenAIEmailClient,
    PermanentGenerationError,
    TransientGenerationError,
)


def command_json() -> dict[str, object]:
    return {
        "version": 1,
        "messageId": str(uuid4()),
        "type": "PERSONALIZE_CAMPAIGN",
        "jobId": str(uuid4()),
        "campaignId": str(uuid4()),
        "idempotencyKey": "personalization:test",
        "traceId": "0123456789abcdef",
        "occurredAt": datetime.now(UTC).isoformat(),
        "payload": {
            "purpose": "Discuss the author's work",
            "templateSubject": "About {{paper_title}}",
            "templateHtml": '<a href="{{unsubscribe_url}}">Unsubscribe</a>',
            "templateText": "Reference {{unsubscribe_url}}",
            "targets": [
                {
                    "recipientId": str(uuid4()),
                    "authorName": "Ada Lovelace",
                    "paperTitle": "Safe Distributed Intelligence",
                    "paperAbstract": "A public abstract.",
                    "arxivId": "2608.00001",
                    "primaryCategory": "cs.AI",
                    "paperUrl": "https://arxiv.org/abs/2608.00001",
                    "organization": "Analytical Engine University",
                }
            ],
        },
    }


def response_payload(content: dict[str, str]) -> dict[str, object]:
    return {
        "id": "resp_test",
        "output": [
            {
                "type": "message",
                "content": [{"type": "output_text", "text": json.dumps(content)}],
            }
        ],
    }


@pytest.mark.asyncio
async def test_calls_responses_api_with_strict_schema_and_parses_output() -> None:
    observed: dict[str, object] = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        observed["authorization"] = request.headers["authorization"]
        observed["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            json=response_payload(
                {
                    "subject": "About Safe Distributed Intelligence",
                    "html": '<p>Hello Ada</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
                    "text": "Hello Ada {{unsubscribe_url}}",
                    "rationale": "Grounded in the paper topic.",
                }
            ),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        client = OpenAIEmailClient(http, SecretStr("sk-test-secret"), "gpt-test")
        command = PersonalizationCommand.model_validate(command_json())
        result = await client.generate(command, command.payload.targets[0])

    assert result.subject == "About Safe Distributed Intelligence"
    assert observed["authorization"] == "Bearer sk-test-secret"
    body = observed["body"]
    assert body["model"] == "gpt-test"  # type: ignore[index]
    assert body["store"] is False  # type: ignore[index]
    output_format = body["text"]["format"]  # type: ignore[index]
    assert output_format["type"] == "json_schema"
    assert output_format["strict"] is True
    assert set(output_format["schema"]["required"]) == {"subject", "html", "text", "rationale"}
    assert "university.edu" not in json.dumps(body)


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [429, 500, 503])
async def test_classifies_retryable_provider_failures(status: int) -> None:
    transport = httpx.MockTransport(lambda request: httpx.Response(status, json={"error": {}}))
    async with httpx.AsyncClient(transport=transport) as http:
        command = PersonalizationCommand.model_validate(command_json())
        with pytest.raises(TransientGenerationError):
            await OpenAIEmailClient(http, SecretStr("sk-test-secret"), "gpt-test").generate(
                command, command.payload.targets[0]
            )


@pytest.mark.asyncio
async def test_redacts_key_and_rejects_authentication_or_malformed_output() -> None:
    command = PersonalizationCommand.model_validate(command_json())
    auth_transport = httpx.MockTransport(
        lambda request: httpx.Response(401, json={"error": {"message": "bad key"}})
    )
    async with httpx.AsyncClient(transport=auth_transport) as http:
        with pytest.raises(PermanentGenerationError) as error:
            await OpenAIEmailClient(http, SecretStr("sk-never-log-me"), "gpt-test").generate(
                command, command.payload.targets[0]
            )
    assert "sk-never-log-me" not in str(error.value)
    assert error.value.code == "AUTHENTICATION_FAILED"

    malformed = httpx.MockTransport(lambda request: httpx.Response(200, json={"output": []}))
    async with httpx.AsyncClient(transport=malformed) as http:
        with pytest.raises(PermanentGenerationError) as error:
            await OpenAIEmailClient(http, SecretStr("sk-test"), "gpt-test").generate(
                command, command.payload.targets[0]
            )
    assert error.value.code == "INVALID_PROVIDER_OUTPUT"
