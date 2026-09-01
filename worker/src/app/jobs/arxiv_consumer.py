from __future__ import annotations

import hashlib
import re
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import UTC, date, datetime
from enum import StrEnum
from json.decoder import scanstring  # type: ignore[attr-defined]
from typing import Literal, Protocol, cast
from uuid import UUID, uuid4

import httpx
from pydantic import ValidationError

from app.arxiv.models import ArxivMetadata, OaiRecordPage
from app.arxiv.oai_client import OaiProtocolError, OaiTokenExpiredError
from app.arxiv.taxonomy import TaxonomyCategory
from app.jobs.job_control import SourceProgress
from app.messaging.contracts import (
    ImportMetadataCommand,
    MessageEnvelope,
    MessageType,
    OaiSyncCommand,
    ResultPayload,
    SourceExtractionCommand,
    SourceExtractionResult,
    SourceTarget,
    TaxonomySyncCommand,
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
        self,
        set_spec: str,
        from_date: date | None = None,
        *,
        resumption_token: str | None = None,
    ) -> AsyncIterator[OaiRecordPage]: ...

    async def fetch_taxonomy(self) -> tuple[TaxonomyCategory, ...]: ...


class ResultPublisher(Protocol):
    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None: ...


class SourceRunner(Protocol):
    async def run(self, target: SourceTarget) -> SourceExtractionResult: ...


class JobStore(Protocol):
    async def is_processed(self, key: str) -> bool: ...

    async def mark_processed(self, key: str) -> None: ...

    async def control_for(self, job_id: UUID) -> str: ...

    async def cursor_for(self, idempotency_key: str) -> str | None: ...

    async def save_cursor(self, idempotency_key: str, token: str) -> None: ...

    async def clear_cursor(self, idempotency_key: str) -> None: ...

    async def source_progress_for(self, idempotency_key: str) -> SourceProgress | None: ...

    async def save_source_progress(
        self, idempotency_key: str, progress: SourceProgress
    ) -> bool: ...

    async def clear_source_progress(self, idempotency_key: str) -> None: ...


class CommandOutcome(StrEnum):
    ACK = "ACK"
    DEFER = "DEFER"
    RETRY = "RETRY"
    DEAD = "DEAD"


_COMMAND_TYPES = {
    MessageType.ARXIV_IMPORT_METADATA,
    MessageType.ARXIV_SYNC_OAI,
    MessageType.ARXIV_SYNC_TAXONOMY,
    MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
    MessageType.ARXIV_REEXTRACT_CONTACTS,
}
_SOURCE_COMMAND_TYPES = {
    MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
    MessageType.ARXIV_REEXTRACT_CONTACTS,
}


