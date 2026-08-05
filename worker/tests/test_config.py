from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.config import Settings


def test_defaults_enforce_safe_arxiv_rate_and_hosts() -> None:
    settings = Settings()

    assert settings.min_request_interval_seconds >= 3
    assert settings.allowed_arxiv_hosts == frozenset({"export.arxiv.org", "arxiv.org"})
    assert settings.max_archive_bytes == 50 * 1024 * 1024
    assert settings.max_extracted_bytes == 250 * 1024 * 1024


def test_rejects_an_arxiv_interval_below_three_seconds() -> None:
    with pytest.raises(ValidationError):
        Settings(min_request_interval_seconds=2.99)


def test_live_smtp_is_not_part_of_worker_configuration() -> None:
    assert "smtp" not in " ".join(Settings.model_fields).lower()

