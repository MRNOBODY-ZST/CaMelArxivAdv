from __future__ import annotations

import pytest

from app.jobs.arxiv_consumer import CommandOutcome
from app.messaging.rabbit import settle_delivery


class FakeIncoming:
    body = b"{}"
    content_type = "application/json"
    content_encoding = "utf-8"
    message_id = "message-1"
    type = "ARXIV_IMPORT_METADATA"
    routing_key = "arxiv.import.metadata"

    def __init__(self, events: list[str], retry_count: int = 0) -> None:
        self.events = events
        self.headers = {"camelRetryCount": retry_count}

    async def ack(self) -> None:
        self.events.append("ack")

    async def reject(self, *, requeue: bool) -> None:
        self.events.append(f"reject:{requeue}")


class FakeExchange:
    def __init__(self, events: list[str]) -> None:
        self.events = events

    async def publish(self, message: object, routing_key: str, mandatory: bool) -> None:
        self.events.append(f"publish:{routing_key}")


@pytest.mark.asyncio
async def test_retry_is_durably_published_before_original_ack() -> None:
    events: list[str] = []

    await settle_delivery(
        FakeIncoming(events),  # type: ignore[arg-type]
        CommandOutcome.REQUEUE,
        FakeExchange(events),  # type: ignore[arg-type]
    )

    assert events == ["publish:arxiv.import.metadata", "ack"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("outcome", "expected"),
    [(CommandOutcome.ACK, ["ack"]), (CommandOutcome.DEAD, ["reject:False"])],
)
async def test_terminal_delivery_settlement(outcome: CommandOutcome, expected: list[str]) -> None:
    events: list[str] = []
    await settle_delivery(
        FakeIncoming(events),  # type: ignore[arg-type]
        outcome,
        FakeExchange(events),  # type: ignore[arg-type]
    )
    assert events == expected


@pytest.mark.asyncio
async def test_retry_exhaustion_dead_letters_instead_of_looping_forever() -> None:
    events: list[str] = []

    await settle_delivery(
        FakeIncoming(events, retry_count=5),  # type: ignore[arg-type]
        CommandOutcome.REQUEUE,
        FakeExchange(events),  # type: ignore[arg-type]
    )

    assert events == ["reject:False"]
