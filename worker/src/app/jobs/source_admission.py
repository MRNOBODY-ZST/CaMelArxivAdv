"""Internal mailbox transport with non-reconstructable, one-shot original requests."""

from __future__ import annotations

import asyncio
import hashlib
import time
from contextlib import suppress
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID, uuid4

from redis.exceptions import RedisError

from app.arxiv.source_errors import SourceUpstreamReason
from app.jobs.job_control import AsyncRedisStore, SourceProgress
from app.jobs.source_retry import (
    TERMINALS,
    SourceAuthorityReceipt,
    SourceHoldReason,
    SourcePhase,
    SourceScope,
    SourceState,
    SourceStateError,
    canonical_json,
    decode_json,
    digest_value,
    fields,
    integer,
    parse_time,
    require,
    timestamp,
    uuid_value,
)
from app.messaging.contracts import SourceExtractionResult

CODES = frozenset(
    {
        "OK",
        "AMBIENT_TRANSACTION",
        "REQUEST_EXPIRED",
        "COORDINATION_FAILED",
        "SCOPE_CONFLICT",
        "JOB_TERMINAL",
        "JOB_PAUSED",
        "JOB_NOT_ACTIVE",
        "LEGACY_UNBOUND",
        "UNKNOWN_IDENTITY",
        "UNKNOWN_NONCE",
        "INVOCATION_UNCERTAIN",
        "CORRUPT_STATE",
        "STATE_LOST",
        "PREDECESSOR_CONFLICT",
        "INDEX_CONFLICT",
        "INVALID_LEASE",
        "BURN_PENDING",
        "CAS_CONFLICT",
    }
)
STATUSES = frozenset({"BOUND", "RESERVED", "TERMINAL", "PAUSED", "HELD", "UNAVAILABLE"})
_ENQUEUE = "return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])"
_DELETE_FRAME = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
"""


def now_millis() -> datetime:
    now = datetime.now(UTC)
    return now.replace(microsecond=now.microsecond // 1000 * 1000)


@dataclass(frozen=True, slots=True, repr=False)
class AdmissionRequest:
    operation: str
    scope: SourceScope
    nonce: UUID
    owner_id: UUID
    expires_at: datetime
    expected_generation: int
    expected_state_digest: str | None
    predecessor_sequence: int
    index: int | None = None
    attempt_number: int | None = None
    token: UUID | None = None
    deadline: datetime | None = None

    def to_json(self) -> str:
        tree: dict[str, Any] = {
            "operation": self.operation,
            "scope": self.scope.to_dict(),
            "nonce": str(self.nonce),
            "owner_id": str(self.owner_id),
            "expires_at": timestamp(self.expires_at),
            "expected_generation": self.expected_generation,
            "expected_state_digest": self.expected_state_digest,
            "predecessor_sequence": self.predecessor_sequence,
        }
        if self.index is not None:
            require(self.deadline is not None)
            assert self.deadline is not None
            tree.update(
                index=self.index,
                attempt_number=self.attempt_number,
                token=str(self.token),
                deadline=timestamp(self.deadline),
            )
        return canonical_json(tree)

    def readback(self) -> AdmissionRequest:
        """Wire-only diagnostic value; cannot be submitted as a new live original."""
        return replace(self, operation="READBACK")

    @classmethod
    def from_json(cls, raw: bytes | str) -> AdmissionRequest:
        try:
            n = decode_json(raw, 4096)
            require(isinstance(n, dict))
            invocation = any(k in n for k in ("index", "attempt_number", "token", "deadline"))
            fields(
                n,
                "operation scope nonce owner_id expires_at expected_generation "
                "expected_state_digest predecessor_sequence"
                + (" index attempt_number token deadline" if invocation else ""),
            )
            require(n["operation"] in {"BIND", "RESERVE", "READBACK"})
            require(n["operation"] != "BIND" or not invocation)
            require(n["operation"] != "RESERVE" or invocation)
            digest = (
                digest_value(n["expected_state_digest"])
                if n["expected_state_digest"] is not None
                else None
            )
            predecessor = integer(n["predecessor_sequence"])
            if invocation:
                require(digest is not None and predecessor > 0)
                require(1 <= integer(n["attempt_number"]) <= 6)
            return cls(
                n["operation"],
                SourceScope.from_dict(n["scope"]),
                uuid_value(n["nonce"]),
                uuid_value(n["owner_id"]),
                parse_time(n["expires_at"]),
                integer(n["expected_generation"]),
                digest,
                predecessor,
                integer(n["index"]) if invocation else None,
                n["attempt_number"] if invocation else None,
                uuid_value(n["token"]) if invocation else None,
                parse_time(n["deadline"]) if invocation else None,
            )
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
            pass
        raise SourceStateError()


@dataclass(frozen=True, slots=True, repr=False)
class AdmissionReply:
    status: str
    code: str
    state_digest: str | None = None
    receipt: SourceAuthorityReceipt | None = None
    issued_deadline: datetime | None = None
    terminal_status: str | None = None

    def to_json(self) -> str:
        return canonical_json(
            {
                "status": self.status,
                "code": self.code,
                "state_digest": self.state_digest,
                "receipt": self.receipt.to_dict() if self.receipt else None,
                "issued_deadline": timestamp(self.issued_deadline)
                if self.issued_deadline
                else None,
                "terminal_status": self.terminal_status,
            }
        )

    @classmethod
    def from_json(cls, raw: bytes | str) -> AdmissionReply:
        try:
            n = fields(
                decode_json(raw, 4096),
                "status code state_digest receipt issued_deadline terminal_status",
            )
            require(n["status"] in STATUSES and n["code"] in CODES)
            receipt = (
                SourceAuthorityReceipt.from_dict(n["receipt"]) if n["receipt"] is not None else None
            )
            deadline = (
                parse_time(n["issued_deadline"]) if n["issued_deadline"] is not None else None
            )
            digest = digest_value(n["state_digest"]) if n["state_digest"] is not None else None
            if n["status"] in {"BOUND", "RESERVED"}:
                require(
                    digest is not None
                    and receipt is not None
                    and n["code"] == "OK"
                    and n["terminal_status"] is None
                )
                if n["status"] == "RESERVED":
                    assert receipt is not None
                    require(receipt.token is not None and deadline is not None)
            else:
                require(digest is None and receipt is None and deadline is None)
                require(
                    n["terminal_status"] in TERMINALS
                    if n["status"] == "TERMINAL"
                    else n["terminal_status"] is None
                )
            return cls(n["status"], n["code"], digest, receipt, deadline, n["terminal_status"])
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
            pass
        raise SourceStateError()


class SourceAdmissionDenied(Exception):
    """Typed non-executing response. Never includes request, result, or backend causes."""

    def __init__(self, reply: AdmissionReply) -> None:
        self.status = reply.status
        self.code = reply.code
        self.terminal_status = reply.terminal_status
        super().__init__("SOURCE_ADMISSION_DENIED")


class SourceAdmissionClient:
    """One fresh owner per runtime. Prepared originals are accepted once by object identity.

    request() never reconstructs an original, retries a claimed frame, or uses READBACK
    for execution. Even the returned RESERVED reply is insufficient for a caller to run:
    RedisJobStore.take_source_invocation performs the final one-shot state verification.
    """

    def __init__(self, redis: AsyncRedisStore) -> None:
        self._redis = redis
        self._owner_id = uuid4()
        self._wall_floor = now_millis()
        self._clock_lost = False
        self._pending: dict[UUID, tuple[AdmissionRequest, float, datetime]] = {}

    @property
    def owner_id(self) -> UUID:
        return self._owner_id

    def _now(self) -> datetime:
        observed = now_millis()
        if self._clock_lost or observed < self._wall_floor:
            self._clock_lost = True
            self._pending.clear()
            raise SourceStateError()
        self._wall_floor = observed
        return observed

    def prepare(
        self,
        scope: SourceScope,
        *,
        state: SourceState | None = None,
        index: int | None = None,
        deadline: datetime | None = None,
    ) -> AdmissionRequest:
        created = self._now()
        monotonic = time.monotonic()
        self._pending = {n: item for n, item in self._pending.items() if item[1] > monotonic}
        require(len(self._pending) < 64)
        if index is not None:
            require(state is not None and state.scope == scope and state.authority is not None)
            assert state is not None and state.authority is not None
            require(state.phase in {"READY", "RETRY_READY"} and index == state.progress.next_index)
            require(deadline is not None and created < deadline <= created + timedelta(seconds=120))
            attempt = state.invocation.attempt_number + 1 if state.invocation else 1
            request = AdmissionRequest(
                "RESERVE",
                scope,
                uuid4(),
                self.owner_id,
                created + timedelta(seconds=10),
                state.generation,
                state.digest,
                state.authority.sequence,
                index,
                attempt,
                uuid4(),
                deadline,
            )
        else:
            request = AdmissionRequest(
                "BIND", scope, uuid4(), self.owner_id, created + timedelta(seconds=10), 0, None, 0
            )
        AdmissionRequest.from_json(request.to_json())
        self._pending[request.nonce] = (request, monotonic + 10, created)
        return request

    @staticmethod
    def slot_key(nonce: UUID) -> str:
        slot = int.from_bytes(hashlib.sha256(str(nonce).encode()).digest()[:4], "big") % 64
        return f"camel:worker:source-admission:{{source-admission}}:slot:{slot}"

    @staticmethod
    def reply_key(nonce: UUID) -> str:
        return f"camel:worker:source-admission:{{source-admission}}:reply:{nonce}"

    async def request(self, request: AdmissionRequest) -> AdmissionReply:
        pending = self._pending.pop(request.nonce, None)
        if pending is None or pending[0] is not request or request.owner_id != self.owner_id:
            return AdmissionReply("HELD", "INVOCATION_UNCERTAIN")
        _, end, created = pending
        frame = request.to_json()
        slot = self.slot_key(request.nonce)
        try:
            async with asyncio.timeout(max(0, end - time.monotonic())):
                # A reply predating our enqueue cannot be the original live response.
                if await self._redis.get(self.reply_key(request.nonce)) is not None:
                    return AdmissionReply("HELD", "INVOCATION_UNCERTAIN")
                while time.monotonic() < end:
                    now = self._now()
                    if now < created or now >= request.expires_at:
                        return AdmissionReply("UNAVAILABLE", "REQUEST_EXPIRED")
                    remaining = min(
                        end - time.monotonic(), (request.expires_at - now).total_seconds()
                    )
                    if await self._redis.eval(
                        _ENQUEUE, 1, slot, frame, max(1, int(remaining * 1000))
                    ):
                        break
                    await asyncio.sleep(0.025)
                while time.monotonic() < end:
                    raw = await self._redis.get(self.reply_key(request.nonce))
                    now = self._now()
                    if now < created or now >= request.expires_at:
                        return AdmissionReply("UNAVAILABLE", "REQUEST_EXPIRED")
                    if raw is not None:
                        reply = AdmissionReply.from_json(raw)
                        if reply.status == "RESERVED" and not await self._matches_original(
                            request, reply
                        ):
                            return AdmissionReply("HELD", "INVOCATION_UNCERTAIN")
                        return reply
                    await asyncio.sleep(0.025)
        except (RedisError, TimeoutError, SourceStateError):
            pass
        finally:
            # Original lifetime is never extended; deletion cannot remove a later frame.
            try:
                async with asyncio.timeout(0.25):
                    await self._redis.eval(_DELETE_FRAME, 1, slot, frame)
            except (RedisError, TimeoutError):
                pass
        return AdmissionReply("UNAVAILABLE", "COORDINATION_FAILED")

    async def _matches_original(self, request: AdmissionRequest, reply: AdmissionReply) -> bool:
        raw = await self._redis.get(request.scope.state_key)
        if raw is None:
            return False
        state = SourceState.from_json(raw)
        observed = self._now()
        receipt, invocation = state.authority, state.invocation
        return (
            request.operation == "RESERVE"
            and state.scope == request.scope
            and state.phase == "RESERVED"
            and state.digest == reply.state_digest
            and receipt == reply.receipt
            and receipt is not None
            and invocation is not None
            and receipt.owner_id == self.owner_id
            and receipt.request_nonce == request.nonce
            and receipt.token == request.token
            and receipt.predecessor_sequence == request.predecessor_sequence
            and receipt.reserved_generation == request.expected_generation + 1
            and state.generation == request.expected_generation + 2
            and invocation.index == request.index
            and invocation.attempt_number == request.attempt_number
            and invocation.deadline == request.deadline == reply.issued_deadline
            and observed < invocation.deadline
            and observed < request.expires_at
        )


_STATE_CAS = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
if tonumber(ARGV[3]) > 0 then
  local clock = redis.call('TIME')
  local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
  if now >= tonumber(ARGV[3]) or now < tonumber(ARGV[4]) then return 0 end
end
redis.call('SET', KEYS[1], ARGV[2])
return 1
"""
_SERVER_TIME = """
local clock = redis.call('TIME')
return tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
"""