@dataclass(frozen=True, slots=True)
class _FailureContext:
    job_id: UUID
    idempotency_key: str
    trace_id: str
    source_command: bool


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
        source_runner: SourceRunner | None = None,
    ) -> None:
        if not 1 <= batch_size <= 100:
            raise ValueError("metadata batch size must be between one and 100")
        self._legacy = legacy_client
        self._oai = oai_client
        self._publisher = publisher
        self._store = store
        self._batch_size = batch_size
        self._maximum_command_bytes = maximum_command_bytes
        self._source_runner = source_runner

    async def process(self, body: bytes) -> CommandOutcome:
        if not body or len(body) > self._maximum_command_bytes:
            return CommandOutcome.DEAD
        try:
            envelope = MessageEnvelope[dict[str, object]].model_validate_json(body)
            if envelope.job_id is None:
                return CommandOutcome.DEAD
            if envelope.type == MessageType.ARXIV_IMPORT_METADATA:
                command: (
                    ImportMetadataCommand
                    | OaiSyncCommand
                    | TaxonomySyncCommand
                    | SourceExtractionCommand
                ) = (
                    ImportMetadataCommand.model_validate(envelope.payload)
                )
            elif envelope.type == MessageType.ARXIV_SYNC_OAI:
                command = OaiSyncCommand.model_validate(envelope.payload)
            elif envelope.type == MessageType.ARXIV_SYNC_TAXONOMY:
                command = TaxonomySyncCommand.model_validate(envelope.payload)
            elif envelope.type in {
                MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
                MessageType.ARXIV_REEXTRACT_CONTACTS,
            }:
                if self._source_runner is None:
                    return CommandOutcome.DEAD
                command = SourceExtractionCommand.model_validate(envelope.payload)
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
            return CommandOutcome.DEFER
        if control == "CANCEL":
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_COMPLETED,
                ResultPayload(status="CANCELED", stage="CANCELED_BY_USER"),
                0,
            )
            await self._store.mark_processed(envelope.idempotency_key)
            if isinstance(command, SourceExtractionCommand):
                await self._store.clear_source_progress(envelope.idempotency_key)
            return CommandOutcome.ACK
        try:
            starting_stage = (
                "FETCHING_TAXONOMY"
                if isinstance(command, TaxonomySyncCommand)
                else "DOWNLOADING_SOURCE"
                if isinstance(command, SourceExtractionCommand)
                else "FETCHING_METADATA"
            )
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_STARTED,
                ResultPayload(status="RUNNING", stage=starting_stage),
                0,
            )
            summary: _SourceSummary | None = None
            result: int | _SourceSummary | CommandOutcome
            if isinstance(command, ImportMetadataCommand):
                result = await self._process_import(envelope, command)
            elif isinstance(command, OaiSyncCommand):
                result = await self._process_oai(envelope, command)
            elif isinstance(command, SourceExtractionCommand):
                result = await self._process_source(envelope, command)
            else:
                result = await self._process_taxonomy(envelope)
            if isinstance(result, CommandOutcome):
                return result
            if isinstance(result, _SourceSummary):
                summary = result
                processed = result.processed
            else:
                processed = result
            if not isinstance(command, TaxonomySyncCommand):
                if isinstance(command, SourceExtractionCommand):
                    if summary is None:
                        raise RuntimeError("Source extraction summary is unavailable")
                    terminal_status = _source_terminal_status(summary)
                    success_count = summary.success
                    skipped_count = summary.skipped
                    failed_count = summary.failed
                    total_count = len(command.targets)
                else:
                    terminal_status = "SUCCEEDED"
                    success_count = processed
                    skipped_count = 0
                    failed_count = 0
                    total_count = processed
                await self._publish(
                    envelope,
                    MessageType.ARXIV_JOB_COMPLETED,
                    ResultPayload(
                        status=terminal_status,
                        stage="COMPLETED",
                        processed_count=processed,
                        success_count=success_count,
                        skipped_count=skipped_count,
                        failed_count=failed_count,
                        total_count=total_count,
                        progress_percent=100,
                    ),
                    processed + 2,
                )
            await self._store.mark_processed(envelope.idempotency_key)
            if isinstance(command, SourceExtractionCommand):
                await self._store.clear_source_progress(envelope.idempotency_key)
            if isinstance(command, OaiSyncCommand):
                await self._store.clear_cursor(envelope.idempotency_key)
            return CommandOutcome.ACK
        except OaiTokenExpiredError:
            await self._store.clear_cursor(envelope.idempotency_key)
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_PROGRESS,
                ResultPayload(
                    status="RUNNING",
                    stage="RESTARTING_EXPIRED_CURSOR",
                    error_code="ARXIV_CURSOR_EXPIRED",
                    error_summary="The expired OAI cursor was cleared for a safe restart",
                ),
                999_998,
            )
            return CommandOutcome.RETRY
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
            return CommandOutcome.RETRY

    async def publish_retry_exhausted_failure(self, body: bytes) -> None:
        try:
            envelope = MessageEnvelope[dict[str, object]].model_validate_json(body)
        except (ValidationError, UnicodeDecodeError, ValueError):
            return
        context = _failure_context(envelope)
        if context is None:
            return
        await self._publish_terminal_failure(
            context,
            error_code="WORKER_RETRY_EXHAUSTED",
            error_summary=(
                "Worker exhausted retries after an unexpected processing failure"
            ),
        )

    async def publish_permanent_failure(self, body: bytes) -> None:
        context = _recover_command_context(body)
        if context is None:
            return
        await self._publish_terminal_failure(
            context,
            error_code="WORKER_COMMAND_INVALID",
            error_summary="Worker rejected an invalid arXiv job command",
        )

    async def _publish_terminal_failure(
        self,
        context: _FailureContext,
        *,
        error_code: str,
        error_summary: str,
    ) -> None:
        message_type = MessageType.ARXIV_JOB_FAILED
        sequence = 999_997
        await self._publisher.publish(
            MessageEnvelope[ResultPayload](
                message_id=uuid4(),
                type=message_type,
                job_id=context.job_id,
                idempotency_key=_result_idempotency_key(
                    context.idempotency_key, message_type, sequence
                ),
                trace_id=context.trace_id,
                payload=ResultPayload(
                    status="FAILED",
                    stage="FAILED",
                    error_code=error_code,
                    error_summary=error_summary,
                ),
            )
        )
        await self._store.mark_processed(context.idempotency_key)
        if context.source_command:
            await self._store.clear_source_progress(context.idempotency_key)

    async def _process_source(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
    ) -> _SourceSummary | CommandOutcome:
        if self._source_runner is None:
            raise RuntimeError("Source extraction runner is not configured")
        checkpoint = await self._store.source_progress_for(envelope.idempotency_key)
        if (
            checkpoint is not None
            and checkpoint.next_index <= len(command.targets)
            and checkpoint.success + checkpoint.skipped + checkpoint.failed
            == checkpoint.next_index
        ):
            start_index = checkpoint.next_index
            success = checkpoint.success
            skipped = checkpoint.skipped
            failed = checkpoint.failed
        else:
            start_index = 0
            success = skipped = failed = 0
            if checkpoint is not None:
                await self._store.clear_source_progress(envelope.idempotency_key)
        for index in range(start_index, len(command.targets)):
            control = await self._pause_or_cancel(
                envelope, index, clear_source_progress=True
            )
            if control is not None:
                return control
            target = command.targets[index]
            result = await self._source_runner.run(target)
            if result.status in {"SUCCEEDED", "PARTIALLY_SUCCEEDED"}:
                success += 1
            elif result.status == "SOURCE_UNAVAILABLE":
                skipped += 1
            else:
                failed += 1
            processed = index + 1
            await self._publish(
                envelope,
                MessageType.ARXIV_EXTRACTION_RESULT,
                ResultPayload(
                    status="RUNNING",
                    stage="PERSISTING_EXTRACTION",
                    processed_count=processed,
                    success_count=success,
                    skipped_count=skipped,
                    failed_count=failed,
                    total_count=len(command.targets),
                    progress_percent=processed * 100.0 / len(command.targets),
                    extractions=(result,),
                ),
                index * 2 + 1,
            )
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_PROGRESS,
                ResultPayload(
                    status="RUNNING",
                    stage="EXTRACTING_CONTACTS",
                    processed_count=processed,
                    success_count=success,
                    skipped_count=skipped,
                    failed_count=failed,
                    total_count=len(command.targets),
                    progress_percent=processed * 100.0 / len(command.targets),
                ),
                index * 2 + 2,
            )
            advanced = await self._store.save_source_progress(
                envelope.idempotency_key,
                SourceProgress(processed, success, skipped, failed),
            )
            if not advanced:
                return CommandOutcome.DEFER
        return _SourceSummary(len(command.targets), success, skipped, failed)

    async def _process_import(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: ImportMetadataCommand,
    ) -> int | CommandOutcome:
        if command.mode == "SELECTED":
            if not command.arxiv_ids or command.criteria is not None:
                raise ValidationError.from_exception_data("ImportMetadataCommand", [])
            return await self._selected(envelope, command.arxiv_ids)
        if command.criteria is None or command.arxiv_ids:
            raise ValidationError.from_exception_data("ImportMetadataCommand", [])
        query, sort_by, sort_order = _legacy_query(command.criteria)
        processed = 0
        while processed < command.max_papers:
            control = await self._pause_or_cancel(envelope, processed)
            if control is not None:
                return control
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
    ) -> int | CommandOutcome:
        processed = 0
        for start in range(0, len(arxiv_ids), self._batch_size):
            control = await self._pause_or_cancel(envelope, processed)
            if control is not None:
                return control
            identifiers = arxiv_ids[start : start + self._batch_size]
            papers = await self._legacy.fetch_ids(identifiers)
            await self._publish_batch(envelope, papers, start)
            processed += len(papers)
            await self._publish_progress(envelope, processed, len(arxiv_ids), start + 1)
        return processed

    async def _process_oai(
        self, envelope: MessageEnvelope[dict[str, object]], command: OaiSyncCommand
    ) -> int | CommandOutcome:
        processed = 0
        from_date = date.fromisoformat(command.from_date) if command.from_date else None
        cursor = await self._store.cursor_for(envelope.idempotency_key)
        async for page in self._oai.iter_record_pages(
            command.set_spec, from_date, resumption_token=cursor
        ):
            control = await self._pause_or_cancel(envelope, processed)
            if control is not None:
                return control
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
            if page.resumption_token is None:
                await self._store.clear_cursor(envelope.idempotency_key)
            else:
                await self._store.save_cursor(
                    envelope.idempotency_key, page.resumption_token
                )
            control = await self._pause_or_cancel(envelope, processed)
            if control is not None:
                return control
        return processed

    async def _process_taxonomy(
        self, envelope: MessageEnvelope[dict[str, object]]
    ) -> int | CommandOutcome:
        control = await self._pause_or_cancel(envelope, 0)
        if control is not None:
            return control
        categories = await self._oai.fetch_taxonomy()
        if not categories:
            raise OaiProtocolError("OAI ListSets returned no arXiv categories")
        observed_at = datetime.now(UTC)
        await self._publish(
            envelope,
            MessageType.ARXIV_JOB_COMPLETED,
            ResultPayload(
                status="SUCCEEDED",
                stage="COMPLETED",
                processed_count=len(categories),
                success_count=len(categories),
                total_count=len(categories),
                progress_percent=100,
                snapshot_version="oai-listsets-"
                + observed_at.strftime("%Y-%m-%dT%H-%M-%SZ"),
                taxonomy_source_updated_at=observed_at,
                taxonomy_categories=tuple(
                    _taxonomy_payload(category) for category in categories
                ),
            ),
            len(categories) + 2,
        )
        return len(categories)

    async def _pause_or_cancel(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        processed: int,
        *,
        clear_source_progress: bool = False,
    ) -> CommandOutcome | None:
        if envelope.job_id is None:
            return CommandOutcome.DEAD
        control = await self._store.control_for(envelope.job_id)
        if control == "RUN":
            return None
        status: Literal["PAUSED", "CANCELED"] = (
            "PAUSED" if control == "PAUSE" else "CANCELED"
        )
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
            if clear_source_progress:
                await self._store.clear_source_progress(envelope.idempotency_key)
            return CommandOutcome.ACK
        return CommandOutcome.DEFER

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
                idempotency_key=_result_idempotency_key(
                    command.idempotency_key, message_type, sequence
                ),
                trace_id=command.trace_id,
                payload=payload,
            )
        )


