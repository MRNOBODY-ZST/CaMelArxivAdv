from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import date
from typing import Literal, cast
from uuid import UUID, uuid4

import pytest

from app.arxiv.models import ArxivMetadata, OaiRecordPage
from app.arxiv.taxonomy import TaxonomyCategory
from app.jobs.arxiv_consumer import ArxivCommandProcessor, CommandOutcome
from app.jobs.job_control import SourceProgress
from app.messaging.contracts import (
    MessageEnvelope,
    MessageType,
    ResultPayload,
    SourceExtractionResult,
    SourceTarget,
)


class UnusedLegacy:
    async def fetch_ids(self, arxiv_ids: tuple[str, ...]) -> tuple[ArxivMetadata, ...]:
        raise AssertionError("metadata client should not be used")

    async def search_page(
        self,
        search_query: str,
        start: int,
        max_results: int,
        sort_by: str,
        sort_order: str,
    ) -> tuple[ArxivMetadata, ...]:
        raise AssertionError("metadata client should not be used")


class UnusedOai:
    async def fetch_taxonomy(self) -> tuple[TaxonomyCategory, ...]:
        raise AssertionError("OAI client should not be used")

    async def iter_record_pages(
        self,
        set_spec: str,
        from_date: date | None = None,
        *,
        resumption_token: str | None = None,
    ) -> AsyncIterator[OaiRecordPage]:
        if False:
            yield cast(OaiRecordPage, None)


class Publisher:
    def __init__(self, events: list[str] | None = None) -> None:
        self.messages: list[MessageEnvelope[ResultPayload]] = []
        self.events = events

    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None:
        self.messages.append(message)
        if self.events is not None:
            self.events.append(f"publish:{message.type.value}")


class Store:
    def __init__(
        self,
        source_progress: SourceProgress | None = None,
        events: list[str] | None = None,
    ) -> None:
        self.marked: list[str] = []
        self.source_progress = source_progress
        self.saved_source_progress: list[SourceProgress] = []
        self.cleared_source_progress: list[str] = []
        self.events = events

    async def is_processed(self, key: str) -> bool:
        return key in self.marked

    async def mark_processed(self, key: str) -> None:
        self.marked.append(key)
        if self.events is not None:
            self.events.append("mark-processed")

    async def control_for(self, job_id: UUID) -> str:
        return "RUN"

    async def cursor_for(self, idempotency_key: str) -> str | None:
        return None

    async def save_cursor(self, idempotency_key: str, token: str) -> None:
        pass

    async def clear_cursor(self, idempotency_key: str) -> None:
        pass

    async def source_progress_for(self, idempotency_key: str) -> SourceProgress | None:
        return self.source_progress

    async def save_source_progress(
        self, idempotency_key: str, progress: SourceProgress
    ) -> bool:
        self.saved_source_progress.append(progress)
        self.source_progress = progress
        if self.events is not None:
            self.events.append("save-checkpoint")
        return True

    async def clear_source_progress(self, idempotency_key: str) -> None:
        self.cleared_source_progress.append(idempotency_key)
        self.source_progress = None
        if self.events is not None:
            self.events.append("clear-checkpoint")


@dataclass(frozen=True)
class UnsafeCheckpoint:
    next_index: int
    success: int
    skipped: int
    failed: int


class Runner:
    def __init__(
        self,
        statuses: tuple[
            Literal[
                "SUCCEEDED",
                "PARTIALLY_SUCCEEDED",
                "FAILED",
                "SECURITY_REJECTED",
                "SOURCE_UNAVAILABLE",
            ],
            ...,
        ] = ("SUCCEEDED",),
    ) -> None:
        self.statuses = list(statuses)
        self.targets: list[SourceTarget] = []

    async def run(self, target: SourceTarget) -> SourceExtractionResult:
        self.targets.append(target)
        status = self.statuses.pop(0)
        return SourceExtractionResult(
            paper_id=target.paper_id,
            arxiv_id=target.arxiv_id,
            parser_version="phase4-test",
            status=status,
            cleanup_confirmed=True,
            source_format="TEX" if status == "SUCCEEDED" else None,
            files_inspected=1 if status == "SUCCEEDED" else 0,
            error_code=(
                None
                if status == "SUCCEEDED"
                else "SOURCE_UNAVAILABLE"
                if status == "SOURCE_UNAVAILABLE"
                else "SOURCE_CONTENT_INVALID"
                if status == "FAILED"
                else "SOURCE_SECURITY_REJECTED"
            ),
        )


