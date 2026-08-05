from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime


@dataclass(frozen=True, slots=True)
class ArxivAuthor:
    name: str
    affiliations: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class ArxivMetadata:
    arxiv_id: str
    version: int | None
    title: str
    abstract: str
    authors: tuple[ArxivAuthor, ...]
    primary_category: str
    categories: tuple[str, ...]
    published_at: datetime | date
    updated_at: datetime | date
    doi: str | None = None
    journal_reference: str | None = None
    comment: str | None = None
    license_url: str | None = None
    pdf_url: str | None = None


@dataclass(frozen=True, slots=True)
class OaiRecord:
    identifier: str
    datestamp: date
    set_specs: tuple[str, ...]
    deleted: bool
    metadata: ArxivMetadata | None


@dataclass(frozen=True, slots=True)
class OaiRecordPage:
    response_date: datetime
    records: tuple[OaiRecord, ...]
    resumption_token: str | None
