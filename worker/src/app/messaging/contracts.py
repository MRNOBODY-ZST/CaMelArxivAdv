from __future__ import annotations

import re
from datetime import UTC, datetime
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationInfo,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from app.source_safety import contains_email_like_text, unsafe_bounded_text

_SOURCE_LOCAL_PART = re.compile(r"[A-Za-z0-9.!#$%&'*+/=?^_`|~-]{1,64}")
_SOURCE_DOMAIN_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")


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
    ARXIV_EXTRACTION_RESULT = "ARXIV_EXTRACTION_RESULT"
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


class SourceTarget(ContractModel):
    paper_id: UUID
    arxiv_id: str = Field(
        pattern=r"^(?:[0-9]{4}\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})$",
        max_length=48,
    )
    metadata_authors: tuple[str, ...] = Field(default=(), max_length=500)

    @field_validator("metadata_authors")
    @classmethod
    def safe_metadata_authors(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if any(
            unsafe_bounded_text(value, 300)
            for value in values
        ):
            raise ValueError("Source target metadata authors are invalid")
        return values


class SourceExtractionCommand(ContractModel):
    targets: tuple[SourceTarget, ...] = Field(min_length=1, max_length=100)
    parser_version: str = Field(pattern=r"^[A-Za-z0-9._-]{1,50}$")

    @model_validator(mode="after")
    def unique_targets(self) -> SourceExtractionCommand:
        paper_ids = {target.paper_id for target in self.targets}
        arxiv_ids = {target.arxiv_id for target in self.targets}
        if len(paper_ids) != len(self.targets) or len(arxiv_ids) != len(self.targets):
            raise ValueError("Source extraction targets must be unique")
        return self


class SourceAuthor(ContractModel):
    order: int = Field(ge=1, le=500)
    name: str = Field(min_length=1, max_length=300)
    affiliations: tuple[str, ...] = Field(default=(), max_length=100)
    corresponding: bool = False

    @field_validator("name")
    @classmethod
    def safe_name(cls, value: str) -> str:
        if unsafe_bounded_text(value, 300) or contains_email_like_text(value):
            raise ValueError("Source author name is unsafe")
        return value

    @field_validator("affiliations")
    @classmethod
    def safe_affiliations(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if any(
            unsafe_bounded_text(value, 2000) or contains_email_like_text(value)
            for value in values
        ):
            raise ValueError("Source author affiliation is unsafe")
        return values


class SourceEvidence(ContractModel):
    source_relative_path: str = Field(min_length=1, max_length=500)
    rule_name: str = Field(pattern=r"^[A-Z0-9_]{1,120}$")
    line_number: int | None = Field(default=None, ge=1)
    logical_location: str = Field(pattern=r"^[A-Z0-9_]{1,120}$")
    masked_context: str = Field(min_length=1, max_length=600)

    @model_validator(mode="after")
    def safe_path(self) -> SourceEvidence:
        parts = self.source_relative_path.split("/")
        if (
            self.source_relative_path.startswith("/")
            or "\\" in self.source_relative_path
            or any(part in {"", ".", ".."} for part in parts)
            or unsafe_bounded_text(self.source_relative_path, 500)
            or contains_email_like_text(self.source_relative_path)
            or unsafe_bounded_text(self.masked_context, 600)
            or contains_email_like_text(self.masked_context)
        ):
            raise ValueError("Source evidence path is unsafe")
        return self


class SourceContact(ContractModel):
    normalized_email: str = Field(min_length=3, max_length=320)
    display_email: str = Field(min_length=3, max_length=320)
    domain: str = Field(min_length=1, max_length=255)
    syntax_valid: bool
    example_address: bool = False
    author_order: int | None = Field(default=None, ge=1, le=500)
    confidence: Literal["HIGH", "MEDIUM", "LOW", "UNMAPPED"]
    corresponding: bool = False
    evidence: tuple[SourceEvidence, ...] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def no_plaintext_evidence(self) -> SourceContact:
        if self.normalized_email.count("@") != 1:
            raise ValueError("Source contact normalized email is invalid")
        local, actual_domain = self.normalized_email.rsplit("@", 1)
        if (
            self.normalized_email != self.normalized_email.lower()
            or _SOURCE_LOCAL_PART.fullmatch(local) is None
            or local.startswith(".")
            or local.endswith(".")
            or ".." in local
            or self.domain != actual_domain
            or len(self.domain) > 255
            or "." not in self.domain
            or any(
                _SOURCE_DOMAIN_LABEL.fullmatch(label) is None
                for label in self.domain.split(".")
            )
            or unsafe_bounded_text(self.display_email, 320)
            or not self.syntax_valid
        ):
            raise ValueError("Source contact display email is unsafe")
        for item in self.evidence:
            context = item.masked_context.casefold()
            if (
                self.normalized_email.casefold() in context
                or self.display_email.casefold() in context
            ):
                raise ValueError("Source evidence contains a complete email address")
        return self


class SourceExtractionResult(ContractModel):
    paper_id: UUID
    arxiv_id: str = Field(
        pattern=r"^(?:[0-9]{4}\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})$"
    )
    parser_version: str = Field(pattern=r"^[A-Za-z0-9._-]{1,50}$")
    status: Literal[
        "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "SECURITY_REJECTED", "SOURCE_UNAVAILABLE"
    ]
    cleanup_confirmed: bool
    source_format: str | None = Field(default=None, max_length=50)
    archive_size_bytes: int = Field(default=0, ge=0)
    extracted_size_bytes: int = Field(default=0, ge=0)
    files_inspected: int = Field(default=0, ge=0, le=5000)
    duration_ms: int = Field(default=0, ge=0)
    document_class: str | None = Field(default=None, max_length=100)
    authors: tuple[SourceAuthor, ...] = Field(default=(), max_length=500)
    contacts: tuple[SourceContact, ...] = Field(default=(), max_length=500)
    error_code: str | None = Field(default=None, pattern=r"^[A-Z0-9_]{1,80}$")
    error_summary: str | None = Field(default=None, max_length=500)

    @field_validator("source_format", "document_class", "error_summary")
    @classmethod
    def java_bounded_optional_text(
        cls, value: str | None, info: ValidationInfo
    ) -> str | None:
        if value is None:
            return None
        limits = {"source_format": 50, "document_class": 100, "error_summary": 500}
        field_name = info.field_name
        if field_name is None:
            raise ValueError("Source result field identity is unavailable")
        if unsafe_bounded_text(value, limits[field_name]) or contains_email_like_text(
            value
        ):
            raise ValueError("Source result text is unsafe")
        return value

    @model_validator(mode="after")
    def consistent_result(self) -> SourceExtractionResult:
        if not self.cleanup_confirmed:
            raise ValueError("Source cleanup must be confirmed before publishing")
        if self.status in {"SUCCEEDED", "PARTIALLY_SUCCEEDED"}:
            if (
                self.source_format is None
                or self.files_inspected < 1
                or self.error_code is not None
            ):
                raise ValueError("Successful Source extraction result is incomplete")
        elif self.error_code is None:
            raise ValueError("Failed Source extraction result requires a safe error code")
        author_orders = {author.order for author in self.authors}
        if len(author_orders) != len(self.authors) or any(
            item.author_order is not None and item.author_order not in author_orders
            for item in self.contacts
        ):
            raise ValueError("Source contact author mapping is invalid")
        return self


class ResultPayload(ContractModel):
    status: Literal[
        "RUNNING", "PAUSED", "CANCELED", "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED"
    ]
    stage: str = Field(min_length=1, max_length=80)
    processed_count: int = Field(default=0, ge=0)
    success_count: int = Field(default=0, ge=0)
    skipped_count: int = Field(default=0, ge=0)
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
    extractions: tuple[SourceExtractionResult, ...] = Field(default=(), max_length=10)

    @field_validator("error_summary")
    @classmethod
    def safe_error_summary(cls, value: str | None) -> str | None:
        if value is not None and (
            unsafe_bounded_text(value, 500) or contains_email_like_text(value)
        ):
            raise ValueError("Result error summary is unsafe")
        return value


class MessageEnvelope[PayloadT](ContractModel):
    version: Literal[1] = 1
    message_id: UUID
    type: MessageType
    job_id: UUID | None = None
    idempotency_key: str = Field(min_length=1, max_length=200)
    trace_id: str = Field(pattern=r"^[A-Za-z0-9_-]{8,64}$")
    occurred_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    payload: PayloadT