def command(targets: list[dict[str, str]], key: str = "source:test") -> bytes:
    return (
        MessageEnvelope[dict[str, object]](
            message_id=uuid4(),
            type=MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
            job_id=uuid4(),
            idempotency_key=key,
            trace_id="0123456789abcdef",
            payload={"targets": targets, "parserVersion": "phase4-test"},
        )
        .model_dump_json(by_alias=True)
        .encode()
    )


@pytest.mark.asyncio
async def test_source_command_publishes_each_result_progress_and_terminal_status() -> None:
    publisher = Publisher()
    store = Store()
    runner = Runner(("SUCCEEDED", "SOURCE_UNAVAILABLE"))
    processor = ArxivCommandProcessor(
        UnusedLegacy(), UnusedOai(), publisher, store, batch_size=50, source_runner=runner
    )
    first, second = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001"), SourceTarget(
        paper_id=uuid4(), arxiv_id="2608.00002"
    )

    outcome = await processor.process(
        command(
            [
                {"paperId": str(first.paper_id), "arxivId": first.arxiv_id},
                {"paperId": str(second.paper_id), "arxivId": second.arxiv_id},
            ]
        )
    )

    assert outcome is CommandOutcome.ACK
    assert runner.targets == [first, second]
    assert [message.type for message in publisher.messages] == [
        MessageType.ARXIV_JOB_STARTED,
        MessageType.ARXIV_EXTRACTION_RESULT,
        MessageType.ARXIV_JOB_PROGRESS,
        MessageType.ARXIV_EXTRACTION_RESULT,
        MessageType.ARXIV_JOB_PROGRESS,
        MessageType.ARXIV_JOB_COMPLETED,
    ]
    terminal = publisher.messages[-1].payload
    assert terminal.status == "SUCCEEDED"
    assert terminal.success_count == 1
    assert terminal.skipped_count == 1
    assert store.marked == ["source:test"]


@pytest.mark.asyncio
async def test_mixed_success_and_security_failure_finishes_partially_succeeded() -> None:
    publisher = Publisher()
    processor = ArxivCommandProcessor(
        UnusedLegacy(),
        UnusedOai(),
        publisher,
        Store(),
        batch_size=50,
        source_runner=Runner(("SUCCEEDED", "SECURITY_REJECTED")),
    )
    targets = [
        {"paperId": str(uuid4()), "arxivId": "2608.00001"},
        {"paperId": str(uuid4()), "arxivId": "2608.00002"},
    ]

    assert await processor.process(command(targets, "source:partial")) is CommandOutcome.ACK
    assert publisher.messages[-1].payload.status == "PARTIALLY_SUCCEEDED"
    assert publisher.messages[-1].payload.failed_count == 1


@pytest.mark.asyncio
async def test_content_failure_does_not_prevent_the_next_target_from_succeeding() -> None:
    publisher = Publisher()
    store = Store()
    runner = Runner(("FAILED", "SUCCEEDED"))
    processor = ArxivCommandProcessor(
        UnusedLegacy(), UnusedOai(), publisher, store, batch_size=50, source_runner=runner
    )
    first = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001")
    second = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00002")

    outcome = await processor.process(
        command(
            [
                {"paperId": str(first.paper_id), "arxivId": first.arxiv_id},
                {"paperId": str(second.paper_id), "arxivId": second.arxiv_id},
            ],
            "source:content-failure",
        )
    )

    assert outcome is CommandOutcome.ACK
    assert runner.targets == [first, second]
    extractions = [
        message.payload.extractions[0]
        for message in publisher.messages
        if message.type is MessageType.ARXIV_EXTRACTION_RESULT
    ]
    assert [item.status for item in extractions] == ["FAILED", "SUCCEEDED"]
    assert extractions[0].error_code == "SOURCE_CONTENT_INVALID"
    terminal = publisher.messages[-1].payload
    assert terminal.status == "PARTIALLY_SUCCEEDED"
    assert (terminal.success_count, terminal.failed_count) == (1, 1)
    assert store.marked == ["source:content-failure"]


