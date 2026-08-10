from __future__ import annotations

from datetime import date
from pathlib import Path

import httpx
import pytest

from app.arxiv.oai_client import OaiClient, OaiTokenExpiredError

FIXTURES = Path(__file__).parents[1] / "fixtures" / "arxiv"


class ImmediateLease:
    async def await_permit(self) -> None:
        return None


@pytest.mark.asyncio
async def test_follows_opaque_resumption_tokens_without_combining_arguments() -> None:
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        fixture = "oai-page-1.xml" if len(requests) == 1 else "oai-page-2.xml"
        return httpx.Response(200, content=(FIXTURES / fixture).read_bytes())

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        pages = [
            page
            async for page in OaiClient(
                http,
                ImmediateLease(),
                "https://oaipmh.arxiv.org/oai",
                {"oaipmh.arxiv.org"},
                "agent",
                max_retries=0,
            ).iter_record_pages("cs:cs:AI", date(2026, 8, 1))
        ]

    assert len(pages) == 2
    assert pages[0].resumption_token == "opaque-token-2"
    assert pages[0].records[0].metadata is not None
    assert pages[0].records[0].metadata.authors[0].name == "Ada Lovelace"
    assert pages[1].records[0].deleted is True
    assert dict(requests[0].url.params) == {
        "verb": "ListRecords",
        "metadataPrefix": "arXiv",
        "set": "cs:cs:AI",
        "from": "2026-08-01",
    }
    assert dict(requests[1].url.params) == {
        "verb": "ListRecords",
        "resumptionToken": "opaque-token-2",
    }


@pytest.mark.asyncio
async def test_classifies_expired_resumption_tokens() -> None:
    body = (
        b'<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">'
        b"<responseDate>2026-08-05T00:00:00Z</responseDate>"
        b'<error code="badResumptionToken">expired</error></OAI-PMH>'
    )

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=body)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        client = OaiClient(
            http,
            ImmediateLease(),
            "https://oaipmh.arxiv.org/oai",
            {"oaipmh.arxiv.org"},
            "agent",
            max_retries=0,
        )
        with pytest.raises(OaiTokenExpiredError):
            await client.list_records(resumption_token="opaque-expired")


@pytest.mark.asyncio
async def test_fetches_the_official_list_sets_taxonomy() -> None:
    body = (FIXTURES / "list-sets.xml").read_bytes()
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, content=body)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        categories = await OaiClient(
            http,
            ImmediateLease(),
            "https://oaipmh.arxiv.org/oai",
            {"oaipmh.arxiv.org"},
            "agent",
            max_retries=0,
        ).fetch_taxonomy()

    assert {category.category_id for category in categories} == {
        "astro-ph.GA",
        "cs.AI",
        "math.NA",
        "hep-th",
    }
    assert dict(requests[0].url.params) == {"verb": "ListSets"}
