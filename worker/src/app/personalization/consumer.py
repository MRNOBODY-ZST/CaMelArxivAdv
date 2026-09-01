from __future__ import annotations

from typing import Protocol

from pydantic import ValidationError

from app.jobs.arxiv_consumer import CommandOutcome
from app.personalization.contracts import PersonalizationCommand, PersonalizationResult


class Executor(Protocol):
    async def execute(self, command: PersonalizationCommand) -> list[PersonalizationResult]: ...


class ResultPublisher(Protocol):
    async def publish(self, result: PersonalizationResult) -> None: ...


class PersonalizationCommandProcessor:
    def __init__(
        self,
        executor: Executor,
        publisher: ResultPublisher,
        *,
        maximum_command_bytes: int,
    ) -> None:
        self._executor = executor
        self._publisher = publisher
        self._maximum_command_bytes = maximum_command_bytes

    async def process(self, body: bytes) -> CommandOutcome:
        if not body or len(body) > self._maximum_command_bytes:
            return CommandOutcome.DEAD
        try:
            command = PersonalizationCommand.model_validate_json(body)
        except (ValidationError, ValueError, UnicodeDecodeError):
            return CommandOutcome.DEAD
        try:
            results = await self._executor.execute(command)
            for result in results:
                await self._publisher.publish(result)
            return CommandOutcome.ACK
        except Exception:
            return CommandOutcome.RETRY
