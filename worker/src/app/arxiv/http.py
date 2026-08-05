from __future__ import annotations

import asyncio
import random
from collections.abc import Mapping
from typing import Protocol
from urllib.parse import urlsplit

import httpx


class PermitLease(Protocol):
    async def await_permit(self) -> None: ...


def validate_official_endpoint(url: str, allowed_hosts: set[str] | frozenset[str]) -> None:
    parsed = urlsplit(url)
    if parsed.scheme.lower() != "https":
        raise ValueError("arXiv endpoint must use HTTPS")
    approved = {host.lower() for host in allowed_hosts}
    if parsed.hostname is None or parsed.hostname.lower() not in approved:
        raise ValueError("arXiv endpoint must use an approved host")
    if parsed.username is not None or parsed.password is not None or parsed.fragment:
        raise ValueError("arXiv endpoint must not contain credentials or a fragment")


async def request_xml(
    client: httpx.AsyncClient,
    lease: PermitLease,
    url: str,
    params: Mapping[str, str],
    user_agent: str,
    max_response_bytes: int,
    max_retries: int,
) -> bytes:
    if not user_agent.strip() or len(user_agent) > 300:
        raise ValueError("arXiv user agent is invalid")
    if max_response_bytes < 1:
        raise ValueError("maximum response size must be positive")
    if not 0 <= max_retries <= 10:
        raise ValueError("maximum retries must be between zero and ten")
    for attempt in range(max_retries + 1):
        await lease.await_permit()
        try:
            response = await client.get(
                url,
                params=params,
                headers={
                    "Accept": "application/xml, application/atom+xml",
                    "User-Agent": user_agent,
                },
                follow_redirects=False,
            )
            if response.status_code in {429, 500, 502, 503, 504}:
                if attempt == max_retries:
                    response.raise_for_status()
                await _backoff(attempt)
                continue
            response.raise_for_status()
            body = response.content
            if len(body) > max_response_bytes:
                raise ValueError("arXiv response exceeded the configured size limit")
            return body
        except (httpx.TimeoutException, httpx.NetworkError):
            if attempt == max_retries:
                raise
            await _backoff(attempt)
    raise RuntimeError("arXiv request exhausted retry attempts")


async def _backoff(attempt: int) -> None:
    await asyncio.sleep(min(8.0, 0.25 * (2**attempt)) + random.uniform(0.0, 0.2))
