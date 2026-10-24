"""Strict, contact-safe source checkpoint values. None of these values grants execution."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any
from uuid import UUID

from app.arxiv.source_errors import SourceUpstreamReason
from app.jobs.job_control import SourceProgress
from app.jobs.source_command_identity import fingerprint
from app.messaging.contracts import MessageType, SourceExtractionResult
from app.source_safety import unsafe_bounded_text

MAX_INTEGER = 2**53 - 1
TERMINALS = frozenset({"SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELED"})
SOURCE_TYPES = frozenset(
    {
        MessageType.ARXIV_FETCH_AND_PARSE_SOURCE,
        MessageType.ARXIV_REEXTRACT_CONTACTS,
    }
)


class SourceStateError(ValueError):
    def __init__(self) -> None:
        super().__init__("SOURCE_STATE_INVALID")


def require(condition: bool) -> None:
    if not condition:
        raise SourceStateError()


def integer(value: Any) -> int:
    require(type(value) is int and 0 <= value <= MAX_INTEGER)
    return int(value)


def uuid_value(value: Any) -> UUID:
    require(isinstance(value, str))
    result = UUID(value)
    require(str(result) == value)
    return result


def timestamp(value: datetime) -> str:
    require(isinstance(value, datetime) and value.utcoffset() is not None)
    require(value.microsecond % 1000 == 0)
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_time(value: Any) -> datetime:
    require(
        isinstance(value, str)
        and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z", value)
        is not None
    )
    result = datetime.fromisoformat(value)
    require(timestamp(result) == value)
    return result


def digest_value(value: Any) -> str:
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None)
    return str(value)


def fields(value: Any, names: str) -> dict[str, Any]:
    require(isinstance(value, dict) and set(value) == set(names.split()))
    return dict(value)


def _valid_tree(value: Any) -> None:
    if value is None or type(value) is bool:
        return
    if type(value) is int:
        integer(value)
    elif isinstance(value, str):
        value.encode("utf-8", "strict")
    elif isinstance(value, list):
        for child in value:
            _valid_tree(child)
    elif isinstance(value, dict):
        for key, child in value.items():
            require(isinstance(key, str))
            key.encode("utf-8", "strict")
            _valid_tree(child)
    else:
        raise SourceStateError()


def canonical_json(value: Any) -> str:
    _valid_tree(value)
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def decode_json(raw: bytes | str, limit: int) -> Any:
    text = raw.decode("utf-8", "strict") if isinstance(raw, bytes) else raw
    require(isinstance(text, str) and len(text.encode("utf-8", "strict")) <= limit)
    value = json.loads(text)
    # Exact bytes also reject duplicate members, whitespace, alternate numbers and escapes.
    require(canonical_json(value) == text)
    return value


class SourcePhase(StrEnum):
    READY = "READY"
    RESERVED = "RESERVED"
    RESULT_READY = "RESULT_READY"
    RETRY_READY = "RETRY_READY"
    EXHAUSTED = "EXHAUSTED"
    HELD = "HELD"
    TERMINAL_PENDING = "TERMINAL_PENDING"
    TERMINAL = "TERMINAL"


class SourceHoldReason(StrEnum):
    LEGACY_UNBOUND = "LEGACY_UNBOUND"
    SCOPE_CONFLICT = "SCOPE_CONFLICT"
    CORRUPT_STATE = "CORRUPT_STATE"
    INVOCATION_UNCERTAIN = "INVOCATION_UNCERTAIN"
    STATE_LOST = "STATE_LOST"


@dataclass(frozen=True, slots=True, repr=False)
class SourceScope:
    idempotency_key: str
    job_id: UUID
    command_message_id: UUID
    command_type: MessageType
    payload_fingerprint: str

    def __post_init__(self) -> None:
        require(
            isinstance(self.idempotency_key, str)
            and not unsafe_bounded_text(self.idempotency_key, 200)
        )
        require(isinstance(self.job_id, UUID) and isinstance(self.command_message_id, UUID))
        require(self.command_type in SOURCE_TYPES)
        digest_value(self.payload_fingerprint)

    @classmethod
    def from_command(
        cls,
        idempotency_key: str,
        job_id: UUID,
        command_message_id: UUID,
        command_type: MessageType,
        payload: object,
    ) -> SourceScope:
        try:
            return cls(
                idempotency_key, job_id, command_message_id, command_type, fingerprint(payload)
            )
        except (ValueError, TypeError, OverflowError):
            pass
        raise SourceStateError()

    def to_dict(self) -> dict[str, Any]:
        return {
            **asdict(self),
            "job_id": str(self.job_id),
            "command_message_id": str(self.command_message_id),
        }

    @classmethod
    def from_dict(cls, value: Any) -> SourceScope:
        n = fields(
            value, "idempotency_key job_id command_message_id command_type payload_fingerprint"
        )
        return cls(
            n["idempotency_key"],
            uuid_value(n["job_id"]),
            uuid_value(n["command_message_id"]),
            MessageType(n["command_type"]),
            digest_value(n["payload_fingerprint"]),
        )

    @property
    def state_key(self) -> str:
        return (
            "camel:worker:source-state:v2:{source-admission}:"
            + hashlib.sha256(self.idempotency_key.encode("utf-8")).hexdigest()
        )


@dataclass(frozen=True, slots=True, repr=False)
class SourceInvocation:
    token: UUID
    index: int
    attempt_number: int
    owner_id: UUID
    deadline: datetime

    def to_dict(self) -> dict[str, Any]:
        return {
            **asdict(self),
            "token": str(self.token),
            "owner_id": str(self.owner_id),
            "deadline": timestamp(self.deadline),
        }

    @classmethod
    def from_dict(cls, value: Any) -> SourceInvocation:
        n = fields(value, "token index attempt_number owner_id deadline")
        require(1 <= integer(n["attempt_number"]) <= 6)
        return cls(
            uuid_value(n["token"]),
            integer(n["index"]),
            n["attempt_number"],
            uuid_value(n["owner_id"]),
            parse_time(n["deadline"]),
        )


@dataclass(frozen=True, slots=True, repr=False)
class SourceAuthorityReceipt:
    sequence: int
    request_nonce: UUID
    owner_id: UUID
    token: UUID | None
    reserved_generation: int
    predecessor_sequence: int

    def to_dict(self) -> dict[str, Any]:
        return {
            **asdict(self),
            "request_nonce": str(self.request_nonce),
            "owner_id": str(self.owner_id),
            "token": str(self.token) if self.token else None,
        }

    @classmethod
    def from_dict(cls, value: Any) -> SourceAuthorityReceipt:
        n = fields(
            value, "sequence request_nonce owner_id token reserved_generation predecessor_sequence"
        )
        sequence, predecessor = integer(n["sequence"]), integer(n["predecessor_sequence"])
        require(sequence == predecessor + 1)
        return cls(
            sequence,
            uuid_value(n["request_nonce"]),
            uuid_value(n["owner_id"]),
            uuid_value(n["token"]) if n["token"] is not None else None,
            integer(n["reserved_generation"]),
            predecessor,
        )


def result_value(value: Any) -> SourceExtractionResult:
    result = SourceExtractionResult.model_validate(value)
    tree = result.model_dump(mode="json")
    require(canonical_json(tree) == canonical_json(value))
    require(len(canonical_json(tree).encode("utf-8")) <= 768 * 1024)
    require(
        len(canonical_json(result.model_dump(mode="json", by_alias=True)).encode("utf-8"))
        <= 768 * 1024
    )
    return result


@dataclass(frozen=True, slots=True, repr=False)
class SourceState:
    scope: SourceScope
    generation: int
    progress: SourceProgress
    phase: SourcePhase
    invocation: SourceInvocation | None = None
    result: SourceExtractionResult | None = None
    reason: SourceUpstreamReason | SourceHoldReason | None = None
    terminal_status: str | None = None
    authority: SourceAuthorityReceipt | None = None

    def to_json(self) -> str:
        try:
            return self._encode()
        except (ValueError, TypeError, OverflowError, RecursionError):
            pass
        raise SourceStateError()

    def _encode(self) -> str:
        return canonical_json(
            {
                "version": 2,
                "scope": self.scope.to_dict(),
                "generation": self.generation,
                "progress": asdict(self.progress),
                "phase": self.phase,
                "invocation": self.invocation.to_dict() if self.invocation else None,
                "result": self.result.model_dump(mode="json", warnings="error")
                if self.result
                else None,
                "reason": self.reason,
                "terminal_status": self.terminal_status,
                "authority": self.authority.to_dict() if self.authority else None,
            }
        )

    @property
    def digest(self) -> str:
        return hashlib.sha256(self.to_json().encode("utf-8")).hexdigest()

    @classmethod
    def from_json(cls, raw: bytes | str) -> SourceState:
        try:
            n = fields(
                decode_json(raw, 1024 * 1024),
                "version scope generation progress phase invocation result reason "
                "terminal_status authority",
            )
            require(integer(n["version"]) == 2)
            scope, generation = SourceScope.from_dict(n["scope"]), integer(n["generation"])
            p = fields(n["progress"], "next_index success skipped failed")
            progress = SourceProgress(**{key: integer(value) for key, value in p.items()})
            require(progress.next_index <= 100)
            phase = SourcePhase(n["phase"])
            authority = (
                SourceAuthorityReceipt.from_dict(n["authority"])
                if n["authority"] is not None
                else None
            )
            invocation = (
                SourceInvocation.from_dict(n["invocation"]) if n["invocation"] is not None else None
            )
            require(authority is None or generation >= authority.reserved_generation)
            if invocation:
                require(invocation.index == progress.next_index and authority is not None)
                assert authority is not None
                require(
                    invocation.token == authority.token
                    and invocation.owner_id == authority.owner_id
                )
            reason: SourceUpstreamReason | SourceHoldReason | None = None
            if n["reason"] is not None:
                reason = (
                    SourceUpstreamReason(n["reason"])
                    if n["reason"] in SourceUpstreamReason
                    else SourceHoldReason(n["reason"])
                )
            terminal = n["terminal_status"]
            require(terminal is None or terminal in TERMINALS)
            result = result_value(n["result"]) if n["result"] is not None else None
            if phase == SourcePhase.READY:
                require(
                    invocation is None and result is None and reason is None and terminal is None
                )
            elif phase in {SourcePhase.RESERVED, SourcePhase.RESULT_READY}:
                require(invocation is not None and reason is None and terminal is None)
                require((result is not None) == (phase == SourcePhase.RESULT_READY))
            elif phase in {SourcePhase.RETRY_READY, SourcePhase.EXHAUSTED}:
                require(
                    invocation is not None
                    and result is None
                    and isinstance(reason, SourceUpstreamReason)
                    and terminal is None
                )
                assert invocation is not None
                require((invocation.attempt_number == 6) == (phase == SourcePhase.EXHAUSTED))
            elif phase == SourcePhase.HELD:
                require(
                    result is None and isinstance(reason, SourceHoldReason) and terminal is None
                )
            else:
                require(
                    invocation is None
                    and result is None
                    and reason is None
                    and terminal is not None
                )
            return cls(
                scope, generation, progress, phase, invocation, result, reason, terminal, authority
            )
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError):
            pass
        # Raise outside except so raw Pydantic input/cause never enters diagnostics.
        raise SourceStateError()


@dataclass(frozen=True, slots=True, repr=False)
class SourceRetryDecision:
    scope: SourceScope
    generation: int
    index: int
    attempt_number: int
    reason: SourceUpstreamReason
    exhausted: bool
