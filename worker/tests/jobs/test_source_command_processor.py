from __future__ import annotations

import asyncio
import contextlib
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from test_arxiv_consumer import FakeLegacyClient, FakeOaiClient, FakeStore

from app.arxiv.source_errors import SourceUpstreamReason, SourceUpstreamTransientError
from app.jobs.arxiv_consumer import ArxivCommandProcessor, CommandOutcome
from app.jobs.job_control import SourceProgress
from app.jobs.source_admission import AdmissionReply, SourceAdmissionDenied
from app.jobs.source_retry import (
    SourceAuthorityReceipt,
    SourceHoldReason,
    SourceInvocation,
    SourcePhase,
    SourceRetryDecision,
    SourceScope,
    SourceState,
)
from app.messaging.contracts import (
    MessageEnvelope,
    MessageType,
    ResultPayload,
    SourceExtractionResult,
    SourceTarget,
)


def denied(status: str = "HELD", code: str = "INVOCATION_UNCERTAIN") -> SourceAdmissionDenied:
    return SourceAdmissionDenied(
        AdmissionReply(
            status,
            code,
            None,
            None,
            None,
            "CANCELED" if status == "TERMINAL" else None,
        )
    )


class Store(FakeStore):
    """Synthetic processor seam, not Java/Redis authority evidence.

    Uses real strict codec and identity-only take. Faults distinguish unknown
    replies before/after a transition; none of this proves distributed CAS.
    """

    def __init__(self, progress: SourceProgress | None = None) -> None:
        super().__init__()
        self.source_owner_id = uuid4()
        self.state: SourceState | None = None
        self.initial = progress or SourceProgress(0, 0, 0, 0)
        self.original: SourceState | None = None
        self.active = False
        self.events: list[str] = []
        self.faults: dict[str, str] = {}
        self.admission: SourceAdmissionDenied | None = None
        self.renew_entered = asyncio.Event()
        self.renew_release: asyncio.Event | None = None
        self.renew_canceled = False
        self.deny_after_stage = False
        self.deadlines: list[datetime] = []

    def current(self) -> SourceState:
        assert self.state is not None
        return self.state

    def write(self, state: SourceState) -> SourceState:
        self.state = SourceState.from_json(state.to_json())
        return self.state

    def fault(self, operation: str, point: str) -> None:
        if self.faults.get(operation) == point:
            del self.faults[operation]
            raise denied("UNAVAILABLE", "COORDINATION_FAILED")

    async def load_or_bind_source(self, scope: SourceScope) -> SourceState:
        if self.admission:
            raise self.admission
        if self.state is None:
            self.write(
                SourceState(
                    scope,
                    0,
                    self.initial,
                    SourcePhase.READY,
                    authority=SourceAuthorityReceipt(1, uuid4(), self.source_owner_id, None, 0, 0),
                )
            )
        if self.current().scope != scope:
            raise denied(code="SCOPE_CONFLICT")
        return SourceState.from_json(self.current().to_json())

    async def get_source(self, scope: SourceScope) -> SourceState:
        return await self.load_or_bind_source(scope)

    async def get_source_retirement(self, scope: SourceScope) -> SourceState | None:
        if self.state and self.state.scope != scope:
            raise denied(code="SCOPE_CONFLICT")
        return (
            self.state
            if self.state
            and self.state.phase in {SourcePhase.TERMINAL_PENDING, SourcePhase.TERMINAL}
            else None
        )

    async def reserve_source_invocation(
        self,
        scope: SourceScope,
        expected_generation: int,
        index: int,
        owner_id: UUID,
        deadline: datetime,
    ) -> SourceState:
        self.fault("reserve", "before")
        self.deadlines.append(deadline)
        s = self.current()
        assert s.scope == scope and s.generation == expected_generation
        assert s.phase in {SourcePhase.READY, SourcePhase.RETRY_READY}
        assert index == s.progress.next_index and owner_id == self.source_owner_id
        attempt = s.invocation.attempt_number + 1 if s.invocation else 1
        assert attempt <= 6 and s.authority
        token = uuid4()
        self.original = self.write(
            replace(
                s,
                generation=s.generation + 2,
                phase=SourcePhase.RESERVED,
                reason=None,
                invocation=SourceInvocation(token, index, attempt, owner_id, deadline),
                authority=SourceAuthorityReceipt(
                    s.authority.sequence + 1,
                    uuid4(),
                    owner_id,
                    token,
                    s.generation + 1,
                    s.authority.sequence,
                ),
            )
        )
        self.events.append("reserve")
        self.fault("reserve", "after")
        return self.original

    async def take_source_invocation(
        self, scope: SourceScope, original_reservation: SourceState
    ) -> bool:
        self.fault("take", "before")
        assert original_reservation is self.original and scope == self.current().scope
        self.original = None
        self.active = True
        self.events.append("take")
        self.fault("take", "after")
        return True

    async def renew_source_invocation(
        self, scope: SourceScope, token: UUID, deadline: datetime
    ) -> bool:
        self.renew_entered.set()
        try:
            if self.renew_release:
                await self.renew_release.wait()
            self.fault("renew", "before")
            s = self.current()
            assert self.active and s.invocation and s.invocation.token == token
            self.write(
                replace(
                    s,
                    generation=s.generation + 1,
                    invocation=replace(s.invocation, deadline=deadline),
                )
            )
            self.events.append("renew")
            return True
        except BaseException:
            self.active = False
            self.renew_canceled = True
            raise

    async def stage_source_result(
        self, scope: SourceScope, token: UUID, result: SourceExtractionResult
    ) -> SourceState:
        assert self.active
        self.active = False
        self.fault("stage", "before")
        s = self.current()
        assert s.invocation and s.invocation.token == token
        state = self.write(
            replace(s, generation=s.generation + 1, phase=SourcePhase.RESULT_READY, result=result)
        )
        self.events.append("stage")
        if self.deny_after_stage:
            self.admission = denied("TERMINAL", "JOB_TERMINAL")
        self.fault("stage", "after")
        return state

    async def record_source_transient(
        self, scope: SourceScope, token: UUID, reason: SourceUpstreamReason
    ) -> SourceState:
        assert self.active
        self.active = False
        s = self.current()
        assert s.invocation and s.invocation.token == token
        self.events.append("transient")
        return self.write(
            replace(
                s,
                generation=s.generation + 1,
                reason=reason,
                phase=SourcePhase.EXHAUSTED
                if s.invocation.attempt_number == 6
                else SourcePhase.RETRY_READY,
            )
        )

    async def hold_source(
        self, scope: SourceScope, expected_generation: int, reason: SourceHoldReason
    ) -> SourceState:
        s = self.current()
        if s.generation != expected_generation or s.phase == SourcePhase.RESULT_READY:
            raise denied(code="PREDECESSOR_CONFLICT")
        self.active = False
        return self.write(
            replace(s, generation=s.generation + 1, phase=SourcePhase.HELD, reason=reason)
        )

    async def advance_source(
        self, scope: SourceScope, expected_generation: int, index: int
    ) -> SourceState:
        self.fault("advance", "before")
        s = await self.get_source(scope)
        assert s.generation == expected_generation and s.result and index == s.progress.next_index
        p = s.progress
        success = s.result.status in {"SUCCEEDED", "PARTIALLY_SUCCEEDED"}
        skipped = s.result.status == "SOURCE_UNAVAILABLE"
        state = self.write(
            replace(
                s,
                generation=s.generation + 1,
                phase=SourcePhase.READY,
                result=None,
                invocation=None,
                progress=SourceProgress(
                    index + 1,
                    p.success + int(success),
                    p.skipped + int(skipped),
                    p.failed + int(not success and not skipped),
                ),
            )
        )
        self.events.append("advance")
        self.fault("advance", "after")
        return state

    async def record_source_terminal(
        self, scope: SourceScope, expected_generation: int, status: str
    ) -> SourceState:
        self.fault("terminal", "before")
        s = self.current()
        assert s.generation == expected_generation and s.phase != SourcePhase.RESULT_READY
        state = self.write(
            replace(
                s,
                generation=s.generation + 1,
                phase=SourcePhase.TERMINAL_PENDING,
                invocation=None,
                result=None,
                reason=None,
                terminal_status=status,
            )
        )
        self.events.append("pending")
        self.fault("terminal", "after")
        return state

    async def retire_source_terminal(
        self, scope: SourceScope, expected_generation: int
    ) -> SourceState:
        self.fault("retire", "before")
        s = self.current()
        assert s.generation == expected_generation and s.phase == SourcePhase.TERMINAL_PENDING
        self.events.append("retire")
        return self.write(replace(s, generation=s.generation + 1, phase=SourcePhase.TERMINAL))

    async def mark_processed(self, key: str) -> None:
        self.fault("marker", "before")
        self.events.append("marker")
        await super().mark_processed(key)


