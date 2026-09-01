from __future__ import annotations

import json

import pytest

from app.jobs import job_control


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, bytes | str] = {}
        self.set_calls: list[tuple[str, str, int]] = []

    async def get(self, key: str) -> bytes | str | None:
        return self.values.get(key)

    async def set(self, key: str, value: str, *, ex: int) -> object:
        self.values[key] = value
        self.set_calls.append((key, value, ex))
        return True

    async def delete(self, key: str) -> object:
        self.values.pop(key, None)
        return True


@pytest.mark.asyncio
async def test_source_progress_round_trips_with_a_source_specific_ttl_key() -> None:
    redis = FakeRedis()
    store = job_control.RedisJobStore(redis)
    progress = job_control.SourceProgress(next_index=53, success=52, skipped=0, failed=1)

    await store.save_source_progress("source:job-1", progress)

    assert await store.source_progress_for("source:job-1") == progress
    key, raw, ttl = redis.set_calls[-1]
    assert key == "camel:worker:source-progress:source:job-1"
    assert json.loads(raw) == {
        "version": 1,
        "nextIndex": 53,
        "success": 52,
        "skipped": 0,
        "failed": 1,
    }
    assert ttl == 7 * 24 * 60 * 60


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "raw",
    [
        "not-json",
        '{"version":2,"nextIndex":1,"success":1,"skipped":0,"failed":0}',
        '{"version":1,"nextIndex":true,"success":1,"skipped":0,"failed":0}',
        '{"version":1,"nextIndex":-1,"success":0,"skipped":0,"failed":0}',
        '{"version":1,"nextIndex":1,"success":0,"skipped":0,"failed":0}',
        '{"version":1,"nextIndex":"1","success":1,"skipped":0,"failed":0}',
        '{"version":1,"nextIndex":1,"success":1,"skipped":0,"failed":0,"extra":0}',
    ],
)
async def test_invalid_source_progress_is_ignored(raw: str) -> None:
    redis = FakeRedis()
    redis.values["camel:worker:source-progress:source:job-1"] = raw
    store = job_control.RedisJobStore(redis)

    assert await store.source_progress_for("source:job-1") is None


@pytest.mark.asyncio
async def test_source_progress_redis_failure_is_not_treated_as_missing_checkpoint() -> None:
    class FailingRedis(FakeRedis):
        async def get(self, key: str) -> bytes | str | None:
            raise ConnectionError("redis unavailable")

    store = job_control.RedisJobStore(FailingRedis())

    with pytest.raises(ConnectionError, match="redis unavailable"):
        await store.source_progress_for("source:job-1")
