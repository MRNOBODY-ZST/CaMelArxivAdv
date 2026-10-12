from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING, Protocol
from uuid import UUID

if TYPE_CHECKING:
    from app.arxiv.source_errors import SourceUpstreamReason
    from app.jobs.source_retry import SourceHoldReason, SourceScope, SourceState
    from app.messaging.contracts import SourceExtractionResult


class AsyncRedisStore(Protocol):
    async def get(self, key: str) -> bytes | str | None: ...

    async def set(self, key: str, value: str, *, ex: int) -> object: ...

    async def delete(self, key: str) -> object: ...

    async def eval(self, script: str, numkeys: int, *values: object) -> object: ...


_SOURCE_PROGRESS_TTL_SECONDS = 32 * 24 * 60 * 60
_SAVE_SOURCE_PROGRESS = """
local current = redis.call('GET', KEYS[1])
if current then
  local next_text, success_text, skipped_text, failed_text = string.match(
    current,
    '^{"version":1,"nextIndex":(%d+),"success":(%d+),"skipped":(%d+),"failed":(%d+)}$'
  )
  local next_index = tonumber(next_text)
  local success = tonumber(success_text)
  local skipped = tonumber(skipped_text)
  local failed = tonumber(failed_text)
  local valid = next_index ~= nil and success ~= nil and skipped ~= nil and failed ~= nil
      and tostring(next_index) == next_text and tostring(success) == success_text
      and tostring(skipped) == skipped_text and tostring(failed) == failed_text
      and success + skipped + failed == next_index
  if valid and next_index >= tonumber(ARGV[2]) then
    return 0
  end
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
return 1
"""


@dataclass(frozen=True, slots=True)
class SourceProgress:
    next_index: int
    success: int
    skipped: int
    failed: int

    def __post_init__(self) -> None:
        values = (self.next_index, self.success, self.skipped, self.failed)
        if any(type(value) is not int or value < 0 for value in values):
            raise ValueError("Source progress values must be non-negative integers")
        if self.success + self.skipped + self.failed != self.next_index:
            raise ValueError("Source progress counts must equal the next index")

    def to_json(self) -> str:
        return json.dumps(
            {
                "version": 1,
                "nextIndex": self.next_index,
                "success": self.success,
                "skipped": self.skipped,
                "failed": self.failed,
            },
            separators=(",", ":"),
        )

    @classmethod
    def from_json(cls, raw: str) -> SourceProgress | None:
        try:
            value = json.loads(raw)
            if not isinstance(value, dict) or set(value) != {
                "version",
                "nextIndex",
                "success",
                "skipped",
                "failed",
            }:
                return None
            if value["version"] != 1 or type(value["version"]) is not int:
                return None
            progress = cls(
                next_index=value["nextIndex"],
                success=value["success"],
                skipped=value["skipped"],
                failed=value["failed"],
            )
            return progress if progress.to_json() == raw else None
        except (json.JSONDecodeError, KeyError, TypeError, ValueError):
            return None


