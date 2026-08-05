from __future__ import annotations

import re
from collections.abc import Sequence
from urllib.parse import urlsplit
from xml.etree.ElementTree import Element

import httpx

from app.arxiv.http import PermitLease, request_xml, validate_official_endpoint
from app.arxiv.models import ArxivAuthor, ArxivMetadata
from app.arxiv.xml import normalized_text, optional_text, parse_datetime, required_text, secure_root

ATOM = "{http://www.w3.org/2005/Atom}"
ARXIV = "{http://arxiv.org/schemas/atom}"
_VERSION = re.compile(r"^(?P<id>.+?)(?:v(?P<version>[0-9]+))?$")


class LegacyApiClient:
    def __init__(
        self,
        client: httpx.AsyncClient,
        lease: PermitLease,
        base_url: str,
        allowed_hosts: set[str] | frozenset[str],
        user_agent: str,
        *,
        max_response_bytes: int = 5 * 1024 * 1024,
        max_retries: int = 3,
    ) -> None:
        validate_official_endpoint(base_url, allowed_hosts)
        self._client = client
        self._lease = lease
        self._base_url = base_url
        self._user_agent = user_agent
        self._max_response_bytes = max_response_bytes
        self._max_retries = max_retries

    async def fetch_ids(self, arxiv_ids: Sequence[str]) -> tuple[ArxivMetadata, ...]:
        if not arxiv_ids or len(arxiv_ids) > 100:
            raise ValueError("Legacy API ID batch must contain between one and 100 IDs")
        body = await request_xml(
            self._client,
            self._lease,
            self._base_url,
            {"id_list": ",".join(arxiv_ids), "start": "0", "max_results": str(len(arxiv_ids))},
            self._user_agent,
            self._max_response_bytes,
            self._max_retries,
        )
        return parse_atom_feed(body)

    async def search_page(
        self,
        search_query: str,
        start: int,
        max_results: int,
        sort_by: str,
        sort_order: str,
    ) -> tuple[ArxivMetadata, ...]:
        if not search_query or start < 0 or not 1 <= max_results <= 100:
            raise ValueError("Legacy API search page is invalid")
        sort_names = {
            "RELEVANCE": "relevance",
            "LAST_UPDATED_DATE": "lastUpdatedDate",
            "SUBMITTED_DATE": "submittedDate",
        }
        order_names = {"ASCENDING": "ascending", "DESCENDING": "descending"}
        if sort_by not in sort_names or sort_order not in order_names:
            raise ValueError("Legacy API sort is invalid")
        body = await request_xml(
            self._client,
            self._lease,
            self._base_url,
            {
                "search_query": search_query,
                "start": str(start),
                "max_results": str(max_results),
                "sortBy": sort_names[sort_by],
                "sortOrder": order_names[sort_order],
            },
            self._user_agent,
            self._max_response_bytes,
            self._max_retries,
        )
        return parse_atom_feed(body)


def parse_atom_feed(body: bytes) -> tuple[ArxivMetadata, ...]:
    root = secure_root(body)
    if root.tag != f"{ATOM}feed":
        raise ValueError("Legacy API response is not an Atom feed")
    return tuple(_parse_entry(entry) for entry in root.findall(f"{ATOM}entry"))


def _parse_entry(node: Element[str]) -> ArxivMetadata:
    raw_id = required_text(node.find(f"{ATOM}id"), "entry ID")
    versioned = urlsplit(raw_id).path.rsplit("/", 1)[-1]
    match = _VERSION.fullmatch(versioned)
    if match is None:
        raise ValueError("Legacy API returned an invalid arXiv ID")
    categories = tuple(
        term
        for category in node.findall(f"{ATOM}category")
        if (term := normalized_text(category.attrib.get("term")))
    )
    primary = node.find(f"{ARXIV}primary_category")
    primary_category = normalized_text(primary.attrib.get("term") if primary is not None else None)
    if not primary_category:
        raise ValueError("Legacy API response is missing primary category")
    authors = tuple(_parse_author(author) for author in node.findall(f"{ATOM}author"))
    pdf_url: str | None = None
    license_url: str | None = None
    for link in node.findall(f"{ATOM}link"):
        if link.attrib.get("title") == "pdf":
            pdf_url = link.attrib.get("href")
        if link.attrib.get("rel") == "license":
            license_url = link.attrib.get("href")
    return ArxivMetadata(
        arxiv_id=match.group("id"),
        version=int(match.group("version")) if match.group("version") else None,
        title=required_text(node.find(f"{ATOM}title"), "title"),
        abstract=required_text(node.find(f"{ATOM}summary"), "abstract"),
        authors=authors,
        primary_category=primary_category,
        categories=categories,
        published_at=parse_datetime(required_text(node.find(f"{ATOM}published"), "published date")),
        updated_at=parse_datetime(required_text(node.find(f"{ATOM}updated"), "updated date")),
        doi=optional_text(node.find(f"{ARXIV}doi")),
        journal_reference=optional_text(node.find(f"{ARXIV}journal_ref")),
        comment=optional_text(node.find(f"{ARXIV}comment")),
        license_url=license_url,
        pdf_url=pdf_url,
    )


def _parse_author(node: Element[str]) -> ArxivAuthor:
    name = required_text(node.find(f"{ATOM}name"), "author name")
    affiliations = tuple(
        value
        for element in node.findall(f"{ARXIV}affiliation")
        if (value := normalized_text(element.text))
    )
    return ArxivAuthor(name=name, affiliations=affiliations)
