from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, date, datetime
from uuid import UUID, uuid4

import pytest

from app.arxiv.models import ArxivAuthor, ArxivMetadata, OaiRecordPage
from app.arxiv.oai_client import OaiProtocolError
from app.arxiv.taxonomy import TaxonomyCategory
from app.jobs.arxiv_consumer import ArxivCommandProcessor, CommandOutcome
from app.jobs.job_control import SourceProgress
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
    async def fetch_taxonomy(self) -> tuple[TaxonomyCategory, ...]:
        return (
            TaxonomyCategory(
                set_spec="cs:cs:AI",
                group_id="cs",
                archive_id="cs",
                category_id="cs.AI",
                category_name="Artificial Intelligence",
            ),
        )

    async def iter_record_pages(
        self,
        set_spec: str,
        from_date: date | None = None,
        *,
        resumption_token: str | None = None,
    ) -> AsyncIterator[OaiRecordPage]:
        if False:
            yield OaiRecordPage(datetime.now(UTC), (), None)


class PagingOaiClient(FakeOaiClient):
    def __init__(self, token: str | None = "next-token") -> None:
        self.token = token
        self.resumed_from: str | None = None

    async def iter_record_pages(
        self,
        set_spec: str,
        from_date: date | None = None,
        *,
        resumption_token: str | None = None,
    ) -> AsyncIterator[OaiRecordPage]:
        self.resumed_from = resumption_token
        yield OaiRecordPage(datetime(2026, 8, 5, tzinfo=UTC), (), self.token)


class InvalidTaxonomyClient(FakeOaiClient):
    async def fetch_taxonomy(self) -> tuple[TaxonomyCategory, ...]:
        raise OaiProtocolError("malformed ListSets")


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
        self.cursor: str | None = None
        self.source_progress: SourceProgress | None = None

    async def is_processed(self, key: str) -> bool:
        return self.duplicate

    async def mark_processed(self, key: str) -> None:
        self.marked.append(key)

    async def control_for(self, job_id: UUID) -> str:
        return self.control

    async def cursor_for(self, idempotency_key: str) -> str | None:
        return self.cursor

    async def save_cursor(self, idempotency_key: str, token: str) -> None:
        self.cursor = token

    async def clear_cursor(self, idempotency_key: str) -> None:
        self.cursor = None

    async def source_progress_for(self, idempotency_key: str) -> SourceProgress | None:
        return self.source_progress

    async def save_source_progress(
        self, idempotency_key: str, progress: SourceProgress
    ) -> None:
        self.source_progress = progress

    async def clear_source_progress(self, idempotency_key: str) -> None:
        self.source_progress = None


class SequenceControlStore(FakeStore):
    def __init__(self, controls: list[str]) -> None:
        super().__init__()
        self.controls = controls

    async def control_for(self, job_id: UUID) -> str:
        return self.controls.pop(0) if self.controls else "RUN"


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

    assert await processor.process(command_body()) is CommandOutcome.DEFER
    assert legacy.calls == []
    assert publisher.messages[-1].payload.status == "PAUSED"


@pytest.mark.asyncio
async def test_malformed_or_unsupported_commands_are_dead_lettered() -> None:
    processor = ArxivCommandProcessor(
        FakeLegacyClient(), FakeOaiClient(), FakePublisher(), FakeStore(), batch_size=50
    )

    assert await processor.process(b"not-json") is CommandOutcome.DEAD


@pytest.mark.asyncio
async def test_taxonomy_sync_publishes_snapshot_in_the_terminal_result() -> None:
    publisher = FakePublisher()
    store = FakeStore()
    processor = ArxivCommandProcessor(
        FakeLegacyClient(), FakeOaiClient(), publisher, store, batch_size=50
    )
    body = (
        MessageEnvelope[dict[str, object]](
            message_id=uuid4(),
            type=MessageType.ARXIV_SYNC_TAXONOMY,
            job_id=uuid4(),
            idempotency_key="taxonomy:2026-08-05",
            trace_id="0123456789abcdef",
            payload={"requestedDate": "2026-08-05"},
        )
        .model_dump_json(by_alias=True)
        .encode()
    )

    assert await processor.process(body) is CommandOutcome.ACK
    assert [message.type for message in publisher.messages] == [
        MessageType.ARXIV_JOB_STARTED,
        MessageType.ARXIV_JOB_COMPLETED,
    ]
    snapshot = publisher.messages[1].payload
    assert snapshot.taxonomy_categories[0]["categoryId"] == "cs.AI"
    assert snapshot.taxonomy_source_updated_at is not None
    assert snapshot.status == "SUCCEEDED"
    assert store.marked == ["taxonomy:2026-08-05"]


