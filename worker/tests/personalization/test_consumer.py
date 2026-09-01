from __future__ import annotations

from app.config import PersonalizationSettings
from app.jobs.arxiv_consumer import CommandOutcome
from app.personalization.consumer import PersonalizationCommandProcessor
from app.personalization.contracts import PersonalizationResult
from app.personalization.ray_executor import RayPersonalizationExecutor
from personalization.helpers import command


class FakeExecutor:
    def __init__(self, results: list[PersonalizationResult]) -> None:
        self.results = results
        self.calls = 0

    async def execute(self, value: object) -> list[PersonalizationResult]:
        self.calls += 1
        return self.results


class FakePublisher:
    def __init__(self) -> None:
        self.published: list[PersonalizationResult] = []

    async def publish(self, result: PersonalizationResult) -> None:
        self.published.append(result)


async def test_invalid_command_is_dead_lettered_without_execution() -> None:
    executor = FakeExecutor([])
    publisher = FakePublisher()
    processor = PersonalizationCommandProcessor(executor, publisher, maximum_command_bytes=1024)

    outcome = await processor.process(b'{"recipientEmail":"secret@example.org"}')

    assert outcome is CommandOutcome.DEAD
    assert executor.calls == 0
    assert publisher.published == []


async def test_publishes_every_result_before_acknowledging_command() -> None:
    active = command(2)
    results = await RayPersonalizationExecutor(
        PersonalizationSettings(enabled=False)
    ).execute(active)
    executor = FakeExecutor(results)
    publisher = FakePublisher()
    processor = PersonalizationCommandProcessor(
        executor, publisher, maximum_command_bytes=1024 * 1024
    )

    outcome = await processor.process(active.model_dump_json(by_alias=True).encode())

    assert outcome is CommandOutcome.ACK
    assert executor.calls == 1
    assert publisher.published == results


async def test_executor_failure_requests_counted_retry() -> None:
    class FailingExecutor(FakeExecutor):
        async def execute(self, value: object) -> list[PersonalizationResult]:
            raise RuntimeError("provider unavailable")

    active = command(1)
    processor = PersonalizationCommandProcessor(
        FailingExecutor([]), FakePublisher(), maximum_command_bytes=1024 * 1024
    )

    assert (
        await processor.process(active.model_dump_json(by_alias=True).encode())
        is CommandOutcome.RETRY
    )