@pytest.mark.asyncio
async def test_source_command_resumes_after_the_last_durably_published_item() -> None:
    publisher = Publisher()
    store = Store(SourceProgress(next_index=1, success=1, skipped=0, failed=0))
    runner = Runner(("SUCCEEDED", "SUCCEEDED"))
    processor = ArxivCommandProcessor(
        UnusedLegacy(), UnusedOai(), publisher, store, batch_size=50, source_runner=runner
    )
    first = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001")
    second = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00002")

    outcome = await processor.process(
        command(
            [
                {"paperId": str(first.paper_id), "arxivId": first.arxiv_id},
                {"paperId": str(second.paper_id), "arxivId": second.arxiv_id},
            ],
            "source:resume",
        )
    )

    assert outcome is CommandOutcome.ACK
    assert runner.targets == [second]
    assert publisher.messages[-1].payload.success_count == 2
    assert publisher.messages[-1].payload.processed_count == 2
    assert [
        (item.next_index, item.success, item.skipped, item.failed)
        for item in store.saved_source_progress
    ] == [(2, 2, 0, 0)]
    assert store.cleared_source_progress == ["source:resume"]


@pytest.mark.asyncio
async def test_checkpoint_is_saved_after_both_item_messages_and_before_terminal_marker() -> None:
    events: list[str] = []
    publisher = Publisher(events)
    store = Store(events=events)
    processor = ArxivCommandProcessor(
        UnusedLegacy(),
        UnusedOai(),
        publisher,
        store,
        batch_size=50,
        source_runner=Runner(),
    )

    await processor.process(
        command(
            [{"paperId": str(uuid4()), "arxivId": "2608.00001"}],
            "source:ordering",
        )
    )

    assert events == [
        "publish:ARXIV_JOB_STARTED",
        "publish:ARXIV_EXTRACTION_RESULT",
        "publish:ARXIV_JOB_PROGRESS",
        "save-checkpoint",
        "publish:ARXIV_JOB_COMPLETED",
        "mark-processed",
        "clear-checkpoint",
    ]


class FailingPublisher(Publisher):
    def __init__(self, fail_type: MessageType) -> None:
        super().__init__()
        self.fail_type = fail_type

    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None:
        if message.type is self.fail_type:
            raise RuntimeError(f"failed to publish {message.type.value}")
        await super().publish(message)


class SupersededCheckpointStore(Store):
    async def save_source_progress(
        self, idempotency_key: str, progress: SourceProgress
    ) -> bool:
        self.saved_source_progress.append(progress)
        return False


@pytest.mark.asyncio
async def test_superseded_source_processor_defers_without_terminal_or_global_marker() -> None:
    publisher = Publisher()
    store = SupersededCheckpointStore()
    runner = Runner(("SUCCEEDED", "SUCCEEDED"))
    processor = ArxivCommandProcessor(
        UnusedLegacy(), UnusedOai(), publisher, store, batch_size=50, source_runner=runner
    )

    outcome = await processor.process(
        command(
            [
                {"paperId": str(uuid4()), "arxivId": "2608.00001"},
                {"paperId": str(uuid4()), "arxivId": "2608.00002"},
            ],
            "source:superseded",
        )
    )

    assert outcome is CommandOutcome.DEFER
    assert len(runner.targets) == 1
    assert store.marked == []
    assert MessageType.ARXIV_JOB_COMPLETED not in {
        message.type for message in publisher.messages
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "fail_type",
    [MessageType.ARXIV_EXTRACTION_RESULT, MessageType.ARXIV_JOB_PROGRESS],
)
async def test_item_publish_failure_does_not_advance_checkpoint(
    fail_type: MessageType,
) -> None:
    first = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001")
    second = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00002")
    initial = SourceProgress(next_index=1, success=1, skipped=0, failed=0)
    store = Store(initial)
    processor = ArxivCommandProcessor(
        UnusedLegacy(),
        UnusedOai(),
        FailingPublisher(fail_type),
        store,
        batch_size=50,
        source_runner=Runner(),
    )

    with pytest.raises(RuntimeError, match="failed to publish"):
        await processor.process(
            command(
                [
                    {"paperId": str(first.paper_id), "arxivId": first.arxiv_id},
                    {"paperId": str(second.paper_id), "arxivId": second.arxiv_id},
                ],
                "source:publish-failure",
            )
        )

    assert store.source_progress is initial
    assert store.saved_source_progress == []


@pytest.mark.asyncio
async def test_terminal_publish_failure_retains_final_checkpoint() -> None:
    store = Store()
    processor = ArxivCommandProcessor(
        UnusedLegacy(),
        UnusedOai(),
        FailingPublisher(MessageType.ARXIV_JOB_COMPLETED),
        store,
        batch_size=50,
        source_runner=Runner(),
    )

    with pytest.raises(RuntimeError, match="ARXIV_JOB_COMPLETED"):
        await processor.process(
            command(
                [{"paperId": str(uuid4()), "arxivId": "2608.00001"}],
                "source:terminal-failure",
            )
        )

    assert store.source_progress is not None
    assert store.source_progress.next_index == 1
    assert store.marked == []


