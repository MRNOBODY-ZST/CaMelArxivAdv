from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable, Mapping, Sequence
from typing import Protocol

from aiokafka import TopicPartition

from app.jobs.arxiv_consumer import CommandOutcome
from app.messaging.contracts import MessageEnvelope, ResultPayload


class KafkaRecord(Protocol):
    topic: str
    partition: int
    offset: int
    key: bytes | None
    value: bytes
    headers: Sequence[tuple[str, bytes]] | None


class KafkaProducer(Protocol):
    async def send_and_wait(
        self,
        topic: str,
        value: bytes | None = None,
        key: bytes | None = None,
        partition: int | None = None,
        timestamp_ms: int | None = None,
        headers: list[tuple[str, bytes]] | None = None,
    ) -> object: ...


class KafkaConsumer(Protocol):
    async def commit(self, offsets: Mapping[TopicPartition, int]) -> None: ...


class KafkaResultPublisher:
    def __init__(self, producer: KafkaProducer, topic: str) -> None:
        self._producer = producer
        self._topic = topic

    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None:
        partition_key = message.job_id or message.message_id
        await self._producer.send_and_wait(
            self._topic,
            value=message.model_dump_json(by_alias=True).encode("utf-8"),
            key=str(partition_key).encode("ascii"),
            headers=contract_headers(message.type.value, message.version),
        )


def contract_headers(message_type: str, version: int) -> list[tuple[str, bytes]]:
    return [
        ("messageType", message_type.encode("ascii")),
        ("contractVersion", str(version).encode("ascii")),
    ]


async def settle_delivery(
    incoming: KafkaRecord,
    outcome: CommandOutcome,
    producer: KafkaProducer,
    consumer: KafkaConsumer,
    *,
    retry_topic: str,
    dead_letter_topic: str,
    now_epoch_ms: int | None = None,
    retry_delay_ms: int = 30_000,
    on_retry_exhausted: Callable[[], Awaitable[object]] | None = None,
) -> None:
    if outcome is CommandOutcome.ACK:
        await _commit(consumer, incoming)
        return
    headers = _headers(incoming.headers)
    retry_count = _retry_count(headers)
    if outcome is CommandOutcome.DEAD:
        await producer.send_and_wait(
            dead_letter_topic,
            value=incoming.value,
            key=incoming.key,
            headers=[*headers.items(), ("failureCategory", b"PERMANENT_FAILURE")],
        )
        await _commit(consumer, incoming)
        return
    if outcome is CommandOutcome.RETRY and retry_count >= 5:
        await producer.send_and_wait(
            dead_letter_topic,
            value=incoming.value,
            key=incoming.key,
            headers=[*headers.items(), ("failureCategory", b"RETRY_EXHAUSTED")],
        )
        if on_retry_exhausted is not None:
            await on_retry_exhausted()
        await _commit(consumer, incoming)
        return
    current_ms = int(time.time() * 1_000) if now_epoch_ms is None else now_epoch_ms
    if outcome is CommandOutcome.RETRY:
        headers["camelRetryCount"] = str(retry_count + 1).encode("ascii")
    headers["camelNotBeforeEpochMs"] = str(current_ms + retry_delay_ms).encode("ascii")
    headers["camelOriginalTopic"] = incoming.topic.encode("utf-8")
    await producer.send_and_wait(
        retry_topic,
        value=incoming.value,
        key=incoming.key,
        headers=list(headers.items()),
    )
    await _commit(consumer, incoming)


async def forward_retry(
    incoming: KafkaRecord,
    producer: KafkaProducer,
    consumer: KafkaConsumer,
    *,
    default_topic: str,
    now_epoch_ms: int | None = None,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> None:
    headers = _headers(incoming.headers)
    current_ms = int(time.time() * 1_000) if now_epoch_ms is None else now_epoch_ms
    due_ms = _epoch_millis(headers.get("camelNotBeforeEpochMs"), current_ms)
    if due_ms > current_ms:
        await sleep((due_ms - current_ms) / 1_000)
    forwarded = {
        name: value
        for name, value in headers.items()
        if name not in {"camelNotBeforeEpochMs", "camelOriginalTopic"}
    }
    await producer.send_and_wait(
        default_topic,
        value=incoming.value,
        key=incoming.key,
        headers=list(forwarded.items()),
    )
    await _commit(consumer, incoming)


async def _commit(consumer: KafkaConsumer, incoming: KafkaRecord) -> None:
    await consumer.commit({TopicPartition(incoming.topic, incoming.partition): incoming.offset + 1})


def _headers(source: Sequence[tuple[str, bytes]] | None) -> dict[str, bytes]:
    return {name: value for name, value in source or ()}


def _retry_count(headers: Mapping[str, bytes]) -> int:
    value = headers.get("camelRetryCount", b"0")
    try:
        parsed = int(value.decode("ascii"))
    except (UnicodeDecodeError, ValueError):
        return 5
    return parsed if 0 <= parsed <= 5 else 5


def _epoch_millis(value: bytes | None, fallback: int) -> int:
    if value is None:
        return fallback
    try:
        parsed = int(value.decode("ascii"))
    except (UnicodeDecodeError, ValueError):
        return fallback
    return parsed if 0 <= parsed <= 32_503_680_000_000 else fallback
