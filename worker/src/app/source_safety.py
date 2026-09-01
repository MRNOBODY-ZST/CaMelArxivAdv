from __future__ import annotations

import re
import unicodedata

_EMAIL_LIKE_TOKEN = re.compile(r"[^\s{}<>(),;:]*@[^\s{}<>(),;:]*")


def utf16_units(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def contains_email_like_text(value: str) -> bool:
    return "@" in unicodedata.normalize("NFKC", value)


def unsafe_bounded_text(value: str, maximum_utf16_units: int) -> bool:
    return (
        not value.strip()
        or utf16_units(value) > maximum_utf16_units
        or any(unicodedata.category(character) == "Cc" for character in value)
    )


def redact_email_like_tokens(value: str, replacement: str) -> str:
    normalized = unicodedata.normalize("NFKC", value)
    redacted = _EMAIL_LIKE_TOKEN.sub(replacement, normalized)
    return replacement if "@" in redacted else redacted


def truncate_utf16(value: str, maximum_utf16_units: int) -> str:
    if utf16_units(value) <= maximum_utf16_units:
        return value
    units = 0
    output: list[str] = []
    for character in value:
        character_units = utf16_units(character)
        if units + character_units > maximum_utf16_units:
            break
        output.append(character)
        units += character_units
    return "".join(output)
