from __future__ import annotations

import asyncio
import signal

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from app.config import PersonalizationSettings
from app.messaging.kafka import forward_retry, run_with_consumer_polling, settle_delivery
from app.observability.logging import configure_logging, get_logger
from app.personalization.consumer import PersonalizationCommandProcessor
from app.personalization.kafka import PersonalizationResultPublisher
from app.personalization.ray_executor import RayPersonalizationExecutor


async def run(settings: PersonalizationSettings | None = None) -> None:
    active = settings or PersonalizationSettings()
    configure_logging(active.log_level)
    logger = get_logger().bind(workerType="PERSONALIZATION")
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
    executor = RayPersonalizationExecutor(active)
    await producer.start()
    await consumer.start()
    try:
        processor = PersonalizationCommandProcessor(
            executor,
            PersonalizationResultPublisher(producer, active.results_topic),
            maximum_command_bytes=active.maximum_command_bytes,
        )
        logger.info(
            "personalization_worker_started",
            providerEnabled=active.enabled,
            model=active.model,
        )
        while not stop_event.is_set():
            try:
                record = await asyncio.wait_for(consumer.getone(), timeout=1.0)
            except TimeoutError:
                continue
            if record.topic == active.retry_topic:
                await forward_retry(
                    record,
                    producer,
                    consumer,
                    default_topic=active.jobs_topic,
                )
                continue
            outcome = await run_with_consumer_polling(
                consumer,
                processor.process(record.value),
                interval_seconds=active.consumer_poll_interval_seconds,
            )
            await settle_delivery(
                record,
                outcome,
                producer,
                consumer,
                retry_topic=active.retry_topic,
                dead_letter_topic=active.dead_letter_topic,
                retry_delay_ms=int(active.retry_delay_seconds * 1_000),
            )
    finally:
        executor.close()
        await consumer.stop()
        await producer.stop()
        logger.info("personalization_worker_stopped")


def cli() -> None:
    asyncio.run(run())


if __name__ == "__main__":
    cli()
