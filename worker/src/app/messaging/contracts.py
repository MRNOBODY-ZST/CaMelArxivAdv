from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class MessageType(StrEnum):
    ARXIV_QUERY_PREVIEW = "ARXIV_QUERY_PREVIEW"
    ARXIV_IMPORT_METADATA = "ARXIV_IMPORT_METADATA"
    ARXIV_SYNC_TAXONOMY = "ARXIV_SYNC_TAXONOMY"
    ARXIV_SYNC_OAI = "ARXIV_SYNC_OAI"
    ARXIV_FETCH_AND_PARSE_SOURCE = "ARXIV_FETCH_AND_PARSE_SOURCE"
    ARXIV_REEXTRACT_CONTACTS = "ARXIV_REEXTRACT_CONTACTS"
    EXPORT_DATA = "EXPORT_DATA"
    SEND_CAMPAIGN_RECIPIENT = "SEND_CAMPAIGN_RECIPIENT"
    SEND_TEST_EMAIL = "SEND_TEST_EMAIL"
    WORKER_HEARTBEAT = "WORKER_HEARTBEAT"


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class WorkerHeartbeat(ContractModel):
    worker_id: str = Field(min_length=1, max_length=120)
    worker_type: Literal["ARXIV", "MAIL"]
    version: str = Field(min_length=1, max_length=50)
    status: Literal["IDLE", "BUSY", "DRAINING", "UNHEALTHY"]
    current_job_id: UUID | None = None


class MessageEnvelope[PayloadT](ContractModel):
    version: Literal[1] = 1
    message_id: UUID
    type: MessageType
    job_id: UUID | None = None
    idempotency_key: str = Field(min_length=1, max_length=200)
    trace_id: str = Field(pattern=r"^[A-Za-z0-9_-]{8,64}$")
    occurred_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    payload: PayloadT