@dataclass(frozen=True, slots=True, repr=False)
class _RuntimeInvocation:
    scope: SourceScope
    digest: str
    lease_end: float
    observed_at: datetime
    accept_until: float | None = None
    accept_wall_until: datetime | None = None


class SourceStateStore:
    """Pure durable state operations, deliberately unconnected to the worker consumer.

    Public get/load always consult DB admission. Local retirement is the exception:
    TERMINAL_PENDING already records an actual prefix and must retire even when the
    backend is terminal/unavailable. It cannot grant invocation/publication authority.
    """

    def __init__(self, redis: AsyncRedisStore) -> None:
        self.redis = redis
        self.admission = SourceAdmissionClient(redis)
        self._grants: dict[UUID, tuple[SourceState, _RuntimeInvocation]] = {}
        self._active: dict[UUID, _RuntimeInvocation] = {}

    def _deny(self, code: str = "INVOCATION_UNCERTAIN") -> SourceAdmissionDenied:
        return SourceAdmissionDenied(AdmissionReply("HELD", code))

    def _now(self) -> datetime:
        try:
            return self.admission._now()
        except SourceStateError:
            pass
        raise self._deny()

    async def _read(self, scope: SourceScope) -> SourceState:
        failure = AdmissionReply("HELD", "CORRUPT_STATE")
        try:
            raw = await self.redis.get(scope.state_key)
            if raw is None:
                raise self._deny("STATE_LOST")
            state = SourceState.from_json(raw)
            if state.scope != scope:
                raise self._deny("SCOPE_CONFLICT")
            return state
        except RedisError:
            failure = AdmissionReply("UNAVAILABLE", "COORDINATION_FAILED")
        except SourceStateError:
            pass
        raise SourceAdmissionDenied(failure)

    async def load_or_bind(self, scope: SourceScope) -> SourceState:
        request = None
        with suppress(SourceStateError):
            request = self.admission.prepare(scope)
        if request is None:
            raise self._deny()
        reply = await self.admission.request(request)
        if reply.status != "BOUND":
            if reply.status == "HELD" and reply.code == "LEGACY_UNBOUND":
                await self._remember_legacy(scope)
            raise SourceAdmissionDenied(reply)
        state = await self._read(scope)
        if state.digest != reply.state_digest or state.authority != reply.receipt:
            raise self._deny()
        return state

    async def get_retirement(self, scope: SourceScope) -> SourceState | None:
        """Inspect only a non-executing tombstone; never returns results/runner state."""
        try:
            state = await self._read(scope)
        except SourceAdmissionDenied as denied:
            if denied.code == "STATE_LOST":
                return None
            raise
        if state.phase not in {SourcePhase.TERMINAL_PENDING, SourcePhase.TERMINAL}:
            return None
        if state.authority is None:
            raise self._deny()
        return state

    async def _remember_legacy(self, scope: SourceScope) -> None:
        """First-observed forensic digest, never an atomic snapshot or admission source."""
        try:
            raw = await self.redis.get("camel:worker:source-progress:" + scope.idempotency_key)
            if raw is not None:
                data = raw.encode("utf-8", "strict") if isinstance(raw, str) else raw
                await self.redis.eval(
                    "return redis.call('SET', KEYS[1], ARGV[1], 'NX')",
                    1,
                    scope.state_key + ":legacy-sha256",
                    hashlib.sha256(data).hexdigest(),
                )
        except (RedisError, UnicodeError):
            # Forensic availability cannot manufacture or upgrade authority.
            pass

    async def reserve(
        self,
        scope: SourceScope,
        expected_generation: int,
        index: int,
        owner_id: UUID,
        deadline: datetime,
    ) -> SourceState:
        if owner_id != self.admission.owner_id:
            raise self._deny()
        self._now()
        state = await self._read(scope)
        self._now()
        if (
            state.generation != expected_generation
            or state.progress.next_index != index
            or state.phase not in {SourcePhase.READY, SourcePhase.RETRY_READY}
            or state.authority is None
        ):
            raise self._deny("PREDECESSOR_CONFLICT")
        started = time.monotonic()
        request = self.admission.prepare(scope, state=state, index=index, deadline=deadline)
        reply = await self.admission.request(request)
        if reply.status != "RESERVED":
            raise SourceAdmissionDenied(reply)
        self._now()
        confirmed = await self._read(scope)
        invocation = confirmed.invocation
        if confirmed.digest != reply.state_digest or invocation is None:
            raise self._deny()
        observed = self._now()
        if observed >= invocation.deadline or observed >= request.expires_at:
            raise self._deny()
        grant = _RuntimeInvocation(
            scope,
            confirmed.digest,
            min(
                started
                + (
                    invocation.deadline - (request.expires_at - timedelta(seconds=10))
                ).total_seconds(),
                time.monotonic() + (invocation.deadline - observed).total_seconds(),
            ),
            observed,
            started + 10,
            request.expires_at,
        )
        self._grants[invocation.token] = (confirmed, grant)
        return confirmed

    async def take(self, scope: SourceScope, original_reservation: SourceState) -> bool:
        """Only the original reserve continuation object can consume the runtime grant.

        Equal decoded/get_source/duplicate states are deliberately not capabilities.
        No await or other work may separate a true return from the caller's runner.run.
        """
        if (
            not isinstance(original_reservation, SourceState)
            or original_reservation.invocation is None
        ):
            return False
        token = original_reservation.invocation.token
        pending = self._grants.get(token)
        if pending is None or pending[0] is not original_reservation or pending[1].scope != scope:
            return False
        _, grant = self._grants.pop(token)
        try:
            self._now()
            state = await self._read(scope)
            if not self._live(state, token, grant):
                return False
            self._active[token] = replace(grant, accept_until=None, accept_wall_until=None)
            return True
        except SourceAdmissionDenied:
            return False

    def _live(self, state: SourceState, token: UUID, grant: _RuntimeInvocation) -> bool:
        invocation = state.invocation
        now = self._now()
        return (
            state.phase == SourcePhase.RESERVED
            and state.digest == grant.digest
            and state.scope == grant.scope
            and invocation is not None
            and invocation.owner_id == self.admission.owner_id
            and invocation.token == token
            and grant.observed_at <= now < invocation.deadline
            and time.monotonic() < grant.lease_end
            and (grant.accept_until is None or time.monotonic() < grant.accept_until)
            and (grant.accept_wall_until is None or now < grant.accept_wall_until)
        )

    async def _owned(self, scope: SourceScope, token: UUID) -> SourceState:
        self._now()
        state = await self._read(scope)
        grant = self._active.get(token)
        if grant is None or not self._live(state, token, grant):
            self._active.pop(token, None)
            raise self._deny()
        return state

    async def _cas(self, previous: SourceState, next_state: SourceState) -> SourceState:
        # Revalidate immutable DTOs and mutable nested result models at the write boundary.
        raw = next_state.to_json()
        validated = SourceState.from_json(raw)
        owned = previous.phase == SourcePhase.RESERVED and next_state.phase in {
            SourcePhase.RESERVED,
            SourcePhase.RESULT_READY,
            SourcePhase.RETRY_READY,
            SourcePhase.EXHAUSTED,
        }
        failure = AdmissionReply("HELD", "CAS_CONFLICT")
        try:
            deadline, server_sample = 0, 0
            timeout: float | None = None
            if owned:
                assert previous.invocation is not None
                token = previous.invocation.token
                grant = self._active.get(token)
                if grant is None or not self._live(previous, token, grant):
                    raise self._deny()
                timeout = grant.lease_end - time.monotonic()
            async with asyncio.timeout(timeout):
                if owned:
                    assert previous.invocation is not None and grant is not None
                    # Redis and the worker need not have identical wall-clock offsets.
                    # Map only the remaining (already shrinking) lease into Redis's clock;
                    # a backward Redis jump after this sample is denied inside the CAS.
                    server_sample = integer(
                        await self.redis.eval(_SERVER_TIME, 1, previous.scope.state_key)
                    )
                    if not self._live(previous, token, grant):
                        raise self._deny()
                    remaining = min(
                        grant.lease_end - time.monotonic(),
                        (previous.invocation.deadline - self._now()).total_seconds(),
                    )
                    deadline = server_sample + max(0, int(remaining * 1000))
                changed = await self.redis.eval(
                    _STATE_CAS,
                    1,
                    previous.scope.state_key,
                    previous.to_json(),
                    raw,
                    deadline,
                    server_sample,
                )
                if owned:
                    self._now()
            if changed == 1:
                return validated
        except (RedisError, TimeoutError):
            failure = AdmissionReply("UNAVAILABLE", "COORDINATION_FAILED")
        raise SourceAdmissionDenied(failure)

    async def renew(self, scope: SourceScope, token: UUID, deadline: datetime) -> bool:
        try:
            created = self._now()
            started = time.monotonic()
            state = await self._owned(scope, token)
            now = self._now()
            invocation = state.invocation
            assert invocation is not None
            if not invocation.deadline < deadline <= created + timedelta(seconds=120):
                self._active.pop(token, None)
                return False
            next_state = await self._cas(
                state,
                replace(
                    state,
                    generation=state.generation + 1,
                    invocation=replace(invocation, deadline=deadline),
                ),
            )
            now = self._now()
            if now >= deadline:
                self._active.pop(token, None)
                return False
            self._active[token] = _RuntimeInvocation(
                scope,
                next_state.digest,
                min(
                    started + (deadline - created).total_seconds(),
                    time.monotonic() + (deadline - now).total_seconds(),
                ),
                now,
            )
            return True
        except (SourceAdmissionDenied, SourceStateError):
            self._active.pop(token, None)
            return False
        except asyncio.CancelledError:
            self._active.pop(token, None)
            raise

    async def stage(
        self, scope: SourceScope, token: UUID, result: SourceExtractionResult
    ) -> SourceState:
        try:
            state = await self._owned(scope, token)
            return await self._cas(
                state,
                replace(
                    state,
                    generation=state.generation + 1,
                    phase=SourcePhase.RESULT_READY,
                    result=result,
                ),
            )
        finally:
            self._active.pop(token, None)

    async def transient(
        self, scope: SourceScope, token: UUID, reason: SourceUpstreamReason
    ) -> SourceState:
        require(isinstance(reason, SourceUpstreamReason))
        try:
            state = await self._owned(scope, token)
            assert state.invocation is not None
            phase = (
                SourcePhase.EXHAUSTED
                if state.invocation.attempt_number == 6
                else SourcePhase.RETRY_READY
            )
            return await self._cas(
                state, replace(state, generation=state.generation + 1, phase=phase, reason=reason)
            )
        finally:
            self._active.pop(token, None)

    async def hold(
        self, scope: SourceScope, expected_generation: int, reason: SourceHoldReason
    ) -> SourceState:
        require(isinstance(reason, SourceHoldReason))
        state = await self._read(scope)
        if state.generation != expected_generation or state.phase in {
            SourcePhase.RESULT_READY,
            SourcePhase.TERMINAL_PENDING,
            SourcePhase.TERMINAL,
        }:
            raise self._deny("PREDECESSOR_CONFLICT")
        if state.invocation:
            self._active.pop(state.invocation.token, None)
            self._grants.pop(state.invocation.token, None)
        return await self._cas(
            state,
            replace(state, generation=state.generation + 1, phase=SourcePhase.HELD, reason=reason),
        )

    async def advance(
        self, scope: SourceScope, expected_generation: int, index: int
    ) -> SourceState:
        """Caller must have both result and progress publication confirmations before this CAS."""
        state = await self.load_or_bind(scope)
        if (
            state.generation != expected_generation
            or state.phase != SourcePhase.RESULT_READY
            or state.progress.next_index != index
            or state.result is None
        ):
            raise self._deny("PREDECESSOR_CONFLICT")
        p, status = state.progress, state.result.status
        success = status in {"SUCCEEDED", "PARTIALLY_SUCCEEDED"}
        skipped = status == "SOURCE_UNAVAILABLE"
        progress = SourceProgress(
            p.next_index + 1,
            p.success + int(success),
            p.skipped + int(skipped),
            p.failed + int(not success and not skipped),
        )
        return await self._cas(
            state,
            replace(
                state,
                generation=state.generation + 1,
                phase=SourcePhase.READY,
                invocation=None,
                result=None,
                progress=progress,
            ),
        )

    async def terminal(
        self, scope: SourceScope, expected_generation: int, status: str
    ) -> SourceState:
        require(status in TERMINALS)
        state = await self._read(scope)
        if state.generation != expected_generation or state.authority is None:
            raise self._deny("PREDECESSOR_CONFLICT")
        if state.phase in {SourcePhase.TERMINAL_PENDING, SourcePhase.TERMINAL}:
            if state.terminal_status != status:
                raise self._deny("PREDECESSOR_CONFLICT")
            return state
        # Never discard a result awaiting publication, even when DB terminal wins admission.
        if state.phase == SourcePhase.RESULT_READY:
            raise self._deny("PREDECESSOR_CONFLICT")
        if state.invocation:
            self._grants.pop(state.invocation.token, None)
            self._active.pop(state.invocation.token, None)
        return await self._cas(
            state,
            replace(
                state,
                generation=state.generation + 1,
                phase=SourcePhase.TERMINAL_PENDING,
                invocation=None,
                result=None,
                reason=None,
                terminal_status=status,
            ),
        )

    async def retire(self, scope: SourceScope, expected_generation: int) -> SourceState:
        state = await self._read(scope)
        if state.generation != expected_generation or state.authority is None:
            raise self._deny("PREDECESSOR_CONFLICT")
        if state.phase == SourcePhase.TERMINAL:
            return state
        if state.phase != SourcePhase.TERMINAL_PENDING:
            raise self._deny("PREDECESSOR_CONFLICT")
        return await self._cas(
            state, replace(state, generation=state.generation + 1, phase=SourcePhase.TERMINAL)
        )
