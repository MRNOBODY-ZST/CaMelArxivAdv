from __future__ import annotations

import json

import pytest

from app.jobs import job_control


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, bytes | str] = {}
        self.set_calls: list[tuple[str, str, int]] = []
        self.eval_calls: list[tuple[str, int, tuple[object, ...]]] = []

    async def get(self, key: str) -> bytes | str | None:
        return self.values.get(key)

    async def set(self, key: str, value: str, *, ex: int) -> object:
        self.values[key] = value
        self.set_calls.append((key, value, ex))
        return True

    async def delete(self, key: str) -> object:
        self.values.pop(key, None)
        return True

    async def eval(self, script: str, numkeys: int, *values: object) -> object:
        self.eval_calls.append((script, numkeys, values))
        key, raw, next_index, _ttl = values
        assert isinstance(key, str)
        assert isinstance(raw, str)
        assert isinstance(next_index, int)
        current = self.values.get(key)
        if current is not None:
            decoded = current.decode("utf-8") if isinstance(current, bytes) else current
            parsed = job_control.SourceProgress.from_json(decoded)
            if parsed is not None and parsed.next_index >= next_index:
                return 0
        self.values[key] = raw
        return 1


@pytest.mark.asyncio
async def test_source_progress_round_trips_with_a_source_specific_ttl_key() -> None:
    redis = FakeRedis()
    store = job_control.RedisJobStore(redis)
    progress = job_control.SourceProgress(next_index=53, success=52, skipped=0, failed=1)

    await store.save_source_progress("source:job-1", progress)

    assert await store.source_progress_for("source:job-1") == progress
    _, numkeys, values = redis.eval_calls[-1]
    key, raw, next_index, ttl = values
    assert numkeys == 1
    assert key == "camel:worker:source-progress:source:job-1"
    assert next_index == 53
    assert isinstance(raw, str)
    assert json.loads(raw) == {
        "version": 1,
        "nextIndex": 53,
        "success": 52,
        "skipped": 0,
        "failed": 1,
    }
    assert ttl == 32 * 24 * 60 * 60


@pytest.mark.asyncio
async def test_source_progress_cannot_regress_or_repeat_an_existing_index() -> None:
    redis = FakeRedis()
    store = job_control.RedisJobStore(redis)

    assert await store.save_source_progress(
        "source:job-1", job_control.SourceProgress(2, 2, 0, 0)
    )
    assert not await store.save_source_progress(
        "source:job-1", job_control.SourceProgress(1, 0, 0, 1)
    )
    assert not await store.save_source_progress(
        "source:job-1", job_control.SourceProgress(2, 1, 0, 1)
    )

    assert await store.source_progress_for("source:job-1") == job_control.SourceProgress(
        2, 2, 0, 0
    )


@pytest.mark.asyncio
async def test_invalid_numeric_source_progress_is_replaced_instead_of_blocking_resume() -> None:
    redis = FakeRedis()
    redis.values["camel:worker:source-progress:source:job-1"] = (
        '{"version":1,"nextIndex":100,"success":0,"skipped":0,"failed":0}'
    )
    store = job_control.RedisJobStore(redis)

    assert await store.source_progress_for("source:job-1") is None
    assert await store.save_source_progress(
        "source:job-1", job_control.SourceProgress(1, 1, 0, 0)
    )
    assert await store.source_progress_for("source:job-1") == job_control.SourceProgress(
        1, 1, 0, 0
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "raw",
    [
        '{"version":1.0,"nextIndex":1,"success":1,"skipped":0,"failed":0}',
        '{"version":1,"nextIndex":1e2,"success":1e2,"skipped":0,"failed":0}',
    ],
)
async def test_noncanonical_numeric_source_progress_cannot_block_resume(raw: str) -> None:
    redis = FakeRedis()
    redis.values["camel:worker:source-progress:source:job-1"] = raw
    store = job_control.RedisJobStore(redis)

    assert await store.source_progress_for("source:job-1") is None
    assert await store.save_source_progress(
        "source:job-1", job_control.SourceProgress(1, 1, 0, 0)
    )


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
        '{ "version":1,"nextIndex":1,"success":1,"skipped":0,"failed":0 }',
        '{"nextIndex":1,"version":1,"success":1,"skipped":0,"failed":0}',
        (
            '{"version":1,"nextIndex":1,"success":0,"success":1,'
            '"skipped":0,"failed":0}'
        ),
    ],
)
async def test_invalid_source_progress_is_ignored(raw: str) -> None:
    redis = FakeRedis()
    redis.values["camel:worker:source-progress:source:job-1"] = raw
    store = job_control.RedisJobStore(redis)

    assert await store.source_progress_for("source:job-1") is None


@pytest.mark.asyncio
async def test_invalid_utf8_source_progress_is_ignored() -> None:
    redis = FakeRedis()
    redis.values["camel:worker:source-progress:source:job-1"] = b"\xff"
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