@pytest.mark.asyncio
async def test_malformed_list_sets_is_settled_as_a_retryable_outcome() -> None:
    publisher = FakePublisher()
    processor = ArxivCommandProcessor(
        FakeLegacyClient(), InvalidTaxonomyClient(), publisher, FakeStore(), batch_size=50
    )

    assert await processor.process(taxonomy_command_body()) is CommandOutcome.RETRY
    assert publisher.messages[-1].payload.stage == "RETRYING_UPSTREAM"


@pytest.mark.asyncio
async def test_oai_pause_persists_and_reuses_the_opaque_cursor() -> None:
    oai = PagingOaiClient()
    store = SequenceControlStore(["RUN", "RUN", "PAUSE"])
    store.cursor = "resume-token"
    processor = ArxivCommandProcessor(
        FakeLegacyClient(), oai, FakePublisher(), store, batch_size=50
    )

    assert await processor.process(oai_command_body()) is CommandOutcome.DEFER
    assert oai.resumed_from == "resume-token"
    assert store.cursor == "next-token"


@pytest.mark.asyncio
async def test_mid_loop_cancel_is_acked_without_fetching_or_retrying() -> None:
    legacy = FakeLegacyClient()
    publisher = FakePublisher()
    store = SequenceControlStore(["RUN", "CANCEL"])
    processor = ArxivCommandProcessor(
        legacy, FakeOaiClient(), publisher, store, batch_size=50
    )

    assert await processor.process(command_body()) is CommandOutcome.ACK
    assert legacy.calls == []
    assert store.marked == ["import:test"]
    assert publisher.messages[-1].type is MessageType.ARXIV_JOB_COMPLETED
    assert publisher.messages[-1].payload.status == "CANCELED"


@pytest.mark.asyncio
async def test_retry_exhaustion_failure_is_deterministic_and_bounded() -> None:
    publisher = FakePublisher()
    processor = ArxivCommandProcessor(
        FakeLegacyClient(), FakeOaiClient(), publisher, FakeStore(), batch_size=50
    )
    body = command_body()

    await processor.publish_retry_exhausted_failure(body)
    await processor.publish_retry_exhausted_failure(body)

    first, second = publisher.messages
    assert first.type is MessageType.ARXIV_JOB_FAILED
    assert first.idempotency_key == second.idempotency_key
    assert first.payload.status == "FAILED"
    assert first.payload.stage == "FAILED"
    assert first.payload.error_code == "WORKER_RETRY_EXHAUSTED"
    assert (
        first.payload.error_summary
        == "Worker exhausted retries after an unexpected processing failure"
    )
    assert "2510.13029" not in first.model_dump_json(by_alias=True)


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


def taxonomy_command_body() -> bytes:
    return (
        MessageEnvelope[dict[str, object]](
            message_id=uuid4(),
            type=MessageType.ARXIV_SYNC_TAXONOMY,
            job_id=uuid4(),
            idempotency_key="taxonomy:2026-08-05",
            trace_id="0123456789abcdef",
            payload={"requestedDate": "2026-08-05"},
        )
        .model_dump_json(by_alias=True)
        .encode()
    )


def oai_command_body() -> bytes:
    return (
        MessageEnvelope[dict[str, object]](
            message_id=uuid4(),
            type=MessageType.ARXIV_SYNC_OAI,
            job_id=uuid4(),
            idempotency_key="oai:test",
            trace_id="0123456789abcdef",
            payload={"setSpec": "cs:cs:AI", "from": "2026-08-01"},
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
