from __future__ import annotations

import asyncio
import hashlib
import math
import re
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from enum import StrEnum
from json.decoder import scanstring  # type: ignore[attr-defined]
from typing import Literal, Protocol, cast
from uuid import UUID, uuid4

import httpx
from pydantic import ValidationError
from redis.exceptions import RedisError

from app.arxiv.models import ArxivMetadata, OaiRecordPage
from app.arxiv.oai_client import OaiProtocolError, OaiTokenExpiredError
from app.arxiv.source_errors import SourceUpstreamReason, SourceUpstreamTransientError
from app.arxiv.taxonomy import TaxonomyCategory
from app.jobs.job_control import SourceProgress
from app.jobs.source_admission import SourceAdmissionDenied
from app.jobs.source_retry import (
    SourceHoldReason,
    SourcePhase,
    SourceRetryDecision,
    SourceScope,
    SourceState,
    SourceStateError,
)
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


class SourceStore(Protocol):
    @property
    def source_owner_id(self) -> UUID: ...

    async def load_or_bind_source(self, scope: SourceScope) -> SourceState: ...
    async def get_source(self, scope: SourceScope) -> SourceState: ...
    async def get_source_retirement(self, scope: SourceScope) -> SourceState | None: ...
    async def reserve_source_invocation(
        self,
        scope: SourceScope,
        expected_generation: int,
        index: int,
        owner_id: UUID,
        deadline: datetime,
    ) -> SourceState: ...
    async def take_source_invocation(
        self,
        scope: SourceScope,
        original_reservation: SourceState,
    ) -> bool: ...
    async def renew_source_invocation(
        self,
        scope: SourceScope,
        token: UUID,
        deadline: datetime,
    ) -> bool: ...
    async def stage_source_result(
        self,
        scope: SourceScope,
        token: UUID,
        result: SourceExtractionResult,
    ) -> SourceState: ...
    async def record_source_transient(
        self,
        scope: SourceScope,
        token: UUID,
        reason: SourceUpstreamReason,
    ) -> SourceState: ...
    async def hold_source(
        self,
        scope: SourceScope,
        expected_generation: int,
        reason: SourceHoldReason,
    ) -> SourceState: ...
    async def advance_source(
        self,
        scope: SourceScope,
        expected_generation: int,
        index: int,
    ) -> SourceState: ...
    async def record_source_terminal(
        self,
        scope: SourceScope,
        expected_generation: int,
        status: str,
    ) -> SourceState: ...
    async def retire_source_terminal(
        self,
        scope: SourceScope,
        expected_generation: int,
    ) -> SourceState: ...