class Publisher:
    def __init__(self, store: Store) -> None:
        self.store = store
        self.messages: list[MessageEnvelope[ResultPayload]] = []
        self.fail: MessageType | None = None
        self.accept_then_fail = False
        self.terminal_denial = False

    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None:
        if message.type == self.fail and not self.accept_then_fail:
            raise RuntimeError("synthetic publication failure")
        self.messages.append(message)
        self.store.events.append(f"publish:{message.type.value}")
        if message.type == self.fail:
            raise RuntimeError("synthetic publication uncertainty")
        if self.terminal_denial and message.type == MessageType.ARXIV_JOB_COMPLETED:
            self.store.admission = denied("TERMINAL", "JOB_TERMINAL")


class Runner:
    def __init__(self, store: Store) -> None:
        self.store = store
        self.calls: list[SourceTarget] = []
        self.transient_targets: set[str] = set()
        self.always_transient = False
        self.results: list[str] = []
        self.wrong_field: str | None = None
        self.wait: asyncio.Event | None = None
        self.entered = asyncio.Event()
        self.unexpected = False

    async def run(self, target: SourceTarget) -> SourceExtractionResult:
        assert self.store.events[-1] == "take"
        self.calls.append(target)
        self.entered.set()
        if self.wait:
            await self.wait.wait()
        if self.unexpected:
            raise RuntimeError("synthetic parser failure")
        if self.always_transient or target.arxiv_id in self.transient_targets:
            self.transient_targets.discard(target.arxiv_id)
            raise SourceUpstreamTransientError(SourceUpstreamReason.RATE_LIMITED)
        status = self.results.pop(0) if self.results else "SUCCEEDED"
        success = status in {"SUCCEEDED", "PARTIALLY_SUCCEEDED"}
        result = SourceExtractionResult.model_validate(
            dict(
                paper_id=target.paper_id,
                arxiv_id=target.arxiv_id,
                parser_version="phase4-test",
                status=status,
                cleanup_confirmed=True,
                source_format="TEX" if success else None,
                files_inspected=1 if success else 0,
                error_code=None if success else "SOURCE_CONTENT_INVALID",
            )
        )
        if self.wrong_field:
            return result.model_copy(
                update={
                    self.wrong_field: uuid4()
                    if self.wrong_field == "paper_id"
                    else "2608.00999"
                    if self.wrong_field == "arxiv_id"
                    else "wrong"
                }
            )
        return result


