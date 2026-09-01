from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

import pytest

from app.jobs.arxiv_consumer import CommandOutcome
from app.messaging.contracts import MessageEnvelope, MessageType, ResultPayload
from app.messaging.kafka import KafkaResultPublisher, forward_retry, settle_delivery


@dataclass(slots=True)
class FakeRecord:
    topic: str = "camel.arxiv.jobs.v1"
    partition: int = 1
    offset: int = 42
    key: bytes = b"message-1"
    value: bytes = b"{}"
    headers: list[tuple[str, bytes]] | None = None


class FakeProducer:
    def __init__(self, events: list[str]) -> None:
        self.events = events
        self.calls: list[dict[str, Any]] = []

    async def send_and_wait(self, topic: str, **kwargs: Any) -> None:
        self.events.append(f"publish:{topic}")
        self.calls.append({"topic": topic, **kwargs})


class FakeConsumer:
    def __init__(self, events: list[str]) -> None:
        self.events = events
        self.offsets: list[dict[object, int]] = []

    async def commit(self, offsets: dict[object, int]) -> None:
        self.events.append("commit")
        self.offsets.append(offsets)


@pytest.mark.asyncio
async def test_retry_is_durably_published_before_source_offset_commit() -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    consumer = FakeConsumer(events)

    await settle_delivery(
        FakeRecord(headers=[]),  # type: ignore[arg-type]
        CommandOutcome.RETRY,
        producer,  # type: ignore[arg-type]
        consumer,  # type: ignore[arg-type]
        retry_topic="camel.arxiv.retry.v1",
        dead_letter_topic="camel.arxiv.dlt.v1",
        now_epoch_ms=1_000,
    )

    assert events == ["publish:camel.arxiv.retry.v1", "commit"]
    assert isinstance(producer.calls[0]["headers"], list)
    assert dict(producer.calls[0]["headers"])["camelRetryCount"] == b"1"
    assert dict(producer.calls[0]["headers"])["camelNotBeforeEpochMs"] == b"31000"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("outcome", "expected_topic"),
    [(CommandOutcome.ACK, None), (CommandOutcome.DEAD, "camel.arxiv.dlt.v1")],
)
async def test_terminal_delivery_settlement(
    outcome: CommandOutcome, expected_topic: str | None
) -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    consumer = FakeConsumer(events)

    await settle_delivery(
        FakeRecord(headers=[]),  # type: ignore[arg-type]
        outcome,
        producer,  # type: ignore[arg-type]
        consumer,  # type: ignore[arg-type]
        retry_topic="camel.arxiv.retry.v1",
        dead_letter_topic="camel.arxiv.dlt.v1",
        now_epoch_ms=1_000,
    )

    assert events == ([f"publish:{expected_topic}", "commit"] if expected_topic else ["commit"])


@pytest.mark.asyncio
async def test_retry_exhaustion_dead_letters_instead_of_looping_forever() -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    consumer = FakeConsumer(events)

    await settle_delivery(
        FakeRecord(headers=[("camelRetryCount", b"5")]),  # type: ignore[arg-type]
        CommandOutcome.RETRY,
        producer,  # type: ignore[arg-type]
        consumer,  # type: ignore[arg-type]
        retry_topic="camel.arxiv.retry.v1",
        dead_letter_topic="camel.arxiv.dlt.v1",
        now_epoch_ms=1_000,
    )

    assert events == ["publish:camel.arxiv.dlt.v1", "commit"]


@pytest.mark.asyncio
async def test_defer_preserves_exhausted_retry_count_without_dead_lettering() -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    consumer = FakeConsumer(events)
    exhausted: list[str] = []

    async def on_retry_exhausted() -> None:
        exhausted.append("called")

    await settle_delivery(
        FakeRecord(headers=[("camelRetryCount", b"5")]),  # type: ignore[arg-type]
        CommandOutcome.DEFER,
        producer,  # type: ignore[arg-type]
        consumer,  # type: ignore[arg-type]
        retry_topic="camel.arxiv.retry.v1",
        dead_letter_topic="camel.arxiv.dlt.v1",
        now_epoch_ms=1_000,
        on_retry_exhausted=on_retry_exhausted,
    )

    assert events == ["publish:camel.arxiv.retry.v1", "commit"]
    assert exhausted == []
    assert isinstance(producer.calls[0]["headers"], list)
    assert dict(producer.calls[0]["headers"])["camelRetryCount"] == b"5"


