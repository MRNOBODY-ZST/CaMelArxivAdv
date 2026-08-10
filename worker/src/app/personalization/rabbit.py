from __future__ import annotations

from aio_pika import DeliveryMode, Message
from aio_pika.abc import AbstractExchange

from app.personalization.contracts import PersonalizationResult


class PersonalizationResultPublisher:
    def __init__(self, exchange: AbstractExchange) -> None:
        self._exchange = exchange

    async def publish(self, result: PersonalizationResult) -> None:
        await self._exchange.publish(
            Message(
                body=result.model_dump_json(by_alias=True).encode("utf-8"),
                content_type="application/json",
                content_encoding="utf-8",
                delivery_mode=DeliveryMode.PERSISTENT,
                message_id=str(result.message_id),
                timestamp=result.occurred_at,
                type=result.type,
                headers={"contractVersion": result.version},
            ),
            routing_key="mail.personalization.result",
            mandatory=True,
        )
