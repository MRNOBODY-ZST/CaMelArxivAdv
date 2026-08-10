from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Protocol, cast
from uuid import uuid4

import httpx
import ray
from ray import ObjectRef

from app.config import PersonalizationSettings
from app.personalization.contracts import (
    GeneratedEmail,
    PersonalizationCommand,
    PersonalizationResult,
    PersonalizationResultPayload,
    PersonalizationTarget,
)
from app.personalization.openai_client import (
    OpenAIEmailClient,
    PermanentGenerationError,
)


class RemoteFunction(Protocol):
    def remote(self, command_json: str, target_json: str) -> object: ...


def _generate_personalization(command_json: str, target_json: str) -> dict[str, str]:
    settings = PersonalizationSettings()
    api_key = settings.api_key
    if not settings.enabled or api_key is None:
        raise RuntimeError("Personalization provider is disabled")
    command = PersonalizationCommand.model_validate_json(command_json)
    target = PersonalizationTarget.model_validate_json(target_json)

    async def generate() -> GeneratedEmail:
        timeout = httpx.Timeout(settings.request_timeout_seconds)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as http:
            client = OpenAIEmailClient(
                http,
                api_key,
                settings.model,
                settings.api_base_url,
            )
            return await client.generate(command, target)

    try:
        return asyncio.run(generate()).model_dump()
    except PermanentGenerationError as exception:
        return {
            "errorCode": exception.code,
            "errorMessage": "Generation was permanently rejected by the provider",
        }


generate_personalization_task = cast(
    RemoteFunction,
    ray.remote(max_retries=2, retry_exceptions=True)(_generate_personalization),
)


def _ray_get(reference: object) -> object:
    return ray.get(cast(ObjectRef[object], reference))


class RayPersonalizationExecutor:
    def __init__(
        self,
        settings: PersonalizationSettings,
        *,
        remote_function: RemoteFunction = generate_personalization_task,
        result_getter: Callable[[object], object] = _ray_get,
        initializer: Callable[[str], object] | None = None,
        shutdown: Callable[[], None] = ray.shutdown,
    ) -> None:
        self._settings = settings
        self._remote = remote_function
        self._result_getter = result_getter
        self._initializer = initializer or self._initialize_ray
        self._shutdown = shutdown
        self._started = False

    async def execute(self, command: PersonalizationCommand) -> list[PersonalizationResult]:
        if not self._settings.enabled:
            return [
                self._failed(command, target, "PROVIDER_DISABLED", "Personalization is disabled")
                for target in command.payload.targets
            ]
        self._start()
        results: list[PersonalizationResult] = []
        command_json = command.model_dump_json(by_alias=True)
        batch_size = self._settings.maximum_concurrency
        targets = list(command.payload.targets)
        for offset in range(0, len(targets), batch_size):
            batch = targets[offset : offset + batch_size]
            references = [
                self._remote.remote(command_json, target.model_dump_json(by_alias=True))
                for target in batch
            ]
            resolved = await asyncio.gather(
                *(
                    self._resolve(command, target, reference)
                    for target, reference in zip(batch, references, strict=True)
                )
            )
            results.extend(resolved)
        return results

    def close(self) -> None:
        if self._started:
            self._shutdown()
            self._started = False

    async def _resolve(
        self,
        command: PersonalizationCommand,
        target: PersonalizationTarget,
        reference: object,
    ) -> PersonalizationResult:
        try:
            value = await asyncio.to_thread(self._result_getter, reference)
            if isinstance(value, dict) and isinstance(value.get("errorCode"), str):
                return self._failed(
                    command,
                    target,
                    value["errorCode"],
                    "Generation was permanently rejected by the provider",
                )
            generated = (
                value
                if isinstance(value, GeneratedEmail)
                else GeneratedEmail.model_validate(value)
            )
            payload = PersonalizationResultPayload(
                status="GENERATED",
                subject=generated.subject,
                html=generated.html,
                text=generated.text,
                rationale=generated.rationale,
                provider=self._settings.provider,
                model=self._settings.model,
            )
            return self._result(command, target, payload)
        except Exception as exception:
            cause = self._task_cause(exception)
            code = getattr(cause, "code", "GENERATION_FAILED")
            if not isinstance(code, str) or not code.replace("_", "").isalnum():
                code = "GENERATION_FAILED"
            return self._failed(
                command,
                target,
                code,
                "Generation failed after bounded retries",
            )

    def _failed(
        self,
        command: PersonalizationCommand,
        target: PersonalizationTarget,
        code: str,
        message: str,
    ) -> PersonalizationResult:
        return self._result(
            command,
            target,
            PersonalizationResultPayload(
                status="FAILED",
                provider=self._settings.provider,
                model=self._settings.model,
                error_code=code,
                error_message=message,
            ),
        )

    def _result(
        self,
        command: PersonalizationCommand,
        target: PersonalizationTarget,
        payload: PersonalizationResultPayload,
    ) -> PersonalizationResult:
        return PersonalizationResult(
            message_id=uuid4(),
            job_id=command.job_id,
            campaign_id=command.campaign_id,
            recipient_id=target.recipient_id,
            idempotency_key=f"result:{command.job_id}:{target.recipient_id}",
            trace_id=command.trace_id,
            occurred_at=datetime.now(UTC),
            payload=payload,
        )

    def _start(self) -> None:
        if not self._started:
            self._initializer(self._settings.ray_address)
            self._started = True

    def _initialize_ray(self, address: str) -> object:
        return ray.init(address=address, ignore_reinit_error=True, logging_level=logging.WARNING)

    def _task_cause(self, exception: Exception) -> BaseException:
        resolver = getattr(exception, "as_instanceof_cause", None)
        if callable(resolver):
            resolved = resolver()
            if isinstance(resolved, BaseException):
                return resolved
        return exception
