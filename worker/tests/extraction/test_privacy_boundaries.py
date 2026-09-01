from __future__ import annotations

from collections.abc import Callable
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.extraction.models import (
    Confidence,
    ExtractedAuthor,
    ExtractedContact,
    ExtractionDocument,
    ExtractionEvidence,
)
from app.messaging.contracts import (
    ResultPayload,
    SourceAuthor,
    SourceContact,
    SourceEvidence,
    SourceExtractionResult,
)


@pytest.mark.parametrize(
    ("model", "kwargs"),
    [
        (ExtractedAuthor, {"order": 1, "name": "alice@example.edu"}),
        (
            ExtractedAuthor,
            {
                "order": 1,
                "name": "Alice Example",
                "affiliations": ("Lab; 用户@example.org",),
            },
        ),
        (SourceAuthor, {"order": 1, "name": '"bob"@example.org'}),
        (
            SourceAuthor,
            {
                "order": 1,
                "name": "Alice Example",
                "affiliations": ("Lab; josé@example.org",),
            },
        ),
    ],
)
def test_author_models_reject_email_like_public_text(
    model: type[ExtractedAuthor] | type[SourceAuthor],
    kwargs: dict[str, object],
) -> None:
    with pytest.raises(ValidationError):
        model(**kwargs)


@pytest.mark.parametrize(
    ("model", "kwargs"),
    [
        (
            ExtractionEvidence,
            {
                "source_relative_path": "authors/alice@example.edu.tex",
                "rule_name": "DIRECT_AUTHOR_EMAIL",
                "line_number": 1,
                "logical_location": "AUTHOR_FRONT_MATTER",
                "masked_context": "[email redacted]",
            },
        ),
        (
            ExtractionEvidence,
            {
                "source_relative_path": "main.tex",
                "rule_name": "DIRECT_AUTHOR_EMAIL",
                "line_number": 1,
                "logical_location": "AUTHOR_FRONT_MATTER",
                "masked_context": "Contact 用户@example.org",
            },
        ),
        (
            SourceEvidence,
            {
                "source_relative_path": 'authors/"bob"@example.org.tex',
                "rule_name": "DIRECT_AUTHOR_EMAIL",
                "line_number": 1,
                "logical_location": "AUTHOR_FRONT_MATTER",
                "masked_context": "[email redacted]",
            },
        ),
        (
            SourceEvidence,
            {
                "source_relative_path": "main.tex",
                "rule_name": "DIRECT_AUTHOR_EMAIL",
                "line_number": 1,
                "logical_location": "AUTHOR_FRONT_MATTER",
                "masked_context": "Contact josé@example.org",
            },
        ),
    ],
)
def test_evidence_models_reject_email_like_public_text(
    model: type[ExtractionEvidence] | type[SourceEvidence],
    kwargs: dict[str, object],
) -> None:
    with pytest.raises(ValidationError):
        model(**kwargs)


@pytest.mark.parametrize("model", [ExtractedAuthor, SourceAuthor])
def test_author_name_length_matches_java_utf16_boundary(
    model: type[ExtractedAuthor] | type[SourceAuthor],
) -> None:
    assert model(order=1, name="😀" * 150).name == "😀" * 150

    with pytest.raises(ValidationError):
        model(order=1, name="😀" * 151)


@pytest.mark.parametrize("model", [ExtractedAuthor, SourceAuthor])
def test_affiliation_length_matches_java_utf16_boundary(
    model: type[ExtractedAuthor] | type[SourceAuthor],
) -> None:
    assert model(order=1, name="Alice", affiliations=("😀" * 1000,)).affiliations

    with pytest.raises(ValidationError):
        model(order=1, name="Alice", affiliations=("😀" * 1001,))


