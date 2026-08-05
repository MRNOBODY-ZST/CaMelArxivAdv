from __future__ import annotations

import asyncio
import secrets
import signal
from datetime import UTC, datetime
from uuid import uuid4

import aio_pika
from aio_pika import DeliveryMode, ExchangeType, Message

from app.config import Settings
from app.messaging.contracts import MessageEnvelope, MessageType, WorkerHeartbeat
from app.observability.logging import configure_logging, get_logger


def build_heartbeat_message(settings: Settings) -> MessageEnvelope[WorkerHeartbeat]:
    occurred_at = datetime.now(UTC)
    message_id = uuid4()
    return MessageEnvelope[WorkerHeartbeat](
        message_id=message_id,
        type=MessageType.WORKER_HEARTBEAT,
        idempotency_key=f"heartbeat:{settings.worker_id}:{occurred_at.isoformat()}",
        trace_id=secrets.token_hex(16),
        occurred_at=occurred_at,
        payload=WorkerHeartbeat(
            worker_id=settings.worker_id,
            worker_type="ARXIV",
            version=settings.worker_version,
            status="IDLE",
        ),
    )


async def run(settings: Settings | None = None) -> None:
    active_settings = settings or Settings()
    configure_logging(active_settings.log_level)
    logger = get_logger().bind(workerId=active_settings.worker_id, workerType="ARXIV")
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for handled_signal in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(handled_signal, stop_event.set)

    logger.info("worker_starting", version=active_settings.worker_version)
    connection = await aio_pika.connect_robust(
        active_settings.rabbitmq_url.get_secret_value(),
        timeout=active_settings.request_timeout_seconds,
    )
    try:
        channel = await connection.channel()
        exchange = await channel.declare_exchange(
            active_settings.results_exchange,
            ExchangeType.TOPIC,
            durable=True,
        )
        while not stop_event.is_set():
            heartbeat = build_heartbeat_message(active_settings)
            await exchange.publish(
                Message(
                    body=heartbeat.model_dump_json(by_alias=True).encode("utf-8"),
                    content_type="application/json",
                    delivery_mode=DeliveryMode.PERSISTENT,
                    message_id=str(heartbeat.message_id),
                    timestamp=heartbeat.occurred_at,
                ),
                routing_key="worker.heartbeat",
            )
            logger.info("heartbeat_published", messageId=str(heartbeat.message_id))
            try:
                await asyncio.wait_for(
                    stop_event.wait(), timeout=active_settings.heartbeat_interval_seconds
                )
            except TimeoutError:
                continue
    finally:
        logger.info("worker_stopping")
        await connection.close()


def cli() -> None:
    asyncio.run(run())


if __name__ == "__main__":
    cli()