class RedisJobStore:
    def __init__(self, redis: AsyncRedisStore) -> None:
        from app.jobs.source_admission import SourceStateStore

        self._redis = redis
        self._source = SourceStateStore(redis)

    @property
    def source_owner_id(self) -> UUID:
        return self._source.admission.owner_id

    async def load_or_bind_source(self, scope: SourceScope) -> SourceState:
        return await self._source.load_or_bind(scope)

    async def get_source(self, scope: SourceScope) -> SourceState:
        """Authority-verified read; RESERVED returned here is always non-executing."""
        return await self._source.load_or_bind(scope)

    async def get_source_retirement(self, scope: SourceScope) -> SourceState | None:
        return await self._source.get_retirement(scope)

    async def reserve_source_invocation(
        self,
        scope: SourceScope,
        expected_generation: int,
        index: int,
        owner_id: UUID,
        deadline: datetime,
    ) -> SourceState:
        return await self._source.reserve(scope, expected_generation, index, owner_id, deadline)

    async def take_source_invocation(
        self, scope: SourceScope, original_reservation: SourceState
    ) -> bool:
        return await self._source.take(scope, original_reservation)

    async def renew_source_invocation(
        self, scope: SourceScope, token: UUID, deadline: datetime
    ) -> bool:
        return await self._source.renew(scope, token, deadline)

    async def stage_source_result(
        self, scope: SourceScope, token: UUID, result: SourceExtractionResult
    ) -> SourceState:
        return await self._source.stage(scope, token, result)

    async def record_source_transient(
        self, scope: SourceScope, token: UUID, reason: SourceUpstreamReason
    ) -> SourceState:
        return await self._source.transient(scope, token, reason)

    async def hold_source(
        self, scope: SourceScope, expected_generation: int, reason: SourceHoldReason
    ) -> SourceState:
        return await self._source.hold(scope, expected_generation, reason)

    async def advance_source(
        self, scope: SourceScope, expected_generation: int, index: int
    ) -> SourceState:
        return await self._source.advance(scope, expected_generation, index)

    async def record_source_terminal(
        self, scope: SourceScope, expected_generation: int, status: str
    ) -> SourceState:
        return await self._source.terminal(scope, expected_generation, status)

    async def retire_source_terminal(
        self, scope: SourceScope, expected_generation: int
    ) -> SourceState:
        return await self._source.retire(scope, expected_generation)

    async def is_processed(self, idempotency_key: str) -> bool:
        return await self._redis.get(self._processed_key(idempotency_key)) is not None

    async def mark_processed(self, idempotency_key: str) -> None:
        await self._redis.set(self._processed_key(idempotency_key), "1", ex=30 * 24 * 60 * 60)

    async def control_for(self, job_id: UUID) -> str:
        raw = await self._redis.get(f"camel:jobs:control:{job_id}")
        if isinstance(raw, bytes):
            value = raw.decode("ascii", errors="ignore").upper()
        else:
            value = str(raw or "RUN").upper()
        return value if value in {"RUN", "PAUSE", "CANCEL"} else "RUN"

    async def cursor_for(self, idempotency_key: str) -> str | None:
        raw = await self._redis.get(self._cursor_key(idempotency_key))
        value = raw.decode("utf-8", errors="strict") if isinstance(raw, bytes) else raw
        return value if isinstance(value, str) and value else None

    async def save_cursor(self, idempotency_key: str, token: str) -> None:
        await self._redis.set(self._cursor_key(idempotency_key), token, ex=7 * 24 * 60 * 60)

    async def clear_cursor(self, idempotency_key: str) -> None:
        await self._redis.delete(self._cursor_key(idempotency_key))

    async def source_progress_for(self, idempotency_key: str) -> SourceProgress | None:
        raw = await self._redis.get(self._source_progress_key(idempotency_key))
        try:
            value = raw.decode("utf-8", errors="strict") if isinstance(raw, bytes) else raw
        except UnicodeDecodeError:
            return None
        return SourceProgress.from_json(value) if isinstance(value, str) and value else None

    async def save_source_progress(self, idempotency_key: str, progress: SourceProgress) -> bool:
        saved = await self._redis.eval(
            _SAVE_SOURCE_PROGRESS,
            1,
            self._source_progress_key(idempotency_key),
            progress.to_json(),
            progress.next_index,
            _SOURCE_PROGRESS_TTL_SECONDS,
        )
        return saved == 1

    async def clear_source_progress(self, idempotency_key: str) -> None:
        await self._redis.delete(self._source_progress_key(idempotency_key))

    def _processed_key(self, idempotency_key: str) -> str:
        return f"camel:worker:processed:{idempotency_key}"

    def _cursor_key(self, idempotency_key: str) -> str:
        return f"camel:worker:oai-cursor:{idempotency_key}"

    def _source_progress_key(self, idempotency_key: str) -> str:
        return f"camel:worker:source-progress:{idempotency_key}"