def setup(
    count: int = 1, progress: SourceProgress | None = None, *, renew: float = 30, lease: float = 120
) -> tuple[ArxivCommandProcessor, Store, Publisher, Runner, bytes]:
    store = Store(progress)
    publisher, runner = Publisher(store), Runner(store)
    body = (
        MessageEnvelope[dict[str, object]](
            message_id=uuid4(),
            job_id=uuid4(),
            type=MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
            idempotency_key="source:test",
            trace_id="0123456789abcdef",
            payload={
                "parserVersion": "phase4-test",
                "targets": [
                    {"paperId": str(uuid4()), "arxivId": f"2608.{i:05d}"}
                    for i in range(1, count + 1)
                ],
            },
        )
        .model_dump_json(by_alias=True)
        .encode()
    )
    processor = ArxivCommandProcessor(
        FakeLegacyClient(),
        FakeOaiClient(),
        publisher,
        store,
        batch_size=50,
        source_runner=runner,
        source_store=store,
        source_invocation_lease_seconds=lease,
        source_invocation_renew_seconds=renew,
    )
    return processor, store, publisher, runner, body


async def bind(store: Store, body: bytes) -> SourceState:
    e = MessageEnvelope[dict[str, object]].model_validate_json(body)
    assert e.job_id
    return await store.get_source(
        SourceScope.from_command(e.idempotency_key, e.job_id, e.message_id, e.type, e.payload)
    )


@pytest.mark.asyncio
async def test_sixDistinctTransientTargetsDoNotExhaustBatch() -> None:
    p, s, pub, r, body = setup(100)
    r.transient_targets = {f"2608.{i:05d}" for i in range(1, 7)}
    for index in range(6):
        decision = await p.process(body)
        assert isinstance(decision, SourceRetryDecision)
        assert (decision.index, decision.attempt_number, decision.exhausted) == (index, 1, False)
    assert await p.process(body) == CommandOutcome.ACK
    assert len(r.calls) == 106
    assert len([m for m in pub.messages if m.type == MessageType.ARXIV_EXTRACTION_RESULT]) == 100
    assert s.current().progress == SourceProgress(100, 100, 0, 0)
    assert pub.messages[-1].payload.status == "SUCCEEDED"


