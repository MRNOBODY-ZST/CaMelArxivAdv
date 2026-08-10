from __future__ import annotations

import asyncio
from typing import Protocol

GLOBAL_ARXIV_LEASE_KEY = "camel:arxiv:global-next-request-ms"
MINIMUM_REQUEST_INTERVAL_SECONDS = 3.0

_RESERVE_SCRIPT = """
local server_time = redis.call('TIME')
local now_ms = (tonumber(server_time[1]) * 1000) + math.floor(tonumber(server_time[2]) / 1000)
local next_ms = tonumber(redis.call('GET', KEYS[1]))
if next_ms == nil or next_ms < now_ms then
  next_ms = now_ms
end
local interval_ms = tonumber(ARGV[1])
redis.call('SET', KEYS[1], next_ms + interval_ms, 'PX', 86400000)
return next_ms - now_ms
"""


class AsyncRedis(Protocol):
    async def eval(
        self, script: str, number_of_keys: int, key: str, interval_ms: int
    ) -> int | bytes | str: ...


class RedisGlobalArxivRateLease:
    def __init__(self, redis: AsyncRedis, interval_seconds: float) -> None:
        if interval_seconds < MINIMUM_REQUEST_INTERVAL_SECONDS:
            raise ValueError("arXiv request interval must be at least three seconds")
        self._redis = redis
        self._interval_ms = round(interval_seconds * 1000)

    async def reserve_delay_seconds(self) -> float:
        try:
            raw_delay = await self._redis.eval(
                _RESERVE_SCRIPT, 1, GLOBAL_ARXIV_LEASE_KEY, self._interval_ms
            )
            delay_ms = int(raw_delay)
            return float(max(0, delay_ms)) / 1000.0
        except (TypeError, ValueError, OSError) as exception:
            raise RuntimeError("Could not reserve the global arXiv rate lease") from exception

    async def await_permit(self) -> None:
        delay = await self.reserve_delay_seconds()
        if delay > 0:
            await asyncio.sleep(delay)