class MarkFailingStore(Store):
    async def mark_processed(self, key: str) -> None:
        raise RuntimeError("processed marker unavailable")


@pytest.mark.asyncio
async def test_processed_marker_failure_retains_final_checkpoint() -> None:
    store = MarkFailingStore()
    processor = ArxivCommandProcessor(
        UnusedLegacy(),
        UnusedOai(),
        Publisher(),
        store,
        batch_size=50,
        source_runner=Runner(),
    )

    with pytest.raises(RuntimeError, match="processed marker unavailable"):
        await processor.process(
            command(
                [{"paperId": str(uuid4()), "arxivId": "2608.00001"}],
                "source:marker-failure",
            )
        )

    assert store.source_progress is not None
    assert store.source_progress.next_index == 1


class ClearOnceFailingStore(Store):
    def __init__(self) -> None:
        super().__init__()
        self.fail_clear = True

    async def clear_source_progress(self, idempotency_key: str) -> None:
        if self.fail_clear:
            self.fail_clear = False
            raise RuntimeError("checkpoint delete unavailable")
        await super().clear_source_progress(idempotency_key)


@pytest.mark.asyncio
async def test_checkpoint_clear_failure_after_marker_does_not_replay_items() -> None:
    store = ClearOnceFailingStore()
    runner = Runner()
    processor = ArxivCommandProcessor(
        UnusedLegacy(), UnusedOai(), Publisher(), store, batch_size=50, source_runner=runner
    )
    body = command(
        [{"paperId": str(uuid4()), "arxivId": "2608.00001"}],
        "source:clear-failure",
    )

    with pytest.raises(RuntimeError, match="checkpoint delete unavailable"):
        await processor.process(body)

    assert await processor.process(body) is CommandOutcome.ACK
    assert len(runner.targets) == 1
    assert store.source_progress is not None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "checkpoint",
    [
        cast(
            SourceProgress,
            UnsafeCheckpoint(next_index=1, success=0, skipped=0, failed=0),
        ),
        cast(
            SourceProgress,
            UnsafeCheckpoint(next_index=3, success=3, skipped=0, failed=0),
        ),
    ],
)
async def test_inconsistent_or_out_of_range_checkpoint_restarts_from_first_target(
    checkpoint: SourceProgress,
) -> None:
    store = Store(checkpoint)
    runner = Runner(("SUCCEEDED", "SUCCEEDED"))
    processor = ArxivCommandProcessor(
        UnusedLegacy(), UnusedOai(), Publisher(), store, batch_size=50, source_runner=runner
    )
    first = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00001")
    second = SourceTarget(paper_id=uuid4(), arxiv_id="2608.00002")

    assert (
        await processor.process(
            command(
                [
                    {"paperId": str(first.paper_id), "arxivId": first.arxiv_id},
                    {"paperId": str(second.paper_id), "arxivId": second.arxiv_id},
                ],
                "source:invalid-checkpoint",
            )
        )
        is CommandOutcome.ACK
    )
    assert runner.targets == [first, second]


@pytest.mark.asyncio
async def test_target_53_resume_uses_absolute_sequences_and_cumulative_counts() -> None:
    targets = [
        SourceTarget(paper_id=uuid4(), arxiv_id=f"2608.{index:05d}")
        for index in range(1, 54)
    ]
    publisher = Publisher()
    store = Store(SourceProgress(next_index=52, success=52, skipped=0, failed=0))
    runner = Runner()
    processor = ArxivCommandProcessor(
        UnusedLegacy(), UnusedOai(), publisher, store, batch_size=50, source_runner=runner
    )

    assert (
        await processor.process(
            command(
                [
                    {"paperId": str(target.paper_id), "arxivId": target.arxiv_id}
                    for target in targets
                ],
                "source:resume-52",
            )
        )
        is CommandOutcome.ACK
    )

    assert runner.targets == [targets[52]]
    item = next(
        message
        for message in publisher.messages
        if message.type is MessageType.ARXIV_EXTRACTION_RESULT
    )
    progress = next(
        message
        for message in publisher.messages
        if message.type is MessageType.ARXIV_JOB_PROGRESS
    )
    assert item.idempotency_key.endswith(":ARXIV_EXTRACTION_RESULT:105")
    assert progress.idempotency_key.endswith(":ARXIV_JOB_PROGRESS:106")
    assert (progress.payload.processed_count, progress.payload.success_count) == (53, 53)
