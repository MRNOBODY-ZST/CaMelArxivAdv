from __future__ import annotations

import asyncio
import signal

import aio_pika
from aio_pika import ExchangeType

from app.config import PersonalizationSettings
from app.messaging.rabbit import settle_delivery
from app.observability.logging import configure_logging, get_logger
from app.personalization.consumer import PersonalizationCommandProcessor
from app.personalization.rabbit import PersonalizationResultPublisher
from app.personalization.ray_executor import RayPersonalizationExecutor


async def run(settings: PersonalizationSettings | None = None) -> None:
    active = settings or PersonalizationSettings()
    configure_logging(active.log_level)
    logger = get_logger().bind(workerType="PERSONALIZATION")
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for handled_signal in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(handled_signal, stop_event.set)

    connection = await aio_pika.connect_robust(active.rabbitmq_url.get_secret_value())
    executor = RayPersonalizationExecutor(active)
    try:
        channel = await connection.channel(publisher_confirms=True, on_return_raises=True)
        await channel.set_qos(prefetch_count=1)
        jobs = await channel.declare_exchange(
            active.jobs_exchange, ExchangeType.TOPIC, durable=True
        )
        results = await channel.declare_exchange(
            active.results_exchange, ExchangeType.TOPIC, durable=True
        )
        retry = await channel.declare_exchange(
            active.retry_exchange, ExchangeType.TOPIC, durable=True
        )
        await channel.declare_exchange(active.dead_exchange, ExchangeType.TOPIC, durable=True)
        queue = await channel.declare_queue(
            active.jobs_queue,
            durable=True,
            arguments={"x-dead-letter-exchange": active.dead_exchange},
        )
        retry_queue = await channel.declare_queue(
            active.retry_queue,
            durable=True,
            arguments={
                "x-message-ttl": 30_000,
                "x-dead-letter-exchange": active.jobs_exchange,
            },
        )
        await queue.bind(jobs, "mail.personalization.generate")
        await retry_queue.bind(retry, "mail.#")
        processor = PersonalizationCommandProcessor(
            executor,
            PersonalizationResultPublisher(results),
            maximum_command_bytes=active.maximum_command_bytes,
        )

        async def consume(message: aio_pika.abc.AbstractIncomingMessage) -> None:
            outcome = await processor.process(message.body)
            await settle_delivery(message, outcome, retry)

        consumer_tag = await queue.consume(consume, no_ack=False)
        logger.info(
            "personalization_worker_started",
            providerEnabled=active.enabled,
            model=active.model,
        )
        try:
            await stop_event.wait()
        finally:
            await queue.cancel(consumer_tag)
    finally:
        executor.close()
        await connection.close()
        logger.info("personalization_worker_stopped")


def cli() -> None:
    asyncio.run(run())


if __name__ == "__main__":
    cli()
