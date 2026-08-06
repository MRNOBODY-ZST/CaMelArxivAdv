from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field


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


class ExtractionEvidence(ExtractionModel):
    source_relative_path: str = Field(min_length=1, max_length=500)
    rule_name: str = Field(min_length=1, max_length=120)
    line_number: int | None = Field(default=None, ge=1)
    logical_location: str = Field(min_length=1, max_length=120)
    masked_context: str = Field(min_length=1, max_length=600)


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


class ExtractionDocument(ExtractionModel):
    document_class: str | None = Field(default=None, max_length=100)
    files_inspected: int = Field(ge=1, le=5000)
    authors: tuple[ExtractedAuthor, ...] = Field(default=(), max_length=500)
    contacts: tuple[ExtractedContact, ...] = Field(default=(), max_length=500)
