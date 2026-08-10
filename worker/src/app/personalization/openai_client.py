from __future__ import annotations

import json
from typing import Any

import httpx
from pydantic import SecretStr, ValidationError

from app.personalization.contracts import (
    GeneratedEmail,
    PersonalizationCommand,
    PersonalizationTarget,
)
from app.personalization.prompt import INSTRUCTIONS, public_generation_input


class GenerationError(RuntimeError):
    def __init__(self, code: str, message: str, *, retryable: bool) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class TransientGenerationError(GenerationError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, message, retryable=True)


class PermanentGenerationError(GenerationError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(code, message, retryable=False)


class OpenAIEmailClient:
    def __init__(
        self,
        http: httpx.AsyncClient,
        api_key: SecretStr,
        model: str,
        base_url: str = "https://api.openai.com/v1",
    ) -> None:
        self._http = http
        self._api_key = api_key
        self._model = model
        self._base_url = base_url.rstrip("/")

    async def generate(
        self,
        command: PersonalizationCommand,
        target: PersonalizationTarget,
    ) -> GeneratedEmail:
        request = {
            "model": self._model,
            "instructions": INSTRUCTIONS,
            "input": json.dumps(
                public_generation_input(command, target), ensure_ascii=False, separators=(",", ":")
            ),
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "personalized_email",
                    "strict": True,
                    "schema": GeneratedEmail.model_json_schema(),
                }
            },
            "store": False,
        }
        try:
            response = await self._http.post(
                f"{self._base_url}/responses",
                headers={
                    "Authorization": f"Bearer {self._api_key.get_secret_value()}",
                    "Content-Type": "application/json",
                },
                json=request,
            )
        except (httpx.TimeoutException, httpx.NetworkError) as exception:
            raise TransientGenerationError(
                "PROVIDER_UNAVAILABLE", "OpenAI request could not reach the provider"
            ) from exception
        self._raise_for_status(response)
        try:
            payload: dict[str, Any] = response.json()
            content = self._output_text(payload)
            return GeneratedEmail.model_validate_json(content)
        except (ValueError, KeyError, TypeError, ValidationError) as exception:
            raise PermanentGenerationError(
                "INVALID_PROVIDER_OUTPUT", "OpenAI returned an invalid structured response"
            ) from exception

    def _raise_for_status(self, response: httpx.Response) -> None:
        if response.is_success:
            return
        if response.status_code == 429:
            raise TransientGenerationError("RATE_LIMITED", "OpenAI rate limit was reached")
        if response.status_code >= 500:
            raise TransientGenerationError(
                "PROVIDER_UNAVAILABLE", "OpenAI is temporarily unavailable"
            )
        if response.status_code in {401, 403}:
            raise PermanentGenerationError(
                "AUTHENTICATION_FAILED", "OpenAI rejected the configured credentials"
            )
        raise PermanentGenerationError(
            "PROVIDER_REJECTED", "OpenAI rejected the generation request"
        )

    def _output_text(self, payload: dict[str, Any]) -> str:
        direct = payload.get("output_text")
        if isinstance(direct, str) and direct:
            return direct
        for output in payload.get("output", []):
            if not isinstance(output, dict):
                continue
            for content in output.get("content", []):
                text = content.get("text") if isinstance(content, dict) else None
                if (
                    isinstance(content, dict)
                    and content.get("type") == "output_text"
                    and isinstance(text, str)
                ):
                    return text
        raise ValueError("Response did not contain output text")
