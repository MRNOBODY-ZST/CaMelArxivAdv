from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, date, datetime
from uuid import UUID, uuid4

import pytest

from app.arxiv.models import ArxivAuthor, ArxivMetadata, OaiRecordPage
from app.jobs.arxiv_consumer import ArxivCommandProcessor, CommandOutcome
from app.messaging.contracts import MessageEnvelope, MessageType, ResultPayload


class FakeLegacyClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, ...]] = []

    async def fetch_ids(self, arxiv_ids: tuple[str, ...]) -> tuple[ArxivMetadata, ...]:
        self.calls.append(arxiv_ids)
        return (metadata(arxiv_ids[0]),)

    async def search_page(
        self,
        search_query: str,
        start: int,
        max_results: int,
        sort_by: str,
        sort_order: str,
    ) -> tuple[ArxivMetadata, ...]:
        return ()


class FakeOaiClient:
    async def iter_record_pages(
        self, set_spec: str, from_date: date | None = None
    ) -> AsyncIterator[OaiRecordPage]:
        if False:
            yield OaiRecordPage(datetime.now(UTC), (), None)


class FakePublisher:
    def __init__(self) -> None:
        self.messages: list[MessageEnvelope[ResultPayload]] = []

    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None:
        self.messages.append(message)


class FakeStore:
    def __init__(self, duplicate: bool = False, control: str = "RUN") -> None:
        self.duplicate = duplicate
        self.control = control
        self.marked: list[str] = []

    async def is_processed(self, key: str) -> bool:
        return self.duplicate

    async def mark_processed(self, key: str) -> None:
        self.marked.append(key)

    async def control_for(self, job_id: UUID) -> str:
        return self.control


@pytest.mark.asyncio
async def test_selected_import_publishes_durable_progress_before_ack_outcome() -> None:
    legacy = FakeLegacyClient()
    publisher = FakePublisher()
    store = FakeStore()
    processor = ArxivCommandProcessor(legacy, FakeOaiClient(), publisher, store, batch_size=50)

    outcome = await processor.process(command_body())

    assert outcome is CommandOutcome.ACK
    assert legacy.calls == [("2608.00001",)]
    assert [message.type for message in publisher.messages] == [
        MessageType.ARXIV_JOB_STARTED,
        MessageType.ARXIV_JOB_BATCH,
        MessageType.ARXIV_JOB_PROGRESS,
        MessageType.ARXIV_JOB_COMPLETED,
    ]
    assert store.marked == ["import:test"]
    assert publisher.messages[1].payload.papers[0]["arxivId"] == "2608.00001"


@pytest.mark.asyncio
async def test_duplicate_is_acked_without_external_calls() -> None:
    legacy = FakeLegacyClient()
    processor = ArxivCommandProcessor(
        legacy, FakeOaiClient(), FakePublisher(), FakeStore(duplicate=True), batch_size=50
    )

    assert await processor.process(command_body()) is CommandOutcome.ACK
    assert legacy.calls == []


@pytest.mark.asyncio
async def test_pause_requeues_before_the_next_external_call() -> None:
    legacy = FakeLegacyClient()
    publisher = FakePublisher()
    processor = ArxivCommandProcessor(
        legacy, FakeOaiClient(), publisher, FakeStore(control="PAUSE"), batch_size=50
    )

    assert await processor.process(command_body()) is CommandOutcome.REQUEUE
    assert legacy.calls == []
    assert publisher.messages[-1].payload.status == "PAUSED"


@pytest.mark.asyncio
async def test_malformed_or_unsupported_commands_are_dead_lettered() -> None:
    processor = ArxivCommandProcessor(
        FakeLegacyClient(), FakeOaiClient(), FakePublisher(), FakeStore(), batch_size=50
    )

    assert await processor.process(b"not-json") is CommandOutcome.DEAD


def command_body() -> bytes:
    return (
        MessageEnvelope[dict[str, object]](
            message_id=uuid4(),
            type=MessageType.ARXIV_IMPORT_METADATA,
            job_id=uuid4(),
            idempotency_key="import:test",
            trace_id="0123456789abcdef",
            payload={"mode": "SELECTED", "arxivIds": ["2608.00001"], "maxPapers": 1},
        )
        .model_dump_json(by_alias=True)
        .encode()
    )


def metadata(arxiv_id: str) -> ArxivMetadata:
    now = datetime(2026, 8, 5, tzinfo=UTC)
    return ArxivMetadata(
        arxiv_id=arxiv_id,
        version=1,
        title="Reliable Agents",
        abstract="Summary",
        authors=(ArxivAuthor("Ada Lovelace", ("Institute",)),),
        primary_category="cs.AI",
        categories=("cs.AI",),
        published_at=now,
        updated_at=now,
        pdf_url=f"https://arxiv.org/pdf/{arxiv_id}v1",
    )
