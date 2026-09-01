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
    def __init__(self) -> None:
        self.messages: list[MessageEnvelope[ResultPayload]] = []

    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None:
        self.messages.append(message)


class Store:
    def __init__(self, source_progress: Checkpoint | None = None) -> None:
        self.marked: list[str] = []
        self.source_progress = source_progress
        self.saved_source_progress: list[object] = []
        self.cleared_source_progress: list[str] = []

    async def is_processed(self, key: str) -> bool:
        return False

    async def mark_processed(self, key: str) -> None:
        self.marked.append(key)

    async def control_for(self, job_id: UUID) -> str:
        return "RUN"

    async def cursor_for(self, idempotency_key: str) -> str | None:
        return None

    async def save_cursor(self, idempotency_key: str, token: str) -> None:
        pass

    async def clear_cursor(self, idempotency_key: str) -> None:
        pass

    async def source_progress_for(self, idempotency_key: str) -> Checkpoint | None:
        return self.source_progress

    async def save_source_progress(
        self, idempotency_key: str, progress: object
    ) -> None:
        self.saved_source_progress.append(progress)
        self.source_progress = progress  # type: ignore[assignment]

    async def clear_source_progress(self, idempotency_key: str) -> None:
        self.cleared_source_progress.append(idempotency_key)
        self.source_progress = None


@dataclass(frozen=True)
class Checkpoint:
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
async def test_source_command_resumes_after_the_last_durably_published_item() -> None:
    publisher = Publisher()
    store = Store(Checkpoint(next_index=1, success=1, skipped=0, failed=0))
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
