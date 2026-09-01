from __future__ import annotations

import re

_DOMAIN_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
_ASCII_LETTER = re.compile(r"[a-z]")


def _valid_domain_label(label: str) -> bool:
    if _DOMAIN_LABEL.fullmatch(label) is None:
        return False
    if not label.startswith("xn--"):
        return True
    try:
        decoded = label.encode("ascii").decode("idna")
        return decoded.encode("idna").decode("ascii") == label
    except UnicodeError:
        return False


def has_public_dns_name_syntax(domain: str) -> bool:
    """Return whether an ASCII domain has public DNS-name syntax."""
    if len(domain) > 253 or "." not in domain:
        return False
    labels = domain.split(".")
    top_level = labels[-1]
    return (
        all(_valid_domain_label(label) for label in labels)
        and len(top_level) >= 2
        and _ASCII_LETTER.search(top_level) is not None
    )