@pytest.mark.asyncio
async def test_nonfinal_retry_increments_to_five_without_terminal_callback() -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    consumer = FakeConsumer(events)
    exhausted: list[str] = []

    async def on_retry_exhausted() -> None:
        exhausted.append("called")

    await settle_delivery(
        FakeRecord(headers=[("camelRetryCount", b"4")]),  # type: ignore[arg-type]
        CommandOutcome.RETRY,
        producer,  # type: ignore[arg-type]
        consumer,  # type: ignore[arg-type]
        retry_topic="camel.arxiv.retry.v1",
        dead_letter_topic="camel.arxiv.dlt.v1",
        now_epoch_ms=1_000,
        on_retry_exhausted=on_retry_exhausted,
    )

    assert events == ["publish:camel.arxiv.retry.v1", "commit"]
    assert exhausted == []
    assert isinstance(producer.calls[0]["headers"], list)
    assert dict(producer.calls[0]["headers"])["camelRetryCount"] == b"5"


@pytest.mark.asyncio
async def test_retry_exhaustion_publishes_dlt_then_terminal_event_then_commits() -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    consumer = FakeConsumer(events)

    async def on_retry_exhausted() -> None:
        events.append("terminal")

    await settle_delivery(
        FakeRecord(headers=[("camelRetryCount", b"5")]),  # type: ignore[arg-type]
        CommandOutcome.RETRY,
        producer,  # type: ignore[arg-type]
        consumer,  # type: ignore[arg-type]
        retry_topic="camel.arxiv.retry.v1",
        dead_letter_topic="camel.arxiv.dlt.v1",
        now_epoch_ms=1_000,
        on_retry_exhausted=on_retry_exhausted,
    )

    assert events == ["publish:camel.arxiv.dlt.v1", "terminal", "commit"]


@pytest.mark.asyncio
async def test_retry_exhaustion_callback_failure_does_not_commit_source_offset() -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    consumer = FakeConsumer(events)

    async def on_retry_exhausted() -> None:
        events.append("terminal")
        raise RuntimeError("result broker unavailable")

    with pytest.raises(RuntimeError, match="result broker unavailable"):
        await settle_delivery(
            FakeRecord(headers=[("camelRetryCount", b"5")]),  # type: ignore[arg-type]
            CommandOutcome.RETRY,
            producer,  # type: ignore[arg-type]
            consumer,  # type: ignore[arg-type]
            retry_topic="camel.arxiv.retry.v1",
            dead_letter_topic="camel.arxiv.dlt.v1",
            now_epoch_ms=1_000,
            on_retry_exhausted=on_retry_exhausted,
        )

    assert events == ["publish:camel.arxiv.dlt.v1", "terminal"]


@pytest.mark.asyncio
async def test_result_publisher_uses_job_id_key_and_contract_header() -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    message = MessageEnvelope[ResultPayload](
        message_id=uuid4(),
        type=MessageType.ARXIV_JOB_STARTED,
        job_id=uuid4(),
        idempotency_key="result:test:1",
        trace_id="0123456789abcdef",
        occurred_at=datetime(2026, 8, 11, tzinfo=UTC),
        payload=ResultPayload(status="RUNNING", stage="FETCHING"),
    )

    await KafkaResultPublisher(
        producer, "camel.arxiv.results.v1"  # type: ignore[arg-type]
    ).publish(message)

    call = producer.calls[0]
    assert call["key"] == str(message.job_id).encode()
    assert isinstance(call["headers"], list)
    assert dict(call["headers"])["contractVersion"] == b"1"


@pytest.mark.asyncio
async def test_due_retry_is_forwarded_to_original_topic_before_commit() -> None:
    events: list[str] = []
    producer = FakeProducer(events)
    consumer = FakeConsumer(events)
    sleeps: list[float] = []

    async def sleep(seconds: float) -> None:
        sleeps.append(seconds)

    await forward_retry(
        FakeRecord(
            topic="camel.arxiv.retry.v1",
            headers=[
                ("camelOriginalTopic", b"camel.arxiv.jobs.v1"),
                ("camelNotBeforeEpochMs", b"31000"),
                ("camelRetryCount", b"1"),
            ],
        ),  # type: ignore[arg-type]
        producer,  # type: ignore[arg-type]
        consumer,  # type: ignore[arg-type]
        default_topic="camel.arxiv.jobs.v1",
        now_epoch_ms=1_000,
        sleep=sleep,
    )

    assert sleeps == [30.0]
    assert events == ["publish:camel.arxiv.jobs.v1", "commit"]
    assert isinstance(producer.calls[0]["headers"], list)
    forwarded_headers = dict(producer.calls[0]["headers"])
    assert "camelNotBeforeEpochMs" not in forwarded_headers
    assert forwarded_headers["camelRetryCount"] == b"1"
