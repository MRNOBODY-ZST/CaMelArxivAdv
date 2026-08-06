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
    ARXIV_JOB_STARTED = "ARXIV_JOB_STARTED"
    ARXIV_JOB_PROGRESS = "ARXIV_JOB_PROGRESS"
    ARXIV_JOB_BATCH = "ARXIV_JOB_BATCH"
    ARXIV_JOB_COMPLETED = "ARXIV_JOB_COMPLETED"
    ARXIV_JOB_FAILED = "ARXIV_JOB_FAILED"


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class WorkerHeartbeat(ContractModel):
    worker_id: str = Field(min_length=1, max_length=120)
    worker_type: Literal["ARXIV", "MAIL"]
    version: str = Field(min_length=1, max_length=50)
    status: Literal["IDLE", "BUSY", "DRAINING", "UNHEALTHY"]
    current_job_id: UUID | None = None


class ImportMetadataCommand(ContractModel):
    mode: Literal["SELECTED", "CRITERIA"]
    arxiv_ids: tuple[str, ...] = Field(default=(), max_length=10_000)
    criteria: dict[str, object] | None = None
    criteria_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    max_papers: int = Field(ge=1, le=1_000_000)


class OaiSyncCommand(ContractModel):
    set_spec: str = Field(pattern=r"^[A-Za-z0-9.-]{1,60}(?::[A-Za-z0-9.-]{1,60}){0,2}$")
    from_date: str | None = Field(
        default=None, alias="from", pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"
    )


class TaxonomySyncCommand(ContractModel):
    requested_date: str = Field(pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")


class ResultPayload(ContractModel):
    status: Literal["RUNNING", "PAUSED", "CANCELED", "SUCCEEDED", "FAILED"]
    stage: str = Field(min_length=1, max_length=80)
    processed_count: int = Field(default=0, ge=0)
    success_count: int = Field(default=0, ge=0)
    failed_count: int = Field(default=0, ge=0)
    total_count: int = Field(default=0, ge=0)
    progress_percent: float = Field(default=0, ge=0, le=100)
    checkpoint: dict[str, object] = Field(default_factory=dict)
    papers: tuple[dict[str, object], ...] = Field(default=(), max_length=100)
    error_code: str | None = Field(default=None, max_length=80)
    error_summary: str | None = Field(default=None, max_length=500)
    snapshot_version: str | None = Field(default=None, max_length=80)
    taxonomy_source_updated_at: datetime | None = None
    taxonomy_categories: tuple[dict[str, object], ...] = Field(default=(), max_length=500)


class MessageEnvelope[PayloadT](ContractModel):
    version: Literal[1] = 1
    message_id: UUID
    type: MessageType
    job_id: UUID | None = None
    idempotency_key: str = Field(min_length=1, max_length=200)
    trace_id: str = Field(pattern=r"^[A-Za-z0-9_-]{8,64}$")
    occurred_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    payload: PayloadT
