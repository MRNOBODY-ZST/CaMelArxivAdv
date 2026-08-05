from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import date, datetime
from enum import StrEnum
from typing import Protocol
from uuid import UUID, uuid4

import httpx
from pydantic import ValidationError

from app.arxiv.models import ArxivMetadata, OaiRecordPage
from app.arxiv.oai_client import OaiProtocolError
from app.messaging.contracts import (
    ImportMetadataCommand,
    MessageEnvelope,
    MessageType,
    OaiSyncCommand,
    ResultPayload,
)


class LegacyClient(Protocol):
    async def fetch_ids(self, arxiv_ids: tuple[str, ...]) -> tuple[ArxivMetadata, ...]: ...

    async def search_page(
        self,
        search_query: str,
        start: int,
        max_results: int,
        sort_by: str,
        sort_order: str,
    ) -> tuple[ArxivMetadata, ...]: ...


class OaiMetadataClient(Protocol):
    def iter_record_pages(
        self, set_spec: str, from_date: date | None = None
    ) -> AsyncIterator[OaiRecordPage]: ...


class ResultPublisher(Protocol):
    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None: ...


class JobStore(Protocol):
    async def is_processed(self, key: str) -> bool: ...

    async def mark_processed(self, key: str) -> None: ...

    async def control_for(self, job_id: UUID) -> str: ...


class CommandOutcome(StrEnum):
    ACK = "ACK"
    REQUEUE = "REQUEUE"
    DEAD = "DEAD"


