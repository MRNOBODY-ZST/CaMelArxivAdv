from __future__ import annotations

from app.messaging.kafka import KafkaProducer
from app.personalization.contracts import PersonalizationResult


class PersonalizationResultPublisher:
    def __init__(self, producer: KafkaProducer, topic: str) -> None:
        self._producer = producer
        self._topic = topic

    async def publish(self, result: PersonalizationResult) -> None:
        await self._producer.send_and_wait(
            self._topic,
            value=result.model_dump_json(by_alias=True).encode("utf-8"),
            key=str(result.message_id).encode("ascii"),
            headers=(
                ("messageType", result.type.encode("ascii")),
                ("contractVersion", str(result.version).encode("ascii")),
            ),
        )
