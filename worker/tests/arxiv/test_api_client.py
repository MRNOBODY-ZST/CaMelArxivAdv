from __future__ import annotations

from pathlib import Path
from typing import Any

import httpx
import pytest

from app.arxiv.api_client import LegacyApiClient

FIXTURES = Path(__file__).parents[1] / "fixtures" / "arxiv"


class ImmediateLease:
    def __init__(self) -> None:
        self.calls = 0

    async def await_permit(self) -> None:
        self.calls += 1


@pytest.mark.asyncio
async def test_fetches_and_parses_legacy_atom_metadata() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params["id_list"] == "2608.00001"
        return httpx.Response(200, content=(FIXTURES / "legacy-page.xml").read_bytes())

    lease = ImmediateLease()
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        papers = await LegacyApiClient(
            http,
            lease,
            "https://export.arxiv.org/api/query",
            {"export.arxiv.org"},
            "CaMelArxivAdv/0.1 (ops@example.invalid)",
            max_retries=0,
        ).fetch_ids(["2608.00001"])

    assert lease.calls == 1
    assert len(papers) == 1
    assert papers[0].arxiv_id == "2608.00001"
    assert papers[0].version == 2
    assert papers[0].title == "Reliable\\n Agents"
    assert papers[0].authors[0].name == "Ada Lovelace"
    assert papers[0].authors[0].affiliations == ("Analytical Institute",)
    assert papers[0].categories == ("cs.AI", "cs.LG")
    assert papers[0].doi == "10.1000/example"


def test_rejects_non_https_and_unapproved_legacy_hosts() -> None:
    lease: Any = ImmediateLease()
    with pytest.raises(ValueError, match="HTTPS"):
        LegacyApiClient(
            httpx.AsyncClient(),
            lease,
            "http://export.arxiv.org/api/query",
            {"export.arxiv.org"},
            "agent",
        )
    with pytest.raises(ValueError, match="approved"):
        LegacyApiClient(
            httpx.AsyncClient(), lease, "https://evil.invalid/api", {"export.arxiv.org"}, "agent"
        )


@pytest.mark.asyncio
async def test_rejects_oversized_and_entity_xml() -> None:
    responses = [b"x" * 101, b'<!DOCTYPE feed [<!ENTITY x "boom">]><feed>&x;</feed>']

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=responses.pop(0))

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        client = LegacyApiClient(
            http,
            ImmediateLease(),
            "https://export.arxiv.org/api/query",
            {"export.arxiv.org"},
            "agent",
            max_response_bytes=100,
            max_retries=0,
        )
        with pytest.raises(ValueError, match="size"):
            await client.fetch_ids(["2608.00001"])
        with pytest.raises(httpx.RemoteProtocolError):
            await client.fetch_ids(["2608.00001"])
