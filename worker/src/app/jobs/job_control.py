from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID


class AsyncRedisStore(Protocol):
    async def get(self, key: str) -> bytes | str | None: ...

    async def set(self, key: str, value: str, *, ex: int) -> object: ...

    async def delete(self, key: str) -> object: ...


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
            return cls(
                next_index=value["nextIndex"],
                success=value["success"],
                skipped=value["skipped"],
                failed=value["failed"],
            )
        except (json.JSONDecodeError, KeyError, TypeError, ValueError):
            return None


class RedisJobStore:
    def __init__(self, redis: AsyncRedisStore) -> None:
        self._redis = redis

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
        value = raw.decode("utf-8", errors="strict") if isinstance(raw, bytes) else raw
        return SourceProgress.from_json(value) if isinstance(value, str) and value else None

    async def save_source_progress(
        self, idempotency_key: str, progress: SourceProgress
    ) -> None:
        await self._redis.set(
            self._source_progress_key(idempotency_key),
            progress.to_json(),
            ex=7 * 24 * 60 * 60,
        )

    async def clear_source_progress(self, idempotency_key: str) -> None:
        await self._redis.delete(self._source_progress_key(idempotency_key))

    def _processed_key(self, idempotency_key: str) -> str:
        return f"camel:worker:processed:{idempotency_key}"

    def _cursor_key(self, idempotency_key: str) -> str:
        return f"camel:worker:oai-cursor:{idempotency_key}"

    def _source_progress_key(self, idempotency_key: str) -> str:
        return f"camel:worker:source-progress:{idempotency_key}"
