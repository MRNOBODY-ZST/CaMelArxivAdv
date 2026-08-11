from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.config import Settings
from app.main import WorkerRuntimeState, build_heartbeat_message
from app.messaging.contracts import MessageEnvelope, MessageType, WorkerHeartbeat


def test_message_requires_supported_version() -> None:
    with pytest.raises(ValidationError):
        MessageEnvelope.model_validate(
            {
                "version": 99,
                "messageId": str(uuid4()),
                "type": "ARXIV_SYNC_TAXONOMY",
                "idempotencyKey": "taxonomy:2026-08-05",
                "traceId": "0123456789abcdef0123456789abcdef",
                "payload": {},
            }
        )


def test_heartbeat_serializes_with_versioned_camel_case_contract() -> None:
    message_id = uuid4()
    heartbeat = MessageEnvelope[WorkerHeartbeat](
        message_id=message_id,
        type=MessageType.WORKER_HEARTBEAT,
        idempotency_key=f"heartbeat:{message_id}",
        trace_id="0123456789abcdef0123456789abcdef",
        occurred_at=datetime(2026, 8, 5, tzinfo=UTC),
        payload=WorkerHeartbeat(
            worker_id="worker-test-1",
            worker_type="ARXIV",
            version="0.1.0",
            status="IDLE",
        ),
    )

    serialized = heartbeat.model_dump(mode="json", by_alias=True)

    assert serialized["version"] == 1
    assert serialized["messageId"] == str(message_id)
    assert serialized["type"] == "WORKER_HEARTBEAT"
    assert serialized["payload"]["workerId"] == "worker-test-1"
    assert "message_id" not in serialized


def test_runtime_heartbeat_contains_no_connection_secret() -> None:
    heartbeat = build_heartbeat_message(
        Settings(redis_url="redis://user:password@redis:6379/0")
    )
    serialized = heartbeat.model_dump_json(by_alias=True)

    assert heartbeat.type == MessageType.WORKER_HEARTBEAT
    assert "password" not in serialized
    assert "redis_url" not in serialized


def test_runtime_heartbeat_reports_the_active_job() -> None:
    job_id = uuid4()
    heartbeat = build_heartbeat_message(
        Settings(), WorkerRuntimeState(status="BUSY", current_job_id=job_id)
    )

    assert heartbeat.payload.status == "BUSY"
    assert heartbeat.payload.current_job_id == job_id
