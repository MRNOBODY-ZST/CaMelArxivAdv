"""Cross-language identity of a validated source command, never of its headers."""

from __future__ import annotations

import hashlib
import struct
from typing import Any

from app.messaging.contracts import SourceExtractionCommand


def canonical_encode(value: object) -> bytes:
    if value is None:
        return b"n"
    if type(value) is bool:
        return b"b" + bytes([value])
    if isinstance(value, str):
        try:
            encoded = value.encode("utf-8", errors="strict")
        except UnicodeError as exc:
            raise ValueError("Invalid identity text") from exc
        return b"s" + struct.pack(">I", len(encoded)) + encoded
    if type(value) is int:
        encoded = str(value).encode("ascii")
        return b"i" + struct.pack(">I", len(encoded)) + encoded
    if isinstance(value, list):
        return b"a" + struct.pack(">I", len(value)) + b"".join(map(canonical_encode, value))
    if isinstance(value, dict) and all(isinstance(key, str) for key in value):
        try:
            keys = sorted(value, key=lambda key: key.encode("utf-8", errors="strict"))
        except UnicodeError as exc:
            raise ValueError("Invalid identity key") from exc
        return (
            b"o"
            + struct.pack(">I", len(value))
            + b"".join(canonical_encode(key) + canonical_encode(value[key]) for key in keys)
        )
    raise ValueError("Unsupported identity value")


def fingerprint(payload: Any) -> str:
    if not isinstance(payload, dict) or set(payload) != {"parserVersion", "targets"}:
        raise ValueError("Invalid source payload fields")
    targets = payload["targets"]
    if not isinstance(targets, list):
        raise ValueError("Invalid source targets")
    for target in targets:
        if not isinstance(target, dict) or not {"paperId", "arxivId"} <= set(target) <= {
            "paperId",
            "arxivId",
            "metadataAuthors",
        }:
            raise ValueError("Invalid source target fields")
    model = SourceExtractionCommand.model_validate(payload)
    tree = model.model_dump(mode="json", by_alias=True)
    return hashlib.sha256(canonical_encode(tree)).hexdigest()
