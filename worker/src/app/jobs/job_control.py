from __future__ import annotations

from typing import Protocol
from uuid import UUID


class AsyncRedisStore(Protocol):
    async def get(self, key: str) -> bytes | str | None: ...

    async def set(self, key: str, value: str, *, ex: int) -> object: ...


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

    def _processed_key(self, idempotency_key: str) -> str:
        return f"camel:worker:processed:{idempotency_key}"