@pytest.mark.asyncio
async def test_sameTargetStopsAfterSixInvocations() -> None:
    p, s, pub, r, body = setup()
    r.always_transient = True
    for attempt in range(1, 7):
        decision = await p.process(body)
        assert isinstance(decision, SourceRetryDecision)
        assert (decision.attempt_number, decision.exhausted) == (attempt, attempt == 6)
    assert await p.process(body) == decision
    assert isinstance(decision, SourceRetryDecision)
    await p.publish_retry_exhausted_failure(body, decision)
    assert await p.process(body) == CommandOutcome.ACK
    assert len(r.calls) == 6
    assert pub.messages[-1].payload.error_code == "SOURCE_UPSTREAM_RETRY_EXHAUSTED"
    assert (
        pub.messages[-1].payload.processed_count,
        pub.messages[-1].payload.failed_count,
        pub.messages[-1].payload.progress_percent,
    ) == (0, 0, 0)
    assert s.current().phase == SourcePhase.TERMINAL


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "statuses,terminal,counts",
    [
        (["SUCCEEDED", "SOURCE_UNAVAILABLE"], "SUCCEEDED", (1, 1, 0)),
        (["SUCCEEDED", "SECURITY_REJECTED"], "PARTIALLY_SUCCEEDED", (1, 0, 1)),
        (["FAILED", "SUCCEEDED"], "PARTIALLY_SUCCEEDED", (1, 0, 1)),
        (["FAILED", "FAILED"], "FAILED", (0, 0, 2)),
    ],
)
async def test_actual_result_counts_and_order(
    statuses: list[str], terminal: str, counts: tuple[int, int, int]
) -> None:
    p, s, pub, r, body = setup(2)
    r.results = statuses
    assert await p.process(body) == CommandOutcome.ACK
    payload = pub.messages[-1].payload
    assert (payload.status, payload.success_count, payload.skipped_count, payload.failed_count) == (
        terminal,
        *counts,
    )
    assert s.events[:7] == [
        "publish:ARXIV_JOB_STARTED",
        "reserve",
        "take",
        "stage",
        "publish:ARXIV_EXTRACTION_RESULT",
        "publish:ARXIV_JOB_PROGRESS",
        "advance",
    ]
    assert s.events[-4:] == ["publish:ARXIV_JOB_COMPLETED", "pending", "marker", "retire"]
    assert s.source_progress is None


@pytest.mark.asyncio
async def test_target_53_resume_uses_absolute_sequences_and_cumulative_counts() -> None:
    p, s, pub, r, body = setup(53, SourceProgress(52, 52, 0, 0))
    assert await p.process(body) == CommandOutcome.ACK
    assert [t.arxiv_id for t in r.calls] == ["2608.00053"]
    assert pub.messages[1].idempotency_key.endswith(":ARXIV_EXTRACTION_RESULT:105")
    assert pub.messages[2].idempotency_key.endswith(":ARXIV_JOB_PROGRESS:106")
    assert s.current().progress == SourceProgress(53, 53, 0, 0)


@pytest.mark.asyncio
@pytest.mark.parametrize("field", ["paper_id", "arxiv_id", "parser_version"])
@pytest.mark.parametrize("stored", [False, True])
async def test_wrong_target_mapping_never_publishes(field: str, stored: bool) -> None:
    p, s, pub, r, body = setup()
    r.wrong_field = field
    if stored:
        initial = await bind(s, body)
        original = await s.reserve_source_invocation(
            initial.scope,
            0,
            0,
            s.source_owner_id,
            datetime.now(UTC).replace(microsecond=0) + timedelta(seconds=120),
        )
        await s.take_source_invocation(initial.scope, original)
        e = MessageEnvelope[dict[str, object]].model_validate_json(body)
        from app.messaging.contracts import SourceExtractionCommand

        target = SourceExtractionCommand.model_validate(e.payload).targets[0]
        result = await r.run(target)
        assert original.invocation
        await s.stage_source_result(initial.scope, original.invocation.token, result)
        r.calls.clear()
    assert await p.process(body) == CommandOutcome.ACK
    assert not any(m.type == MessageType.ARXIV_EXTRACTION_RESULT for m in pub.messages)
    assert pub.messages[-1].payload.stage == "SOURCE_RECONCILIATION_REQUIRED"
    assert s.marked == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "kind", [MessageType.ARXIV_EXTRACTION_RESULT, MessageType.ARXIV_JOB_PROGRESS]
)
@pytest.mark.parametrize("accepted", [False, True])
async def test_publication_unknown_recovers_stored_result_without_runner(
    kind: MessageType, accepted: bool
) -> None:
    p, s, pub, r, body = setup()
    pub.fail, pub.accept_then_fail = kind, accepted
    with pytest.raises(RuntimeError):
        await p.process(body)
    assert s.current().phase == SourcePhase.RESULT_READY
    assert s.current().progress.next_index == 0
    pub.fail = None
    assert await p.process(body) == CommandOutcome.ACK
    assert len(r.calls) == 1
    assert s.current().progress.next_index == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("op", ["reserve", "take", "stage", "advance"])
@pytest.mark.parametrize("point", ["before", "after"])
async def test_unknown_transition_never_grants_extra_execution(op: str, point: str) -> None:
    p, s, pub, r, body = setup()
    s.faults[op] = point
    assert await p.process(body) == CommandOutcome.ACK
    assert len(r.calls) == (1 if op in {"stage", "advance"} else 0)
    if op in {"stage", "advance"} and point == "after":
        assert s.current().phase == SourcePhase.TERMINAL
    else:
        assert pub.messages[-1].payload.stage == "SOURCE_RECONCILIATION_REQUIRED"
        assert s.marked == []


