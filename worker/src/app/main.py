from __future__ import annotations

import asyncio
import contextlib
import secrets
import signal
from collections.abc import Awaitable
from dataclasses import dataclass
from datetime import UTC, datetime
from functools import partial
from typing import Literal
from uuid import UUID, uuid4

import httpx
import redis.asyncio as redis_async
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from pydantic import ValidationError
from structlog.typing import FilteringBoundLogger

from app.arxiv.api_client import LegacyApiClient
from app.arxiv.oai_client import OaiClient
from app.arxiv.rate_limit import RedisGlobalArxivRateLease
from app.arxiv.source_downloader import SourceDownloader
from app.config import Settings
from app.extraction.archive_guard import ArchiveLimits
from app.jobs.arxiv_consumer import ArxivCommandProcessor, CommandOutcome
from app.jobs.job_control import RedisJobStore
from app.jobs.source_extraction import SourceExtractionRunner
from app.messaging.contracts import MessageEnvelope, MessageType, WorkerHeartbeat
from app.messaging.kafka import (
    KafkaRecord,
    KafkaResultPublisher,
    contract_headers,
    forward_retry,
    settle_delivery,
)
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
    active = settings or Settings()
    configure_logging(active.log_level)
    logger = get_logger().bind(workerId=active.worker_id, workerType="ARXIV")
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for handled_signal in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(handled_signal, stop_event.set)

    producer = AIOKafkaProducer(
        bootstrap_servers=active.kafka_bootstrap_servers,
        client_id=active.kafka_client_id,
        security_protocol=active.kafka_security_protocol,
        enable_idempotence=True,
    )
    consumer = AIOKafkaConsumer(
        active.jobs_topic,
        active.retry_topic,
        bootstrap_servers=active.kafka_bootstrap_servers,
        client_id=active.kafka_client_id,
        group_id=active.consumer_group,
        security_protocol=active.kafka_security_protocol,
        enable_auto_commit=False,
        auto_offset_reset="earliest",
        isolation_level="read_committed",
        max_poll_records=1,
        max_poll_interval_ms=active.maximum_poll_interval_ms,
    )
    await producer.start()
    await consumer.start()
    redis = redis_async.from_url(active.redis_url.get_secret_value())  # type: ignore[no-untyped-call]
    runtime_state = WorkerRuntimeState()
    heartbeat_task: asyncio.Task[None] | None = None
    try:
        lease = RedisGlobalArxivRateLease(redis, active.min_request_interval_seconds)
        store = RedisJobStore(redis)
        timeout = httpx.Timeout(active.request_timeout_seconds)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as http:
            processor = ArxivCommandProcessor(
                LegacyApiClient(
                    http,
                    lease,
                    active.legacy_base_url,
                    active.allowed_arxiv_hosts,
                    active.user_agent,
                ),
                OaiClient(
                    http,
                    lease,
                    active.oai_base_url,
                    active.allowed_arxiv_hosts,
                    active.user_agent,
                ),
                KafkaResultPublisher(producer, active.results_topic),
                store,
                batch_size=active.metadata_batch_size,
                maximum_command_bytes=active.command_max_bytes,
                source_runner=SourceExtractionRunner(
                    SourceDownloader(
                        http,
                        lease,
                        base_url=active.source_base_url,
                        allowed_hosts=active.allowed_arxiv_hosts,
                        user_agent=active.user_agent,
                        maximum_bytes=active.max_archive_bytes,
                        maximum_redirects=active.max_redirects,
                        maximum_retries=active.max_request_retries,
                    ),
                    archive_limits=ArchiveLimits(
                        maximum_extracted_bytes=active.max_extracted_bytes,
                        maximum_single_file_bytes=active.max_single_file_bytes,
                        maximum_file_count=active.max_file_count,
                        maximum_directory_depth=active.max_directory_depth,
                        maximum_compression_ratio=active.max_compression_ratio,
                    ),
                    maximum_include_depth=active.max_include_depth,
                    maximum_parse_seconds=active.max_parse_seconds,
                    temporary_root=active.temp_root,
                    parser_version=active.worker_version,
                ),
            )
            heartbeat_task = asyncio.create_task(
                _heartbeat_loop(active, producer, stop_event, logger, runtime_state)
            )
            logger.info("worker_started", version=active.worker_version)
            while not stop_event.is_set():
                try:
                    record = await asyncio.wait_for(consumer.getone(), timeout=1.0)
                except TimeoutError:
                    continue
                if record.topic == active.retry_topic:
                    await _run_with_consumer_polling(
                        consumer,
                        forward_retry(
                            record,
                            producer,
                            consumer,
                            default_topic=active.jobs_topic,
                            maximum_delay_ms=int(active.retry_delay_seconds * 1_000),
                        ),
                        interval_seconds=active.consumer_poll_interval_seconds,
                    )
                    continue
                await _run_with_consumer_polling(
                    consumer,
                    _handle_command_record(
                        record,
                        processor,
                        producer,
                        consumer,
                        active,
                        runtime_state,
                        logger,
                    ),
                    interval_seconds=active.consumer_poll_interval_seconds,
                )
    finally:
        stop_event.set()
        if heartbeat_task is not None:
            try:
                await heartbeat_task
            except Exception:
                logger.exception("heartbeat_task_failed_during_shutdown")
        try:
            await redis.aclose()
        finally:
            try:
                await consumer.stop()
            finally:
                await producer.stop()
        logger.info("worker_stopped")


