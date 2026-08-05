from __future__ import annotations

from datetime import UTC, datetime

from aio_pika import DeliveryMode, Message
from aio_pika.abc import AbstractExchange, AbstractIncomingMessage

from app.jobs.arxiv_consumer import CommandOutcome
from app.messaging.contracts import MessageEnvelope, ResultPayload


class RabbitResultPublisher:
    def __init__(self, exchange: AbstractExchange) -> None:
        self._exchange = exchange

    async def publish(self, message: MessageEnvelope[ResultPayload]) -> None:
        await self._exchange.publish(
            Message(
                body=message.model_dump_json(by_alias=True).encode("utf-8"),
                content_type="application/json",
                content_encoding="utf-8",
                delivery_mode=DeliveryMode.PERSISTENT,
                message_id=str(message.message_id),
                timestamp=message.occurred_at,
                type=message.type.value,
                headers={"contractVersion": message.version},
            ),
            routing_key=f"arxiv.{message.type.value.lower()}",
            mandatory=True,
        )


async def settle_delivery(
    incoming: AbstractIncomingMessage,
    outcome: CommandOutcome,
    retry_exchange: AbstractExchange,
) -> None:
    if outcome is CommandOutcome.ACK:
        await incoming.ack()
        return
    if outcome is CommandOutcome.DEAD:
        await incoming.reject(requeue=False)
        return
    await retry_exchange.publish(
        Message(
            body=incoming.body,
            content_type=incoming.content_type or "application/json",
            content_encoding=incoming.content_encoding or "utf-8",
            delivery_mode=DeliveryMode.PERSISTENT,
            message_id=incoming.message_id,
            timestamp=datetime.now(UTC),
            type=incoming.type,
            headers=dict(incoming.headers or {}),
        ),
        routing_key=incoming.routing_key or "arxiv.retry.unknown",
        mandatory=True,
    )
    await incoming.ack()