@pytest.mark.asyncio
@pytest.mark.parametrize("expired", [False, True])
async def test_duplicate_reserved_never_adopts_original(expired: bool) -> None:
    p, s, _pub, r, body = setup()
    state = await bind(s, body)
    await s.reserve_source_invocation(
        state.scope,
        0,
        0,
        s.source_owner_id,
        datetime.now(UTC).replace(microsecond=0) + timedelta(seconds=-1 if expired else 120),
    )
    assert await p.process(body) == (CommandOutcome.ACK if expired else CommandOutcome.DEFER)
    assert r.calls == [] and s.marked == []


@pytest.mark.asyncio
@pytest.mark.parametrize("op", ["marker", "retire", "terminal"])
async def test_pending_retirement_precedes_marker_and_db_terminal(op: str) -> None:
    p, s, pub, r, body = setup()
    s.faults[op] = "after" if op == "terminal" else "before"
    with contextlib.suppress(SourceAdmissionDenied):
        await p.process(body)
    s.admission = denied("TERMINAL", "JOB_TERMINAL")
    count = len(pub.messages)
    assert await p.process(body) == CommandOutcome.ACK
    assert s.current().phase == SourcePhase.TERMINAL
    assert len(pub.messages) == count and len(r.calls) == 1


@pytest.mark.asyncio
async def test_terminal_publication_before_pending_unknown_respects_db_denial() -> None:
    p, s, pub, r, body = setup()
    s.faults["terminal"] = "before"
    pub.terminal_denial = True
    assert await p.process(body) == CommandOutcome.ACK
    assert s.current().phase == SourcePhase.READY
    count = len(pub.messages)
    assert await p.process(body) == CommandOutcome.ACK
    assert len(pub.messages) == count and len(r.calls) == 1


@pytest.mark.asyncio
async def test_marker_only_staged_residual_is_not_republished_or_discarded() -> None:
    p, s, pub, r, body = setup()
    pub.fail = MessageType.ARXIV_EXTRACTION_RESULT
    with pytest.raises(RuntimeError):
        await p.process(body)
    s.marked.append("source:test")
    pub.fail = None
    count = len(pub.messages)
    assert await p.process(body) == CommandOutcome.ACK
    assert [m.payload.stage for m in pub.messages[count:]] == ["SOURCE_RECONCILIATION_REQUIRED"]
    assert s.current().phase == SourcePhase.RESULT_READY and len(r.calls) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "status,code,outcome",
    [
        ("TERMINAL", "JOB_TERMINAL", CommandOutcome.ACK),
        ("PAUSED", "JOB_PAUSED", CommandOutcome.DEFER),
        ("HELD", "LEGACY_UNBOUND", CommandOutcome.ACK),
        ("HELD", "STATE_LOST", CommandOutcome.ACK),
        ("HELD", "CORRUPT_STATE", CommandOutcome.ACK),
    ],
)
async def test_admission_denials_never_start_or_run(
    status: str, code: str, outcome: CommandOutcome
) -> None:
    p, s, pub, r, body = setup()
    s.admission = denied(status, code)
    assert await p.process(body) == outcome
    assert r.calls == [] and s.marked == []
    assert not any(m.type == MessageType.ARXIV_JOB_STARTED for m in pub.messages)
    if status == "HELD":
        assert pub.messages[-1].payload.checkpoint == {}
    else:
        assert pub.messages == []


@pytest.mark.asyncio
async def test_unavailable_remains_generic_infrastructure() -> None:
    p, s, pub, r, body = setup()
    s.admission = denied("UNAVAILABLE", "COORDINATION_FAILED")
    with pytest.raises(SourceAdmissionDenied):
        await p.process(body)
    assert pub.messages == [] and r.calls == []


@pytest.mark.asyncio
async def test_scope_conflict_quarantines_incoming_without_original_events() -> None:
    p, s, pub, r, body = setup()
    original = await bind(s, body)
    incoming = (
        MessageEnvelope[dict[str, object]]
        .model_validate_json(body)
        .model_copy(update={"message_id": uuid4()})
        .model_dump_json(by_alias=True)
        .encode()
    )
    assert await p.process(incoming) == CommandOutcome.ACK
    assert await p.process(incoming) == CommandOutcome.ACK
    assert s.current() == original and pub.messages == [] and r.calls == []
    assert len(set(s.marked)) == 1 and s.marked[0] != "source:test"


@pytest.mark.asyncio
@pytest.mark.parametrize("control", ["PAUSE", "CANCEL"])
async def test_control_before_invocation_preserves_attempts(control: str) -> None:
    p, s, pub, r, body = setup()
    s.control = control
    assert await p.process(body) == (
        CommandOutcome.DEFER if control == "PAUSE" else CommandOutcome.ACK
    )
    assert r.calls == [] and "reserve" not in s.events
    assert pub.messages[-1].payload.status == ("PAUSED" if control == "PAUSE" else "CANCELED")