class ArxivCommandProcessor:
    def __init__(
        self,
        legacy_client: LegacyClient,
        oai_client: OaiMetadataClient,
        publisher: ResultPublisher,
        store: JobStore,
        *,
        batch_size: int,
        maximum_command_bytes: int = 2 * 1024 * 1024,
    ) -> None:
        if not 1 <= batch_size <= 100:
            raise ValueError("metadata batch size must be between one and 100")
        self._legacy = legacy_client
        self._oai = oai_client
        self._publisher = publisher
        self._store = store
        self._batch_size = batch_size
        self._maximum_command_bytes = maximum_command_bytes

    async def process(self, body: bytes) -> CommandOutcome:
        if not body or len(body) > self._maximum_command_bytes:
            return CommandOutcome.DEAD
        try:
            envelope = MessageEnvelope[dict[str, object]].model_validate_json(body)
            if envelope.job_id is None:
                return CommandOutcome.DEAD
            if envelope.type == MessageType.ARXIV_IMPORT_METADATA:
                command: ImportMetadataCommand | OaiSyncCommand = (
                    ImportMetadataCommand.model_validate(envelope.payload)
                )
            elif envelope.type == MessageType.ARXIV_SYNC_OAI:
                command = OaiSyncCommand.model_validate(envelope.payload)
            else:
                return CommandOutcome.DEAD
        except (ValidationError, UnicodeDecodeError, ValueError):
            return CommandOutcome.DEAD
        if await self._store.is_processed(envelope.idempotency_key):
            return CommandOutcome.ACK
        control = await self._store.control_for(envelope.job_id)
        if control == "PAUSE":
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_PROGRESS,
                ResultPayload(status="PAUSED", stage="PAUSED_BY_USER"),
                0,
            )
            return CommandOutcome.REQUEUE
        if control == "CANCEL":
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_COMPLETED,
                ResultPayload(status="CANCELED", stage="CANCELED_BY_USER"),
                0,
            )
            await self._store.mark_processed(envelope.idempotency_key)
            return CommandOutcome.ACK
        try:
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_STARTED,
                ResultPayload(status="RUNNING", stage="FETCHING_METADATA"),
                0,
            )
            if isinstance(command, ImportMetadataCommand):
                processed = await self._process_import(envelope, command)
            else:
                processed = await self._process_oai(envelope, command)
            if processed is None:
                return CommandOutcome.REQUEUE
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_COMPLETED,
                ResultPayload(
                    status="SUCCEEDED",
                    stage="COMPLETED",
                    processed_count=processed,
                    success_count=processed,
                    total_count=processed,
                    progress_percent=100,
                ),
                processed + 2,
            )
            await self._store.mark_processed(envelope.idempotency_key)
            return CommandOutcome.ACK
        except (httpx.HTTPError, OaiProtocolError, TimeoutError, ConnectionError):
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_PROGRESS,
                ResultPayload(
                    status="RUNNING",
                    stage="RETRYING_UPSTREAM",
                    error_code="ARXIV_RETRYABLE",
                    error_summary="The official arXiv request will be retried",
                ),
                999_999,
            )
            return CommandOutcome.REQUEUE

    async def _process_import(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: ImportMetadataCommand,
    ) -> int | None:
        if command.mode == "SELECTED":
            if not command.arxiv_ids or command.criteria is not None:
                raise ValidationError.from_exception_data("ImportMetadataCommand", [])
            return await self._selected(envelope, command.arxiv_ids)
        if command.criteria is None or command.arxiv_ids:
            raise ValidationError.from_exception_data("ImportMetadataCommand", [])
        query, sort_by, sort_order = _legacy_query(command.criteria)
        processed = 0
        while processed < command.max_papers:
            if await self._pause_or_cancel(envelope, processed):
                return None
            size = min(self._batch_size, command.max_papers - processed)
            papers = await self._legacy.search_page(query, processed, size, sort_by, sort_order)
            if not papers:
                break
            await self._publish_batch(envelope, papers, processed)
            processed += len(papers)
            await self._publish_progress(envelope, processed, command.max_papers, processed + 1)
            if len(papers) < size:
                break
        return processed

    async def _selected(
        self, envelope: MessageEnvelope[dict[str, object]], arxiv_ids: tuple[str, ...]
    ) -> int | None:
        processed = 0
        for start in range(0, len(arxiv_ids), self._batch_size):
            if await self._pause_or_cancel(envelope, processed):
                return None
            identifiers = arxiv_ids[start : start + self._batch_size]
            papers = await self._legacy.fetch_ids(identifiers)
            await self._publish_batch(envelope, papers, start)
            processed += len(papers)
            await self._publish_progress(envelope, processed, len(arxiv_ids), start + 1)
        return processed

    async def _process_oai(
        self, envelope: MessageEnvelope[dict[str, object]], command: OaiSyncCommand
    ) -> int | None:
        processed = 0
        from_date = date.fromisoformat(command.from_date) if command.from_date else None
        async for page in self._oai.iter_record_pages(command.set_spec, from_date):
            if await self._pause_or_cancel(envelope, processed):
                return None
            papers = tuple(
                record.metadata for record in page.records if record.metadata is not None
            )
            if papers:
                await self._publish_batch(envelope, papers, processed)
                processed += len(papers)
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_PROGRESS,
                ResultPayload(
                    status="RUNNING",
                    stage="FETCHING_OAI",
                    processed_count=processed,
                    success_count=processed,
                    checkpoint={
                        "resumptionToken": page.resumption_token or "",
                        "responseDate": page.response_date.isoformat(),
                    },
                ),
                processed + 1,
            )
        return processed

    async def _pause_or_cancel(
        self, envelope: MessageEnvelope[dict[str, object]], processed: int
    ) -> bool:
        if envelope.job_id is None:
            return True
        control = await self._store.control_for(envelope.job_id)
        if control == "RUN":
            return False
        status = "PAUSED" if control == "PAUSE" else "CANCELED"
        event_type = (
            MessageType.ARXIV_JOB_PROGRESS
            if status == "PAUSED"
            else MessageType.ARXIV_JOB_COMPLETED
        )
        await self._publish(
            envelope,
            event_type,
            ResultPayload(status=status, stage=f"{status}_BY_USER", processed_count=processed),
            processed + 500_000,
        )
        if status == "CANCELED":
            await self._store.mark_processed(envelope.idempotency_key)
        return True

    async def _publish_batch(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        papers: tuple[ArxivMetadata, ...],
        sequence: int,
    ) -> None:
        await self._publish(
            envelope,
            MessageType.ARXIV_JOB_BATCH,
            ResultPayload(
                status="RUNNING",
                stage="PERSISTING_METADATA",
                papers=tuple(_paper_payload(paper) for paper in papers),
            ),
            sequence,
        )

    async def _publish_progress(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        processed: int,
        total: int,
        sequence: int,
    ) -> None:
        progress = 100.0 if total == 0 else min(100.0, processed * 100.0 / total)
        await self._publish(
            envelope,
            MessageType.ARXIV_JOB_PROGRESS,
            ResultPayload(
                status="RUNNING",
                stage="FETCHING_METADATA",
                processed_count=processed,
                success_count=processed,
                total_count=total,
                progress_percent=progress,
            ),
            sequence,
        )

    async def _publish(
        self,
        command: MessageEnvelope[dict[str, object]],
        message_type: MessageType,
        payload: ResultPayload,
        sequence: int,
    ) -> None:
        await self._publisher.publish(
            MessageEnvelope[ResultPayload](
                message_id=uuid4(),
                type=message_type,
                job_id=command.job_id,
                idempotency_key=f"{command.idempotency_key}:result:{message_type}:{sequence}",
                trace_id=command.trace_id,
                payload=payload,
            )
        )


