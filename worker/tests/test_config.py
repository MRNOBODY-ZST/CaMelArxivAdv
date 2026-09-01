from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.config import PersonalizationSettings, Settings


def test_defaults_enforce_safe_arxiv_rate_and_hosts() -> None:
    settings = Settings()

    assert settings.min_request_interval_seconds >= 3
    assert settings.allowed_arxiv_hosts == frozenset(
        {"export.arxiv.org", "oaipmh.arxiv.org", "arxiv.org"}
    )
    assert settings.max_archive_bytes == 50 * 1024 * 1024
    assert settings.max_extracted_bytes == 250 * 1024 * 1024
    assert settings.kafka_bootstrap_servers == "localhost:9092"
    assert settings.jobs_topic == "camel.arxiv.jobs.v1"
    assert settings.consumer_group == "camel-arxiv-workers-v1"
    assert settings.maximum_poll_interval_ms >= 10 * settings.consumer_poll_interval_seconds * 1_000


def test_poll_heartbeat_keeps_a_bounded_group_eviction_backstop() -> None:
    settings = Settings(consumer_poll_interval_seconds=90)

    assert settings.maximum_poll_interval_ms == 15 * 60 * 1_000


def test_rejects_an_arxiv_interval_below_three_seconds() -> None:
    with pytest.raises(ValidationError):
        Settings(min_request_interval_seconds=2.99)


def test_live_smtp_is_not_part_of_worker_configuration() -> None:
    assert "smtp" not in " ".join(Settings.model_fields).lower()


def test_enabled_personalization_rejects_a_blank_api_key() -> None:
    with pytest.raises(ValidationError):
        PersonalizationSettings(enabled=True, api_key="")


def test_anthropic_configuration_accepts_a_compatible_https_gateway() -> None:
    settings = PersonalizationSettings(
        enabled=True,
        provider="anthropic",
        api_key="test-key",
        model="claude-opus-4-6",
        api_base_url="https://gateway.example/v1",
    )
    assert settings.provider == "anthropic"
    assert settings.api_base_url == "https://gateway.example/v1"


@pytest.mark.parametrize(
    "base_url",
    [
        "http://gateway.example",
        "https://user:secret@gateway.example",
        "https://gateway.example?key=secret",
    ],
)
def test_personalization_rejects_unsafe_provider_urls(base_url: str) -> None:
    with pytest.raises(ValidationError):
        PersonalizationSettings(api_base_url=base_url)


def test_personalization_rejects_unknown_authentication_scheme() -> None:
    with pytest.raises(ValidationError):
        PersonalizationSettings(api_auth_scheme="query-string")
