import hashlib
import json
from pathlib import Path

import pytest

from app.jobs.source_command_identity import canonical_encode, fingerprint

FIXTURE = Path(__file__).resolve().parents[3] / "contracts/fixtures/source-command-identity-v1.json"
TARGET = {"paperId": "11111111-1111-1111-1111-111111111111", "arxivId": "2608.00001"}
PAYLOAD = {
    "parserVersion": "0.1.0",
    "targets": [TARGET],
}


@pytest.mark.parametrize("vector", json.loads(FIXTURE.read_text())["canonical"])
def test_literal_canonical_vectors(vector: dict[str, object]) -> None:
    encoded = canonical_encode(vector["value"])
    assert encoded.hex() == vector["hex"]
    assert hashlib.sha256(encoded).hexdigest() == vector["sha256"]


@pytest.mark.parametrize("vector", json.loads(FIXTURE.read_text())["payloads"])
def test_literal_payload_fingerprints(vector: dict[str, object]) -> None:
    assert fingerprint(vector["payload"]) == vector["sha256"]


def test_author_default_and_field_order_do_not_change_identity() -> None:
    explicit = {
        "targets": [{**TARGET, "metadataAuthors": []}],
        "parserVersion": "0.1.0",
    }
    assert fingerprint(PAYLOAD) == fingerprint(explicit)


@pytest.mark.parametrize("authors", [None, [""], ["a\n"], ["😀" * 151], [True]])
def test_rejects_unsafe_authors(authors: object) -> None:
    with pytest.raises(ValueError):
        fingerprint(
            {
                "parserVersion": "0.1.0",
                "targets": [{**TARGET, "metadataAuthors": authors}],
            }
        )


def test_order_parser_and_unknown_fields_are_bound() -> None:
    assert fingerprint(PAYLOAD) != fingerprint({**PAYLOAD, "parserVersion": "0.2.0"})
    with pytest.raises(ValueError):
        fingerprint({**PAYLOAD, "headers": {}})
    first = {
        "parserVersion": "0.1.0",
        "targets": [{**TARGET, "metadataAuthors": ["李", "é"]}],
    }
    second = {
        "parserVersion": "0.1.0",
        "targets": [{**TARGET, "metadataAuthors": ["é", "李"]}],
    }
    assert fingerprint(first) != fingerprint(second)


@pytest.mark.parametrize("value", [1.0, float("nan"), "\ud800", {1: "bad"}])
def test_encoder_rejects_noncanonical_values(value: object) -> None:
    with pytest.raises(ValueError):
        canonical_encode(value)