async def _handle_command_record(
    record: KafkaRecord,
    processor: ArxivCommandProcessor,
    producer: AIOKafkaProducer,
    consumer: AIOKafkaConsumer,
    settings: Settings,
    runtime_state: WorkerRuntimeState,
    logger: FilteringBoundLogger,
) -> None:
    runtime_state.status = "BUSY"
    runtime_state.current_job_id = _command_job_id(record.value)
    try:
        try:
            outcome = await processor.process(record.value)
        except Exception as error:
            logger.error(
                "command_processing_failed_unexpectedly",
                jobId=(
                    str(runtime_state.current_job_id)
                    if runtime_state.current_job_id is not None
                    else None
                ),
                errorType=type(error).__name__,
            )
            outcome = CommandOutcome.RETRY
        await settle_delivery(
            record,
            outcome,
            producer,
            consumer,
            retry_topic=settings.retry_topic,
            dead_letter_topic=settings.dead_letter_topic,
            retry_delay_ms=int(settings.retry_delay_seconds * 1_000),
            on_retry_exhausted=partial(
                processor.publish_retry_exhausted_failure, record.value
            ),
            on_permanent_failure=partial(
                processor.publish_permanent_failure, record.value
            ),
        )
    finally:
        runtime_state.status = "IDLE"
        runtime_state.current_job_id = None


async def _run_with_consumer_polling[T](
    consumer: AIOKafkaConsumer,
    operation: Awaitable[T],
    *,
    interval_seconds: float,
) -> T:
    if interval_seconds <= 0:
        raise ValueError("Consumer poll interval must be positive")
    paused = set(consumer.assignment())
    if paused:
        consumer.pause(*paused)
    finished = asyncio.Event()
    operation_task = asyncio.ensure_future(operation)

    async def keep_polling() -> None:
        while not finished.is_set():
            try:
                await asyncio.wait_for(finished.wait(), timeout=interval_seconds)
            except TimeoutError:
                assigned = set(consumer.assignment())
                new_partitions = assigned - paused
                if new_partitions:
                    consumer.pause(*new_partitions)
                    paused.update(new_partitions)
                records = await consumer.getmany(timeout_ms=0, max_records=1)
                if any(records.values()):
                    raise RuntimeError("Paused Kafka consumer returned records") from None

    poll_task = asyncio.create_task(keep_polling())
    try:
        completed, _ = await asyncio.wait(
            {operation_task, poll_task}, return_when=asyncio.FIRST_COMPLETED
        )
        if poll_task in completed:
            await poll_task
            raise RuntimeError("Kafka poll heartbeat stopped unexpectedly")
        return await operation_task
    finally:
        finished.set()
        try:
            if not operation_task.done():
                operation_task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await operation_task
            if not poll_task.done():
                await poll_task
        finally:
            resumable = paused.intersection(consumer.assignment())
            if resumable:
                consumer.resume(*resumable)


async def _heartbeat_loop(
    settings: Settings,
    producer: AIOKafkaProducer,
    stop_event: asyncio.Event,
    logger: FilteringBoundLogger,
    state: WorkerRuntimeState,
) -> None:
    while not stop_event.is_set():
        heartbeat = build_heartbeat_message(settings, state)
        await producer.send_and_wait(
            settings.results_topic,
            value=heartbeat.model_dump_json(by_alias=True).encode("utf-8"),
            key=str(heartbeat.message_id).encode("ascii"),
            headers=contract_headers(heartbeat.type.value, heartbeat.version),
        )
        logger.info("heartbeat_published", messageId=str(heartbeat.message_id))
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=settings.heartbeat_interval_seconds)
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