def _paper_payload(paper: ArxivMetadata) -> dict[str, object]:
    return {
        "arxivId": paper.arxiv_id,
        "version": paper.version,
        "title": paper.title,
        "abstract": paper.abstract,
        "authors": [
            {"name": author.name, "affiliations": list(author.affiliations)}
            for author in paper.authors
        ],
        "primaryCategory": paper.primary_category,
        "categories": list(paper.categories),
        "publishedAt": _temporal(paper.published_at),
        "updatedAt": _temporal(paper.updated_at),
        "doi": paper.doi,
        "journalReference": paper.journal_reference,
        "comment": paper.comment,
        "licenseUrl": paper.license_url,
        "pdfUrl": paper.pdf_url or f"https://arxiv.org/pdf/{paper.arxiv_id}",
    }


def _temporal(value: date | datetime) -> str:
    return value.isoformat()


def _legacy_query(criteria: dict[str, object]) -> tuple[str, str, str]:
    clauses: list[str] = []
    categories = criteria.get("categoryIds")
    if isinstance(categories, list) and categories:
        category_clauses = [f"cat:{value}" for value in categories if isinstance(value, str)]
        if category_clauses:
            clauses.append("(" + " OR ".join(category_clauses) + ")")
    for key, field in (
        ("titleKeywords", "ti"),
        ("abstractKeywords", "abs"),
        ("authorKeywords", "au"),
    ):
        value = criteria.get(key)
        if isinstance(value, str) and value.strip():
            clauses.append(f'{field}:"{value.strip()}"')
    submitted_from = criteria.get("submittedFrom")
    submitted_to = criteria.get("submittedTo")
    if isinstance(submitted_from, str) or isinstance(submitted_to, str):
        start = (
            submitted_from.replace("-", "") + "0000"
            if isinstance(submitted_from, str)
            else "000000000000"
        )
        end = (
            submitted_to.replace("-", "") + "2359"
            if isinstance(submitted_to, str)
            else "999999999999"
        )
        clauses.append(f"submittedDate:[{start} TO {end}]")
    if not clauses:
        raise ValueError("Import criteria do not contain an official arXiv filter")
    sort_by = str(criteria.get("sortBy") or "RELEVANCE")
    sort_order = str(criteria.get("sortOrder") or "DESCENDING")
    return " AND ".join(clauses), sort_by, sort_order