@pytest.mark.asyncio
@pytest.mark.parametrize("cancel", [False, True])
async def test_terminal_callback_drains_stored_result_without_next_target(cancel: bool) -> None:
    p, s, pub, r, body = setup(2)
    pub.fail = MessageType.ARXIV_EXTRACTION_RESULT
    with pytest.raises(RuntimeError):
        await p.process(body)
    pub.fail = None
    if cancel:
        s.control = "CANCEL"
        assert await p.process(body) == CommandOutcome.ACK
    else:
        await p.publish_retry_exhausted_failure(body)
    assert len(r.calls) == 1 and s.current().progress.next_index == 1
    assert pub.messages[-1].payload.processed_count == 1
    assert pub.messages[-1].payload.progress_percent == 50
    assert pub.messages[-1].payload.status == ("CANCELED" if cancel else "FAILED")
    if not cancel:
        assert pub.messages[-1].payload.error_code == "WORKER_RETRY_EXHAUSTED"


@pytest.mark.asyncio
async def test_terminal_callback_does_not_swallow_unresolved_drain() -> None:
    p, s, pub, _r, body = setup()
    pub.fail = MessageType.ARXIV_EXTRACTION_RESULT
    with pytest.raises(RuntimeError):
        await p.process(body)
    with pytest.raises(RuntimeError):
        await p.publish_retry_exhausted_failure(body)
    assert s.current().phase == SourcePhase.RESULT_READY and s.marked == []


@pytest.mark.asyncio
async def test_unexpected_runner_failure_does_not_become_source_retry() -> None:
    p, s, _pub, r, body = setup()
    r.unexpected = True
    with pytest.raises(RuntimeError):
        await p.process(body)
    assert s.current().phase == SourcePhase.RESERVED and "transient" not in s.events


@pytest.mark.asyncio
@pytest.mark.parametrize("transient", [False, True])
async def test_cooperative_stop_joins_inflight_renewal_before_stage(transient: bool) -> None:
    p, s, _pub, r, body = setup(renew=0.01, lease=1)
    r.wait, s.renew_release = asyncio.Event(), asyncio.Event()
    r.always_transient = transient
    task = asyncio.create_task(p.process(body))
    await asyncio.wait_for(s.renew_entered.wait(), 1)
    r.wait.set()
    await asyncio.sleep(0)
    assert "stage" not in s.events
    s.renew_release.set()
    outcome = await asyncio.wait_for(task, 1)
    if transient:
        assert isinstance(outcome, SourceRetryDecision) and outcome.attempt_number == 1
    else:
        assert outcome == CommandOutcome.ACK
    assert not s.renew_canceled
    assert s.events.index("renew") < s.events.index("transient" if transient else "stage")


