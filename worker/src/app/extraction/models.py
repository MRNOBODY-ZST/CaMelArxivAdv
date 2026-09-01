from __future__ import annotations

import re
from enum import StrEnum

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)

from app.source_safety import contains_email_like_text, unsafe_bounded_text

_LOCAL_PART = re.compile(r"[A-Za-z0-9.!#$%&'*+/=?^_`|~-]{1,64}")
_DOMAIN_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")


def _unsafe_text(value: str, maximum_length: int) -> bool:
    return unsafe_bounded_text(value, maximum_length)


class Confidence(StrEnum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    UNMAPPED = "UNMAPPED"


class ExtractionModel(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


class TexFile(ExtractionModel):
    relative_path: str = Field(min_length=1, max_length=500)
    text: str


class TexCorpus(ExtractionModel):
    root_path: str = Field(min_length=1, max_length=500)
    document_class: str | None = Field(default=None, max_length=100)
    files: tuple[TexFile, ...] = Field(min_length=1, max_length=5000)


class ExtractedAuthor(ExtractionModel):
    order: int = Field(ge=1, le=500)
    name: str = Field(min_length=1, max_length=300)
    affiliations: tuple[str, ...] = Field(default=(), max_length=100)
    corresponding: bool = False

    @field_validator("name")
    @classmethod
    def safe_name(cls, value: str) -> str:
        if _unsafe_text(value, 300) or contains_email_like_text(value):
            raise ValueError("Extracted author name is unsafe")
        return value

    @field_validator("affiliations")
    @classmethod
    def safe_affiliations(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if any(
            _unsafe_text(value, 2000) or contains_email_like_text(value)
            for value in values
        ):
            raise ValueError("Extracted author affiliation is unsafe")
        return values


class ExtractionEvidence(ExtractionModel):
    source_relative_path: str = Field(min_length=1, max_length=500)
    rule_name: str = Field(min_length=1, max_length=120)
    line_number: int | None = Field(default=None, ge=1)
    logical_location: str = Field(min_length=1, max_length=120)
    masked_context: str = Field(min_length=1, max_length=600)

    @model_validator(mode="after")
    def backend_safe_evidence(self) -> ExtractionEvidence:
        parts = self.source_relative_path.split("/")
        if (
            self.source_relative_path.startswith("/")
            or "\\" in self.source_relative_path
            or any(part in {"", ".", ".."} for part in parts)
            or _unsafe_text(self.source_relative_path, 500)
            or contains_email_like_text(self.source_relative_path)
            or re.fullmatch(r"[A-Z0-9_]{1,120}", self.rule_name) is None
            or re.fullmatch(r"[A-Z0-9_]{1,120}", self.logical_location) is None
            or _unsafe_text(self.masked_context, 600)
            or contains_email_like_text(self.masked_context)
        ):
            raise ValueError("Extraction evidence is unsafe")
        return self


class ExtractedContact(ExtractionModel):
    normalized_email: str = Field(min_length=3, max_length=320)
    display_email: str = Field(min_length=3, max_length=320)
    domain: str = Field(min_length=1, max_length=255)
    syntax_valid: bool
    example_address: bool = False
    author_order: int | None = Field(default=None, ge=1, le=500)
    confidence: Confidence
    corresponding: bool = False
    evidence: tuple[ExtractionEvidence, ...] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def backend_safe_contact(self) -> ExtractedContact:
        if self.normalized_email.count("@") != 1:
            raise ValueError("Extracted normalized email is invalid")
        local, actual_domain = self.normalized_email.rsplit("@", 1)
        if (
            self.normalized_email != self.normalized_email.lower()
            or _LOCAL_PART.fullmatch(local) is None
            or self.domain != actual_domain
            or len(self.domain) > 255
            or "." not in self.domain
            or any(
                _DOMAIN_LABEL.fullmatch(label) is None for label in self.domain.split(".")
            )
            or _unsafe_text(self.display_email, 320)
            or not self.syntax_valid
        ):
            raise ValueError("Extracted contact is incompatible with the result contract")
        for item in self.evidence:
            context = item.masked_context.casefold()
            if (
                self.normalized_email.casefold() in context
                or self.display_email.casefold() in context
            ):
                raise ValueError("Extraction evidence contains a complete email address")
        return self


class ExtractionDocument(ExtractionModel):
    document_class: str | None = Field(default=None, max_length=100)
    files_inspected: int = Field(ge=1, le=5000)
    authors: tuple[ExtractedAuthor, ...] = Field(default=(), max_length=500)
    contacts: tuple[ExtractedContact, ...] = Field(default=(), max_length=500)

    @field_validator("document_class")
    @classmethod
    def safe_document_class(cls, value: str | None) -> str | None:
        if value is not None and (
            _unsafe_text(value, 100) or contains_email_like_text(value)
        ):
            raise ValueError("Document class is unsafe")
        return value