def _recover_command_context(body: bytes) -> _FailureContext | None:
    if not body or len(body) > 10 * 1024 * 1024:
        return None
    try:
        raw = _top_level_json_strings(body)
        raw_type = raw.get("type")
        if raw_type is None:
            return None
        try:
            message_type = MessageType(raw_type)
        except ValueError:
            if re.fullmatch(r"ARXIV_[A-Z0-9_]{1,70}", raw_type) is None:
                return None
            message_type = None
        if message_type is not None and message_type not in _COMMAND_TYPES:
            return None
        raw_job_id = raw.get("jobId")
        if raw_job_id is None:
            return None
        job_id = UUID(raw_job_id)
    except (UnicodeDecodeError, ValueError, TypeError, RecursionError):
        return None
    raw_key = raw.get("idempotencyKey")
    idempotency_key = (
        raw_key
        if raw_key is not None
        and 1 <= len(raw_key) <= 120
        and not any(ord(character) < 32 or ord(character) == 127 for character in raw_key)
        else f"arxiv-command:{job_id}"
    )
    raw_trace = raw.get("traceId")
    trace_id = (
        raw_trace
        if raw_trace is not None
        and re.fullmatch(r"[A-Za-z0-9_-]{8,64}", raw_trace) is not None
        else job_id.hex
    )
    return _FailureContext(
        job_id=job_id,
        idempotency_key=idempotency_key,
        trace_id=trace_id,
        source_command=message_type in _SOURCE_COMMAND_TYPES,
    )


