import pytest

from app.jobs.arxiv_consumer import _legacy_query


@pytest.mark.parametrize(
    ("dates", "expected_query"),
    [
        (
            {"submittedTo": "2026-09-23"},
            'abs:"Agent" AND submittedDate:[000101010000 TO 202609232359]',
        ),
        (
            {"submittedFrom": "2026-01-01"},
            'abs:"Agent" AND submittedDate:[202601010000 TO 999912312359]',
        ),
        (
            {"submittedFrom": "2026-01-01", "submittedTo": "2026-09-23"},
            'abs:"Agent" AND submittedDate:[202601010000 TO 202609232359]',
        ),
        ({}, 'abs:"Agent"'),
    ],
    ids=["upper-bound-only", "lower-bound-only", "both-bounds", "no-dates"],
)
def test_legacy_query_uses_valid_calendar_bounds(
    dates: dict[str, str], expected_query: str
) -> None:
    criteria: dict[str, object] = {
        "abstractKeywords": "Agent",
        "sortBy": "SUBMITTED_DATE",
        "sortOrder": "DESCENDING",
        **dates,
    }

    assert _legacy_query(criteria) == (
        expected_query,
        "SUBMITTED_DATE",
        "DESCENDING",
    )
