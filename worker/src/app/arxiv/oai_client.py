from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import date
from xml.etree.ElementTree import Element

import httpx

from app.arxiv.http import PermitLease, request_xml, validate_official_endpoint
from app.arxiv.models import ArxivAuthor, ArxivMetadata, OaiRecord, OaiRecordPage
from app.arxiv.xml import (
    normalized_text,
    optional_text,
    parse_date,
    parse_datetime,
    required_text,
    secure_root,
)

OAI = "{http://www.openarchives.org/OAI/2.0/}"
ARXIV = "{http://arxiv.org/OAI/arXiv/}"


class OaiProtocolError(RuntimeError):
    pass


class OaiTokenExpiredError(OaiProtocolError):
    pass


class OaiClient:
    def __init__(
        self,
        client: httpx.AsyncClient,
        lease: PermitLease,
        base_url: str,
        allowed_hosts: set[str] | frozenset[str],
        user_agent: str,
        *,
        max_response_bytes: int = 10 * 1024 * 1024,
        max_retries: int = 3,
    ) -> None:
        validate_official_endpoint(base_url, allowed_hosts)
        self._client = client
        self._lease = lease
        self._base_url = base_url
        self._user_agent = user_agent
        self._max_response_bytes = max_response_bytes
        self._max_retries = max_retries

    async def list_records(
        self,
        set_spec: str | None = None,
        from_date: date | None = None,
        *,
        resumption_token: str | None = None,
    ) -> OaiRecordPage:
        if resumption_token is not None:
            if not resumption_token.strip() or len(resumption_token) > 4000:
                raise ValueError("OAI resumption token is invalid")
            params = {"verb": "ListRecords", "resumptionToken": resumption_token}
        else:
            if set_spec is None or not set_spec.strip():
                raise ValueError("OAI set is required")
            params = {"verb": "ListRecords", "metadataPrefix": "arXiv", "set": set_spec}
            if from_date is not None:
                params["from"] = from_date.isoformat()
        body = await request_xml(
            self._client,
            self._lease,
            self._base_url,
            params,
            self._user_agent,
            self._max_response_bytes,
            self._max_retries,
        )
        return parse_oai_page(body)

    async def iter_record_pages(
        self, set_spec: str, from_date: date | None = None
    ) -> AsyncIterator[OaiRecordPage]:
        page = await self.list_records(set_spec, from_date)
        yield page
        while page.resumption_token is not None:
            page = await self.list_records(resumption_token=page.resumption_token)
            yield page


def parse_oai_page(body: bytes) -> OaiRecordPage:
    root = secure_root(body)
    if root.tag != f"{OAI}OAI-PMH":
        raise ValueError("OAI response root is invalid")
    error = root.find(f"{OAI}error")
    if error is not None:
        code = error.attrib.get("code", "unknown")
        if code == "badResumptionToken":
            raise OaiTokenExpiredError("OAI resumption token expired or became invalid")
        raise OaiProtocolError(f"OAI request failed with {code}")
    response_date = parse_datetime(required_text(root.find(f"{OAI}responseDate"), "response date"))
    container = root.find(f"{OAI}ListRecords")
    if container is None:
        raise ValueError("OAI response does not contain ListRecords")
    records = tuple(_parse_record(record) for record in container.findall(f"{OAI}record"))
    token = optional_text(container.find(f"{OAI}resumptionToken"))
    return OaiRecordPage(response_date=response_date, records=records, resumption_token=token)


def _parse_record(node: Element[str]) -> OaiRecord:
    header = node.find(f"{OAI}header")
    if header is None:
        raise ValueError("OAI record is missing a header")
    identifier = required_text(header.find(f"{OAI}identifier"), "record identifier")
    datestamp = parse_date(required_text(header.find(f"{OAI}datestamp"), "record datestamp"))
    set_specs = tuple(required_text(value, "set spec") for value in header.findall(f"{OAI}setSpec"))
    deleted = header.attrib.get("status") == "deleted"
    if deleted:
        return OaiRecord(identifier, datestamp, set_specs, True, None)
    metadata_container = node.find(f"{OAI}metadata")
    metadata = metadata_container.find(f"{ARXIV}arXiv") if metadata_container is not None else None
    if metadata is None:
        raise ValueError("OAI record is missing arXiv metadata")
    return OaiRecord(identifier, datestamp, set_specs, False, _parse_metadata(metadata))


def _parse_metadata(element: Element[str]) -> ArxivMetadata:
    authors_node = element.find(f"{ARXIV}authors")
    authors = tuple(
        _parse_author(author)
        for author in (() if authors_node is None else authors_node.findall(f"{ARXIV}author"))
    )
    categories = tuple(required_text(element.find(f"{ARXIV}categories"), "categories").split())
    if not categories:
        raise ValueError("OAI record is missing categories")
    created = parse_date(required_text(element.find(f"{ARXIV}created"), "created date"))
    updated_text = optional_text(element.find(f"{ARXIV}updated"))
    return ArxivMetadata(
        arxiv_id=required_text(element.find(f"{ARXIV}id"), "arXiv ID"),
        version=None,
        title=required_text(element.find(f"{ARXIV}title"), "title"),
        abstract=required_text(element.find(f"{ARXIV}abstract"), "abstract"),
        authors=authors,
        primary_category=categories[0],
        categories=categories,
        published_at=created,
        updated_at=parse_date(updated_text) if updated_text else created,
        doi=optional_text(element.find(f"{ARXIV}doi")),
        journal_reference=optional_text(element.find(f"{ARXIV}journal-ref")),
        comment=optional_text(element.find(f"{ARXIV}comments")),
        license_url=optional_text(element.find(f"{ARXIV}license")),
    )


def _parse_author(node: Element[str]) -> ArxivAuthor:
    keyname = required_text(node.find(f"{ARXIV}keyname"), "author key name")
    forenames = optional_text(node.find(f"{ARXIV}forenames"))
    suffix = optional_text(node.find(f"{ARXIV}suffix"))
    name = normalized_text(" ".join(value for value in (forenames, keyname, suffix) if value))
    affiliations = tuple(
        value
        for item in node.findall(f"{ARXIV}affiliation")
        if (value := normalized_text(item.text))
    )
    return ArxivAuthor(name=name, affiliations=affiliations)