_SOURCE_UNCERTAINTY = (
    SourceAdmissionDenied,
    SourceStateError,
    RedisError,
    ConnectionError,
    TimeoutError,
)


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
        source_store: SourceStore | None = None,
        source_invocation_lease_seconds: float = 120,
        source_invocation_renew_seconds: float = 30,
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
        if not (
            math.isfinite(source_invocation_lease_seconds)
            and math.isfinite(source_invocation_renew_seconds)
            and source_invocation_renew_seconds > 0
            and 3 * source_invocation_renew_seconds <= source_invocation_lease_seconds <= 120
        ):
            raise ValueError("Invalid source invocation lease configuration")
        self._source_store = source_store
        self._source_lease = source_invocation_lease_seconds
        self._source_renew = source_invocation_renew_seconds

    async def process(self, body: bytes) -> CommandOutcome | SourceRetryDecision:
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
                ) = ImportMetadataCommand.model_validate(envelope.payload)
            elif envelope.type == MessageType.ARXIV_SYNC_OAI:
                command = OaiSyncCommand.model_validate(envelope.payload)
            elif envelope.type == MessageType.ARXIV_SYNC_TAXONOMY:
                command = TaxonomySyncCommand.model_validate(envelope.payload)
            elif envelope.type in {
                MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
                MessageType.ARXIV_REEXTRACT_CONTACTS,
            }:
                command = SourceExtractionCommand.model_validate(envelope.payload)
            else:
                return CommandOutcome.DEAD
        except (ValidationError, UnicodeDecodeError, ValueError):
            return CommandOutcome.DEAD
        if isinstance(command, SourceExtractionCommand):
            scope = SourceScope.from_command(
                envelope.idempotency_key,
                envelope.job_id,
                envelope.message_id,
                envelope.type,
                envelope.payload,
            )
            try:
                return await self._process_source(envelope, command, scope)
            except SourceAdmissionDenied as error:
                return await self._source_denied(envelope, command, scope, error)
            except SourceStateError:
                return await self._source_hold(envelope, command, scope, None)
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
            return CommandOutcome.ACK
        try:
            starting_stage = (
                "FETCHING_TAXONOMY"
                if isinstance(command, TaxonomySyncCommand)
                else "FETCHING_METADATA"
            )
            await self._publish(
                envelope,
                MessageType.ARXIV_JOB_STARTED,
                ResultPayload(status="RUNNING", stage=starting_stage),
                0,
            )
            result: int | CommandOutcome
            if isinstance(command, ImportMetadataCommand):
                result = await self._process_import(envelope, command)
            elif isinstance(command, OaiSyncCommand):
                result = await self._process_oai(envelope, command)
            else:
                result = await self._process_taxonomy(envelope)
            if isinstance(result, CommandOutcome):
                return result
            processed = result
            if not isinstance(command, TaxonomySyncCommand):
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

    async def publish_retry_exhausted_failure(
        self,
        body: bytes,
        source_decision: SourceRetryDecision | None = None,
    ) -> None:
        try:
            envelope = MessageEnvelope[dict[str, object]].model_validate_json(body)
        except (ValidationError, UnicodeDecodeError, ValueError):
            return
        context = _failure_context(envelope)
        if context is None:
            return
        if context.source_command:
            await self._publish_source_failure(
                envelope,
                error_code="SOURCE_UPSTREAM_RETRY_EXHAUSTED"
                if source_decision is not None
                else "WORKER_RETRY_EXHAUSTED",
                source_decision=source_decision,
            )
            return
        if source_decision is not None:
            raise SourceStateError()
        await self._publish_terminal_failure(
            context,
            error_code="WORKER_RETRY_EXHAUSTED",
            error_summary=("Worker exhausted retries after an unexpected processing failure"),
        )

    async def publish_permanent_failure(self, body: bytes) -> None:
        context = _recover_command_context(body)
        if context is None:
            return
        if context.source_command:
            try:
                envelope = MessageEnvelope[dict[str, object]].model_validate_json(body)
            except (ValidationError, UnicodeDecodeError, ValueError):
                return
            await self._publish_source_failure(envelope, error_code="WORKER_COMMAND_INVALID")
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

    async def _publish_source_failure(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        *,
        error_code: str,
        source_decision: SourceRetryDecision | None = None,
    ) -> None:
        try:
            if envelope.job_id is None:
                return
            command = SourceExtractionCommand.model_validate(envelope.payload)
            scope = SourceScope.from_command(
                envelope.idempotency_key,
                envelope.job_id,
                envelope.message_id,
                envelope.type,
                envelope.payload,
            )
        except (ValidationError, ValueError):
            # Identified poison is transport-quarantined, not source authority.
            return
        try:
            if await self._source_retirement(scope):
                return
            state = await self._sources().load_or_bind_source(scope)
        except SourceAdmissionDenied as error:
            if error.status in {"PAUSED", "UNAVAILABLE"}:
                raise
            await self._source_denied(envelope, command, scope, error)
            return
        except SourceStateError:
            await self._source_hold(envelope, command, scope, None)
            return
        try:
            if (
                await self._store.is_processed(scope.idempotency_key)
                or state.phase == SourcePhase.HELD
            ):
                await self._source_hold(envelope, command, scope, state)
                return
            if source_decision is not None and (
                state.phase != SourcePhase.EXHAUSTED
                or not source_decision.exhausted
                or self._source_decision(state) != source_decision
            ):
                raise SourceStateError()
            await self._finish_source(
                envelope, command, state, status="FAILED", error_code=error_code
            )
        except SourceAdmissionDenied as error:
            if error.status == "TERMINAL":
                return
            if error.code == "SCOPE_CONFLICT":
                await self._source_denied(envelope, command, scope, error)
                return
            # Unresolved drain/advance/PAUSED cannot settle Kafka exhaustion.
            raise

    def _sources(self) -> SourceStore:
        if self._source_store is None:
            raise RuntimeError("Source state store is not configured")
        return self._source_store

    def _source_deadline(self) -> datetime:
        deadline = datetime.now(UTC) + timedelta(seconds=self._source_lease)
        return deadline.replace(microsecond=deadline.microsecond // 1000 * 1000)

    async def _source_retirement(self, scope: SourceScope) -> bool:
        state = await self._sources().get_source_retirement(scope)
        if state is None:
            return False
        if state.phase == SourcePhase.TERMINAL_PENDING:
            # Pending means the terminal publication was already confirmed.
            await self._store.mark_processed(scope.idempotency_key)
            await self._sources().retire_source_terminal(scope, state.generation)
        return True

    async def _source_denied(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
        scope: SourceScope,
        error: SourceAdmissionDenied,
    ) -> CommandOutcome:
        if error.status == "TERMINAL":
            return CommandOutcome.ACK
        if error.status == "PAUSED":
            return CommandOutcome.DEFER
        if error.status == "UNAVAILABLE":
            raise error
        if error.code == "SCOPE_CONFLICT":
            # Independent fixed-value generic-TTL marker; never touch the original.
            material = (
                scope.idempotency_key
                + "\0"
                + str(scope.command_message_id)
                + "\0"
                + scope.payload_fingerprint
            )
            key = "source-quarantine:" + hashlib.sha256(material.encode()).hexdigest()
            if not await self._store.is_processed(key):
                await self._store.mark_processed(key)
            return CommandOutcome.ACK
        return await self._source_hold(envelope, command, scope, None)

    async def _source_hold(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
        scope: SourceScope,
        state: SourceState | None,
    ) -> CommandOutcome:
        if state is not None and state.phase not in {
            SourcePhase.HELD,
            SourcePhase.RESULT_READY,
            SourcePhase.TERMINAL_PENDING,
            SourcePhase.TERMINAL,
        }:
            try:
                state = await self._sources().hold_source(
                    scope, state.generation, SourceHoldReason.INVOCATION_UNCERTAIN
                )
            except SourceAdmissionDenied as error:
                if (
                    error.status in {"TERMINAL", "PAUSED", "UNAVAILABLE"}
                    or error.code == "SCOPE_CONFLICT"
                ):
                    return await self._source_denied(envelope, command, scope, error)
                # A racing CAS is not permission to overwrite or resume its state.
                state = None
        progress = state.progress if state is not None else None
        payload = self._source_payload(
            command, progress, status="PAUSED", stage="SOURCE_RECONCILIATION_REQUIRED"
        )
        if progress is not None:
            payload = payload.model_copy(
                update={
                    "checkpoint": {
                        "nextIndex": progress.next_index,
                        "success": progress.success,
                        "skipped": progress.skipped,
                        "failed": progress.failed,
                    }
                }
            )
        await self._publish(envelope, MessageType.ARXIV_JOB_PROGRESS, payload, 999_996)
        return CommandOutcome.ACK

    @staticmethod
    def _source_payload(
        command: SourceExtractionCommand,
        progress: SourceProgress | None,
        *,
        status: Literal[
            "RUNNING", "PAUSED", "CANCELED", "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED"
        ],
        stage: str,
    ) -> ResultPayload:
        p = progress or SourceProgress(0, 0, 0, 0)
        return ResultPayload(
            status=status,
            stage=stage,
            processed_count=p.next_index,
            success_count=p.success,
            skipped_count=p.skipped,
            failed_count=p.failed,
            total_count=len(command.targets),
            progress_percent=p.next_index * 100.0 / len(command.targets),
        )

    @staticmethod
    def _source_result_matches(
        command: SourceExtractionCommand, state: SourceState, result: SourceExtractionResult
    ) -> bool:
        index = state.progress.next_index
        if not 0 <= index < len(command.targets):
            return False
        target = command.targets[index]
        return (
            result.paper_id == target.paper_id
            and result.arxiv_id == target.arxiv_id
            and result.parser_version == command.parser_version
        )

    async def _recover_source_invocation(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
        scope: SourceScope,
    ) -> SourceState | CommandOutcome:
        # BIND can recover publication only, never the original execution grant.
        state = await self._sources().get_source(scope)
        if state.phase == SourcePhase.RESULT_READY:
            return state
        return await self._source_hold(envelope, command, scope, state)

    async def _invoke_source(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
        scope: SourceScope,
        original: SourceState,
    ) -> SourceState | CommandOutcome:
        store, runner = self._sources(), self._source_runner
        assert runner is not None and original.invocation is not None
        target, token = command.targets[original.progress.next_index], original.invocation.token
        stopped = asyncio.Event()
        taken = False
        owned_generation = original.generation

        async def renew() -> bool:
            nonlocal owned_generation
            while not stopped.is_set():
                try:
                    await asyncio.wait_for(stopped.wait(), timeout=self._source_renew)
                    break
                except TimeoutError:
                    pass
                if stopped.is_set():
                    break
                if not taken:
                    continue
                try:
                    # A stalled transport is uncertainty, not permission to extend
                    # the owner task indefinitely. Normal cooperative stop does
                    # not cancel renewal; only its own bound/shutdown may do so.
                    async with asyncio.timeout(self._source_renew):
                        if not await store.renew_source_invocation(
                            scope, token, self._source_deadline()
                        ):
                            return False
                    owned_generation += 1
                except _SOURCE_UNCERTAINTY:
                    return False
            return True

        async def execute() -> SourceExtractionResult:
            nonlocal taken
            # Assignment is part of the take condition, not intervening work.
            # Nothing is awaited or scheduled between true take and actual runner.
            if taken := await store.take_source_invocation(scope, original):
                return await runner.run(target)
            raise SourceStateError()

        renewal = asyncio.create_task(renew())
        result: SourceExtractionResult | None = None
        transient: SourceUpstreamReason | None = None
        try:
            try:
                result = await execute()
            except SourceUpstreamTransientError as error:
                transient = error.reason
            except _SOURCE_UNCERTAINTY:
                # take itself may have an unknown outcome; never try it again.
                if taken:
                    raise
            finally:
                stopped.set()
            renewed = await renewal  # normal completion cooperatively joins, never cancels
        except asyncio.CancelledError:
            stopped.set()
            renewal.cancel()  # shutdown deliberately closes any in-flight ownership
            await asyncio.gather(renewal, return_exceptions=True)
            try:
                # Also close a reservation canceled before the first renewal.
                # Only the last confirmed generation is eligible; unknown/racing
                # writes remain untouched and no result is ever discarded.
                async with asyncio.timeout(self._source_renew):
                    await store.hold_source(
                        scope, owned_generation, SourceHoldReason.INVOCATION_UNCERTAIN
                    )
            except Exception:
                # Cleanup must not replace the owner's cancellation with a store
                # failure. No retry, publication, or detached work is started.
                pass
            raise
        except BaseException:
            stopped.set()
            renewal.cancel()
            await asyncio.gather(renewal, return_exceptions=True)
            raise
        if not renewed or (result is None and transient is None):
            return await self._recover_source_invocation(envelope, command, scope)
        if result is not None and not self._source_result_matches(command, original, result):
            return await self._source_hold(envelope, command, scope, await store.get_source(scope))
        try:
            if transient is not None:
                return await store.record_source_transient(scope, token, transient)
            assert result is not None
            return await store.stage_source_result(scope, token, result)
        except _SOURCE_UNCERTAINTY:
            return await self._recover_source_invocation(envelope, command, scope)

    @staticmethod
    def _source_decision(state: SourceState) -> SourceRetryDecision:
        if state.invocation is None or not isinstance(state.reason, SourceUpstreamReason):
            raise SourceStateError()
        return SourceRetryDecision(
            state.scope,
            state.generation,
            state.progress.next_index,
            state.invocation.attempt_number,
            state.reason,
            state.phase == SourcePhase.EXHAUSTED,
        )

    async def _source_retry(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
        state: SourceState,
    ) -> SourceRetryDecision:
        decision = self._source_decision(state)
        payload = self._source_payload(
            command, state.progress, status="RUNNING", stage="RETRYING_UPSTREAM"
        ).model_copy(
            update={
                "error_code": "SOURCE_UPSTREAM_" + decision.reason.value,
                "error_summary": "The official arXiv source request will be retried",
            }
        )
        await self._publish(envelope, MessageType.ARXIV_JOB_PROGRESS, payload, 999_999)
        return decision

    async def _drain_source_result(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
        state: SourceState,
    ) -> SourceState:
        result, p = state.result, state.progress
        if result is None or not self._source_result_matches(command, state, result):
            raise SourceStateError()
        success = result.status in {"SUCCEEDED", "PARTIALLY_SUCCEEDED"}
        skipped = result.status == "SOURCE_UNAVAILABLE"
        progress = SourceProgress(
            p.next_index + 1,
            p.success + int(success),
            p.skipped + int(skipped),
            p.failed + int(not success and not skipped),
        )
        payload = self._source_payload(
            command, progress, status="RUNNING", stage="PERSISTING_EXTRACTION"
        ).model_copy(update={"extractions": (result,)})
        await self._publish(
            envelope, MessageType.ARXIV_EXTRACTION_RESULT, payload, 2 * p.next_index + 1
        )
        await self._publish(
            envelope,
            MessageType.ARXIV_JOB_PROGRESS,
            self._source_payload(command, progress, status="RUNNING", stage="EXTRACTING_CONTACTS"),
            2 * p.next_index + 2,
        )
        try:
            advanced = await self._sources().advance_source(
                state.scope, state.generation, p.next_index
            )
        except _SOURCE_UNCERTAINTY:
            advanced = await self._sources().get_source(state.scope)
        # Only this exact durable movement can earn the next target's allowance.
        if (
            advanced.phase != SourcePhase.READY
            or advanced.generation <= state.generation
            or advanced.progress != progress
        ):
            raise SourceStateError()
        return advanced

    async def _finish_source(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
        state: SourceState,
        *,
        status: Literal["CANCELED", "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED"],
        error_code: str | None = None,
    ) -> CommandOutcome:
        if state.phase == SourcePhase.RESULT_READY:
            state = await self._drain_source_result(envelope, command, state)
        payload = self._source_payload(
            command,
            state.progress,
            status=status,
            stage="FAILED"
            if error_code
            else "CANCELED_BY_USER"
            if status == "CANCELED"
            else "COMPLETED",
        )
        if error_code:
            payload = payload.model_copy(
                update={
                    "error_code": error_code,
                    "error_summary": (
                        "Worker exhausted retries for the current official arXiv source target"
                    )
                    if error_code == "SOURCE_UPSTREAM_RETRY_EXHAUSTED"
                    else "Worker rejected an invalid arXiv job command"
                    if error_code == "WORKER_COMMAND_INVALID"
                    else "Worker exhausted retries after an unexpected processing failure",
                }
            )
        await self._publish(
            envelope,
            MessageType.ARXIV_JOB_FAILED if error_code else MessageType.ARXIV_JOB_COMPLETED,
            payload,
            999_997 if error_code else len(command.targets) + 2,
        )
        try:
            await self._sources().record_source_terminal(state.scope, state.generation, status)
        except _SOURCE_UNCERTAINTY:
            if await self._source_retirement(state.scope):
                return CommandOutcome.ACK
            # Publication may have made the DB terminal before pending was written.
            # BIND terminal denial acknowledges that actual status without a tombstone.
            await self._sources().get_source(state.scope)
            raise
        if not await self._source_retirement(state.scope):
            raise SourceStateError()
        return CommandOutcome.ACK

    async def _process_source(
        self,
        envelope: MessageEnvelope[dict[str, object]],
        command: SourceExtractionCommand,
        scope: SourceScope,
    ) -> CommandOutcome | SourceRetryDecision:
        if await self._source_retirement(scope):
            return CommandOutcome.ACK
        state = await self._sources().load_or_bind_source(scope)
        started = False
        while True:
            if state.progress.next_index > len(command.targets):
                return await self._source_hold(envelope, command, scope, None)
            if await self._store.is_processed(scope.idempotency_key):
                return await self._source_hold(envelope, command, scope, state)
            if state.phase == SourcePhase.HELD:
                return await self._source_hold(envelope, command, scope, state)
            control = await self._store.control_for(scope.job_id)
            if control == "PAUSE":
                await self._publish(
                    envelope,
                    MessageType.ARXIV_JOB_PROGRESS,
                    self._source_payload(
                        command, state.progress, status="PAUSED", stage="PAUSED_BY_USER"
                    ),
                    state.progress.next_index + 500_000,
                )
                return CommandOutcome.DEFER
            if control == "CANCEL":
                return await self._finish_source(envelope, command, state, status="CANCELED")
            if state.phase == SourcePhase.RESERVED:
                if state.invocation is not None and state.invocation.deadline > datetime.now(UTC):
                    return CommandOutcome.DEFER
                return await self._source_hold(envelope, command, scope, state)
            if state.phase == SourcePhase.EXHAUSTED:
                return await self._source_retry(envelope, command, state)
            if state.phase == SourcePhase.RESULT_READY:
                if state.result is None or not self._source_result_matches(
                    command, state, state.result
                ):
                    return await self._source_hold(envelope, command, scope, state)
                try:
                    state = await self._drain_source_result(envelope, command, state)
                except SourceStateError:
                    return await self._source_hold(
                        envelope, command, scope, await self._sources().get_source(scope)
                    )
                state = await self._sources().get_source(scope)
                continue
            if state.phase not in {SourcePhase.READY, SourcePhase.RETRY_READY}:
                return await self._source_hold(envelope, command, scope, state)
            if state.progress.next_index == len(command.targets):
                p = state.progress
                return await self._finish_source(
                    envelope,
                    command,
                    state,
                    status=_source_terminal_status(
                        _SourceSummary(p.next_index, p.success, p.skipped, p.failed)
                    ),
                )
            if not started:
                if self._source_runner is None:
                    raise RuntimeError("Source extraction runner is not configured")
                await self._publish(
                    envelope,
                    MessageType.ARXIV_JOB_STARTED,
                    self._source_payload(
                        command, state.progress, status="RUNNING", stage="DOWNLOADING_SOURCE"
                    ),
                    0,
                )
                started = True
            try:
                original = await self._sources().reserve_source_invocation(
                    scope,
                    state.generation,
                    state.progress.next_index,
                    self._sources().source_owner_id,
                    self._source_deadline(),
                )
            except _SOURCE_UNCERTAINTY:
                recovered = await self._recover_source_invocation(envelope, command, scope)
                if isinstance(recovered, CommandOutcome):
                    return recovered
                state = recovered
                continue
            invoked = await self._invoke_source(envelope, command, scope, original)
            if isinstance(invoked, CommandOutcome):
                return invoked
            if invoked.phase in {SourcePhase.RETRY_READY, SourcePhase.EXHAUSTED}:
                return await self._source_retry(envelope, command, invoked)
            # Stage is local ownership/CAS, not current DB status authority.
            # A canceled/terminal job wins before result or control publications.
            state = await self._sources().get_source(scope)

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
                await self._store.save_cursor(envelope.idempotency_key, page.resumption_token)
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
                snapshot_version="oai-listsets-" + observed_at.strftime("%Y-%m-%dT%H-%M-%SZ"),
                taxonomy_source_updated_at=observed_at,
                taxonomy_categories=tuple(_taxonomy_payload(category) for category in categories),
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
        status: Literal["PAUSED", "CANCELED"] = "PAUSED" if control == "PAUSE" else "CANCELED"
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
        if raw_trace is not None and re.fullmatch(r"[A-Za-z0-9_-]{8,64}", raw_trace) is not None
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


def _result_idempotency_key(command_key: str, message_type: MessageType, sequence: int) -> str:
    suffix = f":result:{message_type}:{sequence}"
    maximum_base = 200 - len(suffix)
    if len(command_key) <= maximum_base:
        base = command_key
    else:
        digest = hashlib.sha256(command_key.encode("utf-8")).hexdigest()[:16]
        base = f"{command_key[: maximum_base - 17]}:{digest}"
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
    if (
        re.fullmatch(
            r"(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)",
            primitive,
        )
        is None
    ):
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
            else "000101010000"
        )
        end = (
            submitted_to.replace("-", "") + "2359"
            if isinstance(submitted_to, str)
            else "999912312359"
        )
        clauses.append(f"submittedDate:[{start} TO {end}]")
    if not clauses:
        raise ValueError("Import criteria do not contain an official arXiv filter")
    sort_by = str(criteria.get("sortBy") or "RELEVANCE")
    sort_order = str(criteria.get("sortOrder") or "DESCENDING")
    return " AND ".join(clauses), sort_by, sort_order