@pytest.mark.parametrize("model", [ExtractionEvidence, SourceEvidence])
def test_evidence_lengths_match_java_utf16_boundaries(
    model: type[ExtractionEvidence] | type[SourceEvidence],
) -> None:
    base = {
        "rule_name": "DIRECT_AUTHOR_EMAIL",
        "line_number": 1,
        "logical_location": "AUTHOR_FRONT_MATTER",
    }
    assert model(
        **base,
        source_relative_path="😀" * 250,
        masked_context="😀" * 300,
    )

    with pytest.raises(ValidationError):
        model(
            **base,
            source_relative_path="😀" * 251,
            masked_context="safe",
        )
    with pytest.raises(ValidationError):
        model(
            **base,
            source_relative_path="main.tex",
            masked_context="😀" * 301,
        )


def _internal_contact(display_email: str) -> ExtractedContact:
    return ExtractedContact(
        normalized_email="alice@example.edu",
        display_email=display_email,
        domain="example.edu",
        syntax_valid=True,
        author_order=1,
        confidence=Confidence.HIGH,
        evidence=(
            ExtractionEvidence(
                source_relative_path="main.tex",
                rule_name="DIRECT_AUTHOR_EMAIL",
                line_number=1,
                logical_location="AUTHOR_FRONT_MATTER",
                masked_context="[email redacted]",
            ),
        ),
    )


def _source_contact(display_email: str) -> SourceContact:
    return SourceContact(
        normalized_email="alice@example.edu",
        display_email=display_email,
        domain="example.edu",
        syntax_valid=True,
        author_order=1,
        confidence="HIGH",
        evidence=(
            SourceEvidence(
                source_relative_path="main.tex",
                rule_name="DIRECT_AUTHOR_EMAIL",
                line_number=1,
                logical_location="AUTHOR_FRONT_MATTER",
                masked_context="[email redacted]",
            ),
        ),
    )


@pytest.mark.parametrize("factory", [_internal_contact, _source_contact])
def test_display_email_length_matches_java_utf16_boundary(
    factory: Callable[[str], ExtractedContact | SourceContact],
) -> None:
    assert factory("😀" * 160)

    with pytest.raises(ValidationError):
        factory("😀" * 161)


def test_document_class_length_matches_java_utf16_boundary() -> None:
    assert ExtractionDocument(
        document_class="😀" * 50,
        files_inspected=1,
    )
    assert SourceExtractionResult(
        paper_id=uuid4(),
        arxiv_id="2608.00001",
        parser_version="test",
        status="SUCCEEDED",
        cleanup_confirmed=True,
        source_format="TAR_GZIP",
        files_inspected=1,
        document_class="😀" * 50,
    )

    with pytest.raises(ValidationError):
        ExtractionDocument(document_class="😀" * 51, files_inspected=1)
    with pytest.raises(ValidationError):
        SourceExtractionResult(
            paper_id=uuid4(),
            arxiv_id="2608.00001",
            parser_version="test",
            status="SUCCEEDED",
            cleanup_confirmed=True,
            source_format="TAR_GZIP",
            files_inspected=1,
            document_class="😀" * 51,
        )


def test_source_result_optional_public_text_rejects_email_like_content() -> None:
    with pytest.raises(ValidationError):
        ExtractionDocument(document_class="alice@example.edu", files_inspected=1)

    for field, value in (
        ("source_format", "alice@example.edu"),
        ("document_class", "用户@example.org"),
        ("error_summary", 'failed near "bob"@example.org'),
    ):
        kwargs: dict[str, object] = {
            "paper_id": uuid4(),
            "arxiv_id": "2608.00001",
            "parser_version": "test",
            "status": "FAILED",
            "cleanup_confirmed": True,
            "error_code": "SOURCE_CONTENT_INVALID",
        }
        kwargs[field] = value
        with pytest.raises(ValidationError):
            SourceExtractionResult(**kwargs)


def test_result_payload_error_summary_rejects_email_like_content() -> None:
    with pytest.raises(ValidationError):
        ResultPayload(
            status="FAILED",
            stage="FAILED",
            error_summary="Contact alice@example.edu",
        )
