from __future__ import annotations

from datetime import UTC, date, datetime
from xml.etree.ElementTree import Element

from defusedxml import ElementTree
from defusedxml.common import DefusedXmlException


def secure_root(body: bytes) -> Element:
    try:
        return ElementTree.fromstring(body)
    except (DefusedXmlException, ElementTree.ParseError) as exception:
        raise ValueError("arXiv XML could not be parsed safely") from exception


def normalized_text(value: str | None) -> str:
    return " ".join((value or "").split())


def required_text(element: Element | None, field: str) -> str:
    value = normalized_text(element.text if element is not None else None)
    if not value:
        raise ValueError(f"arXiv XML is missing {field}")
    return value


def optional_text(element: Element | None) -> str | None:
    value = normalized_text(element.text if element is not None else None)
    return value or None


def parse_datetime(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=UTC)


def parse_date(value: str) -> date:
    return date.fromisoformat(value)