@pytest.mark.asyncio
async def test_external_cancellation_joins_renewal_and_propagates() -> None:
    p, s, _pub, r, body = setup(renew=0.01, lease=1)
    r.wait, s.renew_release = asyncio.Event(), asyncio.Event()
    task = asyncio.create_task(p.process(body))
    await asyncio.wait_for(s.renew_entered.wait(), 1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    count = len(s.events)
    s.renew_release.set()
    await asyncio.sleep(0.03)
    assert len(s.events) == count and s.renew_canceled
    assert s.current().phase == SourcePhase.HELD and "stage" not in s.events


@pytest.mark.asyncio
async def test_db_cancellation_after_stage_wins_before_any_result_publication() -> None:
    p, s, pub, r, body = setup()
    s.deny_after_stage = True
    assert await p.process(body) == CommandOutcome.ACK
    assert [m.type for m in pub.messages] == [MessageType.ARXIV_JOB_STARTED]
    assert s.current().phase == SourcePhase.RESULT_READY
    assert s.marked == [] and len(r.calls) == 1


@pytest.mark.asyncio
async def test_generic_exhaustion_does_not_terminalize_strict_held_state() -> None:
    p, s, pub, r, body = setup()
    state = await bind(s, body)
    await s.hold_source(state.scope, state.generation, SourceHoldReason.LEGACY_UNBOUND)
    await p.publish_retry_exhausted_failure(body)
    assert [m.payload.stage for m in pub.messages] == ["SOURCE_RECONCILIATION_REQUIRED"]
    assert s.current().phase == SourcePhase.HELD and s.marked == [] and r.calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", [MessageType.ARXIV_JOB_COMPLETED, MessageType.ARXIV_JOB_PROGRESS])
async def test_terminal_or_retry_diagnostic_failure_keeps_durable_state(kind: MessageType) -> None:
    p, s, pub, r, body = setup()
    pub.fail = kind
    r.always_transient = kind == MessageType.ARXIV_JOB_PROGRESS
    with pytest.raises(RuntimeError):
        await p.process(body)
    assert s.marked == []
    if r.always_transient:
        assert s.current().phase == SourcePhase.RETRY_READY
        invocation = s.current().invocation
        assert invocation and invocation.attempt_number == 1
    else:
        assert s.current().phase == SourcePhase.READY
        assert s.current().progress == SourceProgress(1, 1, 0, 0)
    pub.fail = None
    outcome = await p.process(body)
    if r.always_transient:
        assert isinstance(outcome, SourceRetryDecision) and outcome.attempt_number == 2
    else:
        assert outcome == CommandOutcome.ACK and len(r.calls) == 1


@pytest.mark.asyncio
async def test_renewal_uncertainty_holds_without_staging_or_fabricated_transient() -> None:
    p, s, pub, r, body = setup(renew=0.01, lease=1)
    r.wait = asyncio.Event()
    s.faults["renew"] = "before"
    task = asyncio.create_task(p.process(body))
    await asyncio.wait_for(s.renew_entered.wait(), 1)
    r.wait.set()
    assert await task == CommandOutcome.ACK
    assert s.current().phase == SourcePhase.HELD
    assert "stage" not in s.events and "transient" not in s.events
    assert pub.messages[-1].payload.stage == "SOURCE_RECONCILIATION_REQUIRED"


@pytest.mark.parametrize("lease,renew", [(121, 30), (120, 0), (0, 1), (2, 1), (float("nan"), 1)])
def test_invalid_lease_settings_cannot_enable_invocation(lease: float, renew: float) -> None:
    with pytest.raises(ValueError, match="lease"):
        setup(lease=lease, renew=renew)


@pytest.mark.asyncio
async def test_source_exhaustion_callback_rejects_forged_decision() -> None:
    p, s, pub, r, body = setup()
    r.always_transient = True
    decision = await p.process(body)
    assert isinstance(decision, SourceRetryDecision)
    count = len(pub.messages)
    from app.jobs.source_retry import SourceStateError

    with pytest.raises(SourceStateError):
        await p.publish_retry_exhausted_failure(
            body, replace(decision, exhausted=True, attempt_number=6)
        )
    assert len(pub.messages) == count and s.marked == []


@pytest.mark.asyncio
async def test_cancel_staged_result_with_db_denial_retains_exceptional_residual() -> None:
    p, s, pub, r, body = setup()
    pub.fail = MessageType.ARXIV_EXTRACTION_RESULT
    with pytest.raises(RuntimeError):
        await p.process(body)
    pub.fail, s.control, s.admission = None, "CANCEL", denied("TERMINAL", "JOB_TERMINAL")
    count = len(pub.messages)
    assert await p.process(body) == CommandOutcome.ACK
    assert len(pub.messages) == count and s.current().phase == SourcePhase.RESULT_READY
    assert len(r.calls) == 1 and s.marked == []


@pytest.mark.asyncio
async def test_invalid_source_callback_cannot_mutate_original_history() -> None:
    p, s, pub, r, body = setup()
    await bind(s, body)
    invalid = (
        MessageEnvelope[dict[str, object]]
        .model_validate_json(body)
        .model_copy(update={"payload": {"targets": [], "parserVersion": "phase4-test"}})
    )
    await p.publish_permanent_failure(invalid.model_dump_json(by_alias=True).encode())
    assert pub.messages == [] and s.marked == [] and r.calls == []


@pytest.mark.asyncio
async def test_valid_source_permanent_callback_drains_before_terminal_and_retires() -> None:
    p, s, pub, r, body = setup(2)
    pub.fail = MessageType.ARXIV_EXTRACTION_RESULT
    with pytest.raises(RuntimeError):
        await p.process(body)
    pub.fail = None
    await p.publish_permanent_failure(body)
    assert len(r.calls) == 1 and s.current().phase == SourcePhase.TERMINAL
    assert pub.messages[-1].payload.error_code == "WORKER_COMMAND_INVALID"
    assert pub.messages[-1].payload.processed_count == 1


@pytest.mark.asyncio
async def test_terminal_cleanup_does_not_require_configured_runner() -> None:
    p, s, pub, r, body = setup()
    assert await p.process(body) == CommandOutcome.ACK
    s.marked.clear()  # generic TTL is not execution authority
    count = len(pub.messages)
    no_runner = ArxivCommandProcessor(
        FakeLegacyClient(), FakeOaiClient(), pub, s, batch_size=50, source_store=s
    )
    assert await no_runner.process(body) == CommandOutcome.ACK
    assert len(pub.messages) == count and len(r.calls) == 1


@pytest.mark.asyncio
async def test_missing_runner_is_configuration_failure_not_invalid_command() -> None:
    _, s, pub, r, body = setup()
    no_runner = ArxivCommandProcessor(
        FakeLegacyClient(), FakeOaiClient(), pub, s, batch_size=50, source_store=s
    )
    with pytest.raises(RuntimeError, match="runner"):
        await no_runner.process(body)
    assert pub.messages == [] and s.marked == [] and r.calls == []


@pytest.mark.asyncio
@pytest.mark.parametrize("point", ["before", "after"])
async def test_generic_terminal_callback_requires_actual_advance(point: str) -> None:
    p, s, pub, r, body = setup(2)
    pub.fail = MessageType.ARXIV_EXTRACTION_RESULT
    with pytest.raises(RuntimeError):
        await p.process(body)
    pub.fail, s.faults["advance"] = None, point
    from app.jobs.source_retry import SourceStateError

    if point == "before":
        with pytest.raises(SourceStateError):
            await p.publish_retry_exhausted_failure(body)
        assert s.current().phase == SourcePhase.RESULT_READY and s.marked == []
        assert not any(m.type == MessageType.ARXIV_JOB_FAILED for m in pub.messages)
    else:
        await p.publish_retry_exhausted_failure(body)
        assert s.current().phase == SourcePhase.TERMINAL
        assert pub.messages[-1].payload.processed_count == 1
    assert len(r.calls) == 1


@pytest.mark.asyncio
async def test_expired_result_ready_is_publication_only_recovery() -> None:
    p, s, pub, r, body = setup()
    pub.fail = MessageType.ARXIV_EXTRACTION_RESULT
    with pytest.raises(RuntimeError):
        await p.process(body)
    state = s.current()
    assert state.invocation
    s.write(
        replace(
            state, invocation=replace(state.invocation, deadline=datetime(2020, 1, 1, tzinfo=UTC))
        )
    )
    pub.fail = None
    assert await p.process(body) == CommandOutcome.ACK
    assert len(r.calls) == 1
    assert pub.messages[-1].payload.success_count == 1


@pytest.mark.asyncio
async def test_legacy_progress_is_not_deleted_or_used_to_replay() -> None:
    p, s, pub, r, body = setup()
    legacy = SourceProgress(1, 1, 0, 0)
    s.source_progress = legacy
    s.admission = denied(code="LEGACY_UNBOUND")
    assert await p.process(body) == CommandOutcome.ACK
    assert s.source_progress is legacy and r.calls == [] and s.marked == []
    assert pub.messages[-1].payload.checkpoint == {}


@pytest.mark.asyncio
async def test_out_of_range_prefix_holds_without_inventing_tail() -> None:
    p, s, pub, r, body = setup(1, SourceProgress(2, 2, 0, 0))
    assert await p.process(body) == CommandOutcome.ACK
    assert pub.messages[-1].payload.checkpoint == {}
    assert s.current().progress.next_index == 2 and r.calls == [] and s.marked == []


@pytest.mark.asyncio
async def test_generic_callback_pause_remains_unsettled() -> None:
    p, s, pub, r, body = setup()
    s.admission = denied("PAUSED", "JOB_PAUSED")
    with pytest.raises(SourceAdmissionDenied):
        await p.publish_retry_exhausted_failure(body)
    assert pub.messages == [] and r.calls == [] and s.marked == []


@pytest.mark.asyncio
async def test_stalled_renewal_is_bounded_and_cannot_stage_after_timeout() -> None:
    p, s, pub, r, body = setup(renew=0.01, lease=1)
    r.wait, s.renew_release = asyncio.Event(), asyncio.Event()
    task = asyncio.create_task(p.process(body))
    await asyncio.wait_for(s.renew_entered.wait(), 1)
    r.wait.set()
    assert await asyncio.wait_for(task, 0.2) == CommandOutcome.ACK
    assert s.renew_canceled and s.current().phase == SourcePhase.HELD
    assert "stage" not in s.events and "transient" not in s.events
    assert pub.messages[-1].payload.stage == "SOURCE_RECONCILIATION_REQUIRED"


@pytest.mark.asyncio
async def test_cancel_before_first_renewal_closes_owned_reservation() -> None:
    p, s, pub, r, body = setup()
    r.wait = asyncio.Event()
    task = asyncio.create_task(p.process(body))
    await asyncio.wait_for(r.entered.wait(), 1)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not s.active and s.current().phase == SourcePhase.HELD
    assert not s.renew_entered.is_set()
    assert [m.type for m in pub.messages] == [MessageType.ARXIV_JOB_STARTED]


@pytest.mark.asyncio
async def test_fractional_lease_does_not_round_to_a_different_second() -> None:
    p, s, _, _, body = setup(lease=0.3, renew=0.05)
    before = datetime.now(UTC)
    assert await p.process(body) == CommandOutcome.ACK
    after = datetime.now(UTC)
    assert before + timedelta(seconds=0.299) <= s.deadlines[0] <= after + timedelta(seconds=0.3)


@pytest.mark.asyncio
async def test_generic_callback_initial_unbound_denial_confirms_admin_hold() -> None:
    p, s, pub, r, body = setup()
    s.admission = denied(code="LEGACY_UNBOUND")
    await p.publish_retry_exhausted_failure(body)
    assert pub.messages[-1].payload.stage == "SOURCE_RECONCILIATION_REQUIRED"
    assert pub.messages[-1].payload.checkpoint == {}
    assert s.marked == [] and r.calls == []
