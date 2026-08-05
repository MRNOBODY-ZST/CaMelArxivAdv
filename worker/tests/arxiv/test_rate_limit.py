from __future__ import annotations

from typing import Any

import pytest

from app.arxiv.rate_limit import GLOBAL_ARXIV_LEASE_KEY, RedisGlobalArxivRateLease


class FakeRedis:
    def __init__(self, result: int | Exception) -> None:
        self.result = result
        self.calls: list[tuple[str, int, str, int]] = []

    async def eval(self, script: str, number_of_keys: int, key: str, interval_ms: int) -> Any:
        self.calls.append((script, number_of_keys, key, interval_ms))
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


def test_uses_the_same_global_key_as_the_spring_client() -> None:
    assert GLOBAL_ARXIV_LEASE_KEY == "camel:arxiv:global-next-request-ms"


@pytest.mark.asyncio
async def test_reserves_with_the_redis_server_time_script() -> None:
    redis = FakeRedis(3200)
    lease = RedisGlobalArxivRateLease(redis, 3.0)

    delay = await lease.reserve_delay_seconds()

    assert delay == 3.2
    assert redis.calls[0][1:] == (1, GLOBAL_ARXIV_LEASE_KEY, 3000)
    assert "redis.call('TIME')" in redis.calls[0][0]


def test_rejects_an_interval_below_three_seconds() -> None:
    with pytest.raises(ValueError, match="three seconds"):
        RedisGlobalArxivRateLease(FakeRedis(0), 2.999)


@pytest.mark.asyncio
async def test_fails_closed_when_redis_is_unavailable() -> None:
    lease = RedisGlobalArxivRateLease(FakeRedis(ConnectionError("offline")), 3.0)

    with pytest.raises(RuntimeError, match="rate lease"):
        await lease.reserve_delay_seconds()
