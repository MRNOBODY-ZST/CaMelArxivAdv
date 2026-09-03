from __future__ import annotations

import json
import re
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
            return self._generated_email(payload)
        except (ValueError, KeyError, TypeError, AttributeError, ValidationError) as exception:
            raise TransientGenerationError(
                "INVALID_PROVIDER_OUTPUT", "Anthropic returned an invalid structured response"
            ) from exception

    def _generated_email(self, payload: dict[str, Any]) -> GeneratedEmail:
        content = payload.get("content")
        if not isinstance(content, list):
            raise ValueError("Provider response content was not a list")
        for block in content:
            if (
                not isinstance(block, dict)
                or block.get("type") != "tool_use"
                or block.get("name") != "personalized_email"
            ):
                continue
            try:
                return GeneratedEmail.model_validate(block["input"])
            except (KeyError, TypeError, ValidationError):
                continue
        for block in content:
            if not isinstance(block, dict) or block.get("type") != "text":
                continue
            text = block.get("text")
            if not isinstance(text, str):
                continue
            generated = self._text_fallback(text)
            if generated is not None:
                return generated
        raise ValueError("Provider did not return a compliant email draft")

    def _text_fallback(self, value: str) -> GeneratedEmail | None:
        candidate = value.strip()
        if candidate.startswith("```") and candidate.endswith("```"):
            candidate = re.sub(r"^```(?:json)?\s*", "", candidate, count=1, flags=re.I)
            candidate = re.sub(r"\s*```$", "", candidate, count=1)
        try:
            return GeneratedEmail.model_validate_json(candidate)
        except (ValueError, ValidationError):
            pass
        subject = self._section(
            value,
            r"^\s*(?:\*\*)?Subject\s*:(?:\*\*)?\s*(?P<value>.+?)\s*$",
        )
        text = self._fenced_section(
            value,
            "Plain(?:\\s+text)?(?:\\s+version)?",
            "(?:text|plaintext|txt)?",
        )
        html = self._fenced_section(value, "HTML(?:\\s+version)?", "html")
        if subject is None or text is None or html is None:
            return None
        return GeneratedEmail(
            subject=subject,
            html=html,
            text=text,
            rationale="Normalized from the provider's structured email fallback.",
        )

    def _section(self, value: str, pattern: str) -> str | None:
        match = re.search(pattern, value, re.I | re.M)
        return match.group("value").strip() if match else None

    def _fenced_section(self, value: str, label: str, language: str) -> str | None:
        pattern = (
            rf"^\s*(?:\*\*)?{label}\s*:(?:\*\*)?\s*$"
            rf"\s*^```{language}\s*$\s*(?P<value>.*?)\s*^```\s*$"
        )
        match = re.search(pattern, value, re.I | re.M | re.S)
        return match.group("value").strip() if match else None

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
