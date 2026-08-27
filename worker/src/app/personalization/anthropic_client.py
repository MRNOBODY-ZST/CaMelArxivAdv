from __future__ import annotations

import json
from typing import Any, Literal

import httpx
from pydantic import SecretStr, ValidationError

from app.personalization.contracts import (
    GeneratedEmail,
    PersonalizationCommand,
    PersonalizationTarget,
)
from app.personalization.openai_client import PermanentGenerationError, TransientGenerationError
from app.personalization.prompt import INSTRUCTIONS, public_generation_input


class AnthropicEmailClient:
    def __init__(
        self,
        http: httpx.AsyncClient,
        api_key: SecretStr,
        model: str,
        base_url: str = "https://api.anthropic.com/v1",
        *,
        auth_scheme: Literal["x-api-key", "bearer"] = "x-api-key",
    ) -> None:
        self._http = http
        self._api_key = api_key
        self._model = model
        self._auth_scheme = auth_scheme
        base = base_url.rstrip("/")
        self._base_url = base if base.endswith("/v1") else f"{base}/v1"

    async def generate(
        self, command: PersonalizationCommand, target: PersonalizationTarget
    ) -> GeneratedEmail:
        request = {
            "model": self._model,
            "max_tokens": 4096,
            "system": INSTRUCTIONS,
            "messages": [
                {
                    "role": "user",
                    "content": json.dumps(
                        public_generation_input(command, target), ensure_ascii=False
                    ),
                }
            ],
            "tools": [
                {
                    "name": "personalized_email",
                    "description": "Return an email draft for human review, without sending it.",
                    "input_schema": GeneratedEmail.model_json_schema(),
                }
            ],
            "tool_choice": {"type": "tool", "name": "personalized_email"},
        }
        authentication = (
            {"Authorization": f"Bearer {self._api_key.get_secret_value()}"}
            if self._auth_scheme == "bearer"
            else {"x-api-key": self._api_key.get_secret_value()}
        )
        try:
            response = await self._http.post(
                f"{self._base_url}/messages",
                headers={
                    **authentication,
                    "anthropic-version": "2023-06-01",
                    "Content-Type": "application/json",
                },
                json=request,
            )
        except (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError) as exception:
            raise TransientGenerationError(
                "PROVIDER_UNAVAILABLE", "Anthropic request could not reach the provider"
            ) from exception
        self._raise_for_status(response)
        try:
            payload: dict[str, Any] = response.json()
            if payload.get("stop_reason") != "tool_use":
                raise ValueError("Provider did not complete structured output")
            blocks = [
                block
                for block in payload.get("content", [])
                if isinstance(block, dict) and block.get("type") == "tool_use"
            ]
            if len(blocks) != 1 or blocks[0].get("name") != "personalized_email":
                raise ValueError("Provider returned an unexpected structured output")
            return GeneratedEmail.model_validate(blocks[0]["input"])
        except (ValueError, KeyError, TypeError, AttributeError, ValidationError) as exception:
            raise PermanentGenerationError(
                "INVALID_PROVIDER_OUTPUT", "Anthropic returned an invalid structured response"
            ) from exception

    def _raise_for_status(self, response: httpx.Response) -> None:
        if response.is_success:
            return
        if response.status_code == 429:
            raise TransientGenerationError("RATE_LIMITED", "Anthropic rate limit was reached")
        if response.status_code >= 500:
            raise TransientGenerationError(
                "PROVIDER_UNAVAILABLE", "Anthropic is temporarily unavailable"
            )
        if response.status_code in {401, 403}:
            raise PermanentGenerationError(
                "AUTHENTICATION_FAILED", "Anthropic rejected the configured credentials"
            )
        raise PermanentGenerationError(
            "PROVIDER_REJECTED", "Anthropic rejected the generation request"
        )
