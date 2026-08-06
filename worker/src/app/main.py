from __future__ import annotations

import asyncio
import secrets
import signal
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal
from uuid import UUID, uuid4

import aio_pika
import httpx
import redis.asyncio as redis_async
from aio_pika import DeliveryMode, ExchangeType, Message
from pydantic import ValidationError
from structlog.typing import FilteringBoundLogger

from app.arxiv.api_client import LegacyApiClient
from app.arxiv.oai_client import OaiClient
from app.arxiv.rate_limit import RedisGlobalArxivRateLease
from app.config import Settings
from app.jobs.arxiv_consumer import ArxivCommandProcessor, CommandOutcome
from app.jobs.job_control import RedisJobStore
from app.messaging.contracts import MessageEnvelope, MessageType, WorkerHeartbeat
from app.messaging.rabbit import RabbitResultPublisher, settle_delivery
from app.observability.logging import configure_logging, get_logger


@dataclass(slots=True)
class WorkerRuntimeState:
    status: Literal["IDLE", "BUSY", "DRAINING", "UNHEALTHY"] = "IDLE"
    current_job_id: UUID | None = None


def build_heartbeat_message(
    settings: Settings, state: WorkerRuntimeState | None = None
) -> MessageEnvelope[WorkerHeartbeat]:
    runtime = state or WorkerRuntimeState()
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
            status=runtime.status,
            current_job_id=runtime.current_job_id,
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
    runtime_state = WorkerRuntimeState()
    connection = await aio_pika.connect_robust(
        active_settings.rabbitmq_url.get_secret_value(),
        timeout=active_settings.request_timeout_seconds,
    )
    try:
        channel = await connection.channel(publisher_confirms=True, on_return_raises=True)
        await channel.set_qos(prefetch_count=1)
        exchange = await channel.declare_exchange(
            active_settings.results_exchange,
            ExchangeType.TOPIC,
            durable=True,
        )
        jobs_exchange = await channel.declare_exchange(
            active_settings.jobs_exchange, ExchangeType.TOPIC, durable=True
        )
        retry_exchange = await channel.declare_exchange(
            active_settings.retry_exchange, ExchangeType.TOPIC, durable=True
        )
        await channel.declare_exchange(
            active_settings.dead_exchange, ExchangeType.TOPIC, durable=True
        )
        queue = await channel.declare_queue(
            active_settings.jobs_queue,
            durable=True,
            arguments={"x-dead-letter-exchange": active_settings.dead_exchange},
        )
        await queue.bind(jobs_exchange, "arxiv.import.metadata")
        await queue.bind(jobs_exchange, "arxiv.sync.oai")
        await queue.bind(jobs_exchange, "arxiv.sync.taxonomy")
        redis = redis_async.from_url(  # type: ignore[no-untyped-call]
            active_settings.redis_url.get_secret_value()
        )
        lease = RedisGlobalArxivRateLease(redis, active_settings.min_request_interval_seconds)
        store = RedisJobStore(redis)
        timeout = httpx.Timeout(active_settings.request_timeout_seconds)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as http:
            processor = ArxivCommandProcessor(
                LegacyApiClient(
                    http,
                    lease,
                    active_settings.legacy_base_url,
                    active_settings.allowed_arxiv_hosts,
                    active_settings.user_agent,
                ),
                OaiClient(
                    http,
                    lease,
                    active_settings.oai_base_url,
                    active_settings.allowed_arxiv_hosts,
                    active_settings.user_agent,
                ),
                RabbitResultPublisher(exchange),
                store,
                batch_size=active_settings.metadata_batch_size,
                maximum_command_bytes=active_settings.command_max_bytes,
            )

            async def consume(message: aio_pika.abc.AbstractIncomingMessage) -> None:
                runtime_state.status = "BUSY"
                runtime_state.current_job_id = _command_job_id(message.body)
                try:
                    try:
                        outcome = await processor.process(message.body)
                    except Exception:
                        logger.exception("command_processing_failed_unexpectedly")
                        outcome = CommandOutcome.REQUEUE
                    await settle_delivery(message, outcome, retry_exchange)
                finally:
                    runtime_state.status = "IDLE"
                    runtime_state.current_job_id = None

            consumer_tag = await queue.consume(consume, no_ack=False)
            try:
                await _heartbeat_loop(
                    active_settings, exchange, stop_event, logger, runtime_state
                )
            finally:
                await queue.cancel(consumer_tag)
                await redis.aclose()
    finally:
        logger.info("worker_stopping")
        await connection.close()


async def _heartbeat_loop(
    active_settings: Settings,
    exchange: aio_pika.abc.AbstractExchange,
    stop_event: asyncio.Event,
    logger: FilteringBoundLogger,
    state: WorkerRuntimeState,
) -> None:
    while not stop_event.is_set():
        heartbeat = build_heartbeat_message(active_settings, state)
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


def _command_job_id(body: bytes) -> UUID | None:
    try:
        return MessageEnvelope[dict[str, object]].model_validate_json(body).job_id
    except (ValidationError, ValueError, UnicodeDecodeError):
        return None


def cli() -> None:
    asyncio.run(run())


if __name__ == "__main__":
    cli()
