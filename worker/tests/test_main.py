from __future__ import annotations

import asyncio

import pytest
from aiokafka import TopicPartition

from app.messaging.kafka import run_with_consumer_polling


class FakePollingConsumer:
    def __init__(self) -> None:
        self.partition = TopicPartition("jobs", 0)
        self.paused: set[TopicPartition] = set()
        self.polls = 0
        self.resumed = False

    def assignment(self) -> set[TopicPartition]:
        return {self.partition}

    def pause(self, *partitions: TopicPartition) -> None:
        self.paused.update(partitions)

    def resume(self, *partitions: TopicPartition) -> None:
        self.paused.difference_update(partitions)
        self.resumed = True

    async def getmany(
        self, *partitions: TopicPartition, timeout_ms: int = 0, max_records: int = 1
    ) -> dict[TopicPartition, list[object]]:
        assert self.paused == self.assignment()
        self.polls += 1
        return {}


@pytest.mark.asyncio
async def test_long_operation_keeps_polling_without_draining_assigned_records() -> None:
    consumer = FakePollingConsumer()

    async def operation() -> str:
        while consumer.polls < 2:
            await asyncio.sleep(0.001)
        return "completed"

    result = await run_with_consumer_polling(
        consumer,
        operation(),
        interval_seconds=0.001,
    )

    assert result == "completed"
    assert consumer.polls >= 2
    assert consumer.resumed
    assert consumer.paused == set()


class FailingPollingConsumer(FakePollingConsumer):
    async def getmany(
        self, *partitions: TopicPartition, timeout_ms: int = 0, max_records: int = 1
    ) -> dict[TopicPartition, list[object]]:
        raise RuntimeError("poll heartbeat failed")


@pytest.mark.asyncio
async def test_poll_failure_cancels_the_unsettled_operation() -> None:
    consumer = FailingPollingConsumer()
    canceled = asyncio.Event()

    async def operation() -> None:
        try:
            await asyncio.Future()
        finally:
            canceled.set()

    with pytest.raises(RuntimeError, match="poll heartbeat failed"):
        await run_with_consumer_polling(
            consumer,
            operation(),
            interval_seconds=0.001,
        )

    assert canceled.is_set()
    assert consumer.resumed