def _failure_context(
    envelope: MessageEnvelope[dict[str, object]],
) -> _FailureContext | None:
    if envelope.job_id is None or envelope.type not in _COMMAND_TYPES:
        return None
    return _FailureContext(
        job_id=envelope.job_id,
        idempotency_key=envelope.idempotency_key,
        trace_id=envelope.trace_id,
        source_command=envelope.type in _SOURCE_COMMAND_TYPES,
    )


def _result_idempotency_key(
    command_key: str, message_type: MessageType, sequence: int
) -> str:
    suffix = f":result:{message_type}:{sequence}"
    maximum_base = 200 - len(suffix)
    if len(command_key) <= maximum_base:
        base = command_key
    else:
        digest = hashlib.sha256(command_key.encode("utf-8")).hexdigest()[:16]
        base = f"{command_key[:maximum_base - 17]}:{digest}"
    return f"{base}{suffix}"


def _top_level_json_strings(body: bytes) -> dict[str, str]:
    text = body.decode("utf-8", errors="strict")
    cursor = _skip_json_space(text, 0)
    if cursor >= len(text) or text[cursor] != "{":
        raise ValueError("Command envelope is not a JSON object")
    cursor += 1
    wanted = {"type", "jobId", "idempotencyKey", "traceId"}
    values: dict[str, str] = {}
    while True:
        cursor = _skip_json_space(text, cursor)
        if cursor >= len(text):
            raise ValueError("Command envelope is incomplete")
        if text[cursor] == "}":
            cursor += 1
            break
        name, cursor = _scan_json_string(text, cursor)
        cursor = _skip_json_space(text, cursor)
        if cursor >= len(text) or text[cursor] != ":":
            raise ValueError("Command envelope field has no value")
        cursor = _skip_json_space(text, cursor + 1)
        if cursor >= len(text):
            raise ValueError("Command envelope field is incomplete")
        if text[cursor] == '"':
            value, cursor = _scan_json_string(text, cursor)
            if name in wanted:
                if name in values:
                    raise ValueError("Command envelope field is duplicated")
                values[name] = value
        else:
            cursor = _skip_json_value(text, cursor)
        cursor = _skip_json_space(text, cursor)
        if cursor >= len(text):
            raise ValueError("Command envelope is incomplete")
        if text[cursor] == ",":
            cursor += 1
            continue
        if text[cursor] == "}":
            cursor += 1
            break
        raise ValueError("Command envelope has an invalid separator")
    if _skip_json_space(text, cursor) != len(text):
        raise ValueError("Command envelope has trailing data")
    return values


