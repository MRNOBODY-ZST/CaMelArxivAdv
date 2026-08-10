from __future__ import annotations

from datetime import datetime
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class ContractModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


class PersonalizationTarget(ContractModel):
    recipient_id: UUID
    author_name: str = Field(min_length=1, max_length=300)
    paper_title: str = Field(min_length=1, max_length=2_000)
    paper_abstract: str = Field(min_length=1, max_length=20_000)
    arxiv_id: str = Field(
        pattern=r"^(?:[0-9]{4}\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})$",
        max_length=48,
    )
    primary_category: str | None = Field(default=None, max_length=80)
    paper_url: str = Field(pattern=r"^https://arxiv\.org/abs/[^\s]{1,80}$", max_length=120)
    organization: str | None = Field(default=None, max_length=500)


class PersonalizationPayload(ContractModel):
    purpose: str = Field(min_length=1, max_length=4_000)
    template_subject: str = Field(min_length=1, max_length=998)
    template_html: str = Field(min_length=1, max_length=200_000)
    template_text: str = Field(min_length=1, max_length=100_000)
    targets: tuple[PersonalizationTarget, ...] = Field(min_length=1, max_length=1_000)

    @model_validator(mode="after")
    def unique_recipients(self) -> PersonalizationPayload:
        recipient_ids = {target.recipient_id for target in self.targets}
        if len(recipient_ids) != len(self.targets):
            raise ValueError("Personalization recipient IDs must be unique")
        return self


class PersonalizationCommand(ContractModel):
    version: Literal[1]
    message_id: UUID
    type: Literal["PERSONALIZE_CAMPAIGN"]
    job_id: UUID
    campaign_id: UUID
    idempotency_key: str = Field(min_length=1, max_length=200)
    trace_id: str = Field(pattern=r"^[A-Za-z0-9_-]{8,64}$")
    occurred_at: datetime
    payload: PersonalizationPayload


class GeneratedEmail(ContractModel):
    subject: str = Field(min_length=1, max_length=998)
    html: str = Field(min_length=1, max_length=200_000)
    text: str = Field(min_length=1, max_length=100_000)
    rationale: str = Field(min_length=1, max_length=2_000)

    @model_validator(mode="after")
    def contains_unsubscribe_variable(self) -> GeneratedEmail:
        if "{{unsubscribe_url}}" not in self.html or "{{unsubscribe_url}}" not in self.text:
            raise ValueError("Generated email must preserve unsubscribe_url in HTML and text")
        return self


class PersonalizationResultPayload(ContractModel):
    status: Literal["GENERATED", "FAILED"]
    subject: str | None = Field(default=None, max_length=998)
    html: str | None = Field(default=None, max_length=200_000)
    text: str | None = Field(default=None, max_length=100_000)
    rationale: str | None = Field(default=None, max_length=2_000)
    provider: str = Field(min_length=1, max_length=80)
    model: str = Field(min_length=1, max_length=120)
    error_code: str | None = Field(default=None, pattern=r"^[A-Z0-9_]{1,80}$")
    error_message: str | None = Field(default=None, max_length=500)

    @model_validator(mode="after")
    def consistent_status(self) -> PersonalizationResultPayload:
        content = (self.subject, self.html, self.text, self.rationale)
        if self.status == "GENERATED":
            if any(value is None or not value.strip() for value in content):
                raise ValueError("Generated result is missing content")
            GeneratedEmail(
                subject=self.subject,
                html=self.html,
                text=self.text,
                rationale=self.rationale,
            )
            if self.error_code is not None or self.error_message is not None:
                raise ValueError("Generated result cannot contain an error")
        elif (
            any(value is not None for value in content)
            or not self.error_code
            or not self.error_message
        ):
            raise ValueError("Failed result must contain only a safe error")
        return self


class PersonalizationResult(ContractModel):
    version: Literal[1] = 1
    message_id: UUID
    type: Literal["PERSONALIZATION_RESULT"] = "PERSONALIZATION_RESULT"
    job_id: UUID
    campaign_id: UUID
    recipient_id: UUID
    idempotency_key: str = Field(min_length=1, max_length=200)
    trace_id: str = Field(pattern=r"^[A-Za-z0-9_-]{8,64}$")
    occurred_at: datetime
    payload: PersonalizationResultPayload