def _scan_json_string(text: str, cursor: int) -> tuple[str, int]:
    if cursor >= len(text) or text[cursor] != '"':
        raise ValueError("Expected a JSON string")
    return cast(tuple[str, int], scanstring(text, cursor + 1, True))


def _skip_json_space(text: str, cursor: int) -> int:
    while cursor < len(text) and text[cursor] in " \t\r\n":
        cursor += 1
    return cursor


def _skip_json_value(text: str, cursor: int) -> int:
    if text[cursor] == '"':
        return _scan_json_string(text, cursor)[1]
    if text[cursor] in "[{":
        closing = {"[": "]", "{": "}"}
        stack = [closing[text[cursor]]]
        cursor += 1
        while stack:
            if cursor >= len(text) or len(stack) > 100_000:
                raise ValueError("Command envelope nesting is invalid")
            character = text[cursor]
            if character == '"':
                _, cursor = _scan_json_string(text, cursor)
            elif character in closing:
                stack.append(closing[character])
                cursor += 1
            elif character in "]}":
                if character != stack.pop():
                    raise ValueError("Command envelope nesting is invalid")
                cursor += 1
            else:
                cursor += 1
        return cursor
    end = cursor
    while end < len(text) and text[end] not in ",}":
        end += 1
    primitive = text[cursor:end].strip()
    if re.fullmatch(
        r"(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)",
        primitive,
    ) is None:
        raise ValueError("Command envelope primitive is invalid")
    return cursor + len(text[cursor:end].rstrip())


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


_GROUP_NAMES = {
    "cs": "Computer Science",
    "econ": "Economics",
    "eess": "Electrical Engineering and Systems Science",
    "math": "Mathematics",
    "physics": "Physics",
    "q-bio": "Quantitative Biology",
    "q-fin": "Quantitative Finance",
    "stat": "Statistics",
}


def _taxonomy_payload(category: TaxonomyCategory) -> dict[str, object]:
    group_name = _GROUP_NAMES.get(category.group_id, category.group_id)
    archive_name = group_name if category.archive_id == category.group_id else category.archive_id
    if (
        len(category.group_id) > 40
        or len(group_name) > 120
        or len(category.archive_id) > 40
        or len(archive_name) > 160
        or len(category.category_id) > 80
        or not category.category_name.strip()
        or len(category.category_name) > 200
    ):
        raise OaiProtocolError("OAI taxonomy value exceeds the supported schema")
    return {
        "groupId": category.group_id,
        "groupName": group_name,
        "archiveId": category.archive_id,
        "archiveName": archive_name,
        "categoryId": category.category_id,
        "categoryName": category.category_name,
        "description": "",
        "alias": False,
        "aliasTarget": None,
    }


def _temporal(value: date | datetime) -> str:
    return value.isoformat()


class _SourceSummary:
    def __init__(self, processed: int, success: int, skipped: int, failed: int) -> None:
        self.processed = processed
        self.success = success
        self.skipped = skipped
        self.failed = failed


def _source_terminal_status(
    summary: _SourceSummary,
) -> Literal["SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED"]:
    if summary.failed == 0:
        return "SUCCEEDED"
    if summary.success > 0 or summary.skipped > 0:
        return "PARTIALLY_SUCCEEDED"
    return "FAILED"


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
