from __future__ import annotations

import os
import socket
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _default_worker_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="ARXIV_WORKER_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    worker_id: str = Field(default_factory=_default_worker_id, min_length=1, max_length=120)
    worker_version: str = "0.1.0"
    log_level: str = "INFO"
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_security_protocol: Literal["PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"] = "PLAINTEXT"
    kafka_client_id: str = "camel-arxiv-worker"
    consumer_group: str = "camel-arxiv-workers-v1"
    redis_url: SecretStr = SecretStr("redis://localhost:6379/0")
    results_topic: str = "camel.arxiv.results.v1"
    jobs_topic: str = "camel.arxiv.jobs.v1"
    retry_topic: str = "camel.arxiv.retry.v1"
    dead_letter_topic: str = "camel.arxiv.dlt.v1"
    retry_delay_seconds: float = Field(default=30.0, ge=1.0, le=300.0)
    consumer_poll_interval_seconds: float = Field(default=60.0, ge=5.0, le=300.0)
    command_max_bytes: int = Field(default=2 * 1024 * 1024, ge=1024, le=10 * 1024 * 1024)
    metadata_batch_size: int = Field(default=50, ge=1, le=100)
    heartbeat_interval_seconds: float = Field(default=15.0, ge=5.0, le=300.0)
    allowed_arxiv_hosts: frozenset[str] = frozenset(
        {"export.arxiv.org", "oaipmh.arxiv.org", "arxiv.org"}
    )
    min_request_interval_seconds: float = Field(default=3.0, ge=3.0, le=300.0)
    request_timeout_seconds: float = Field(default=30.0, ge=1.0, le=300.0)
    legacy_base_url: str = "https://export.arxiv.org/api/query"
    oai_base_url: str = "https://oaipmh.arxiv.org/oai"
    source_base_url: str = "https://export.arxiv.org/e-print"
    user_agent: str = "CaMelArxivAdv/0.1 (admin@example.invalid)"
    max_redirects: int = Field(default=3, ge=0, le=5)
    max_request_retries: int = Field(default=3, ge=0, le=10)
    max_archive_bytes: int = Field(default=50 * 1024 * 1024, ge=1024, le=1024 * 1024 * 1024)
    max_extracted_bytes: int = Field(default=250 * 1024 * 1024, ge=1024)
    max_single_file_bytes: int = Field(default=20 * 1024 * 1024, ge=1024)
    max_file_count: int = Field(default=5_000, ge=1, le=100_000)
    max_directory_depth: int = Field(default=20, ge=1, le=100)
    max_compression_ratio: float = Field(default=100.0, ge=1.0, le=10_000.0)
    max_include_depth: int = Field(default=16, ge=1, le=100)
    max_parse_seconds: float = Field(default=60.0, ge=1.0, le=600.0)
    temp_root: Path | None = None

    @property
    def maximum_poll_interval_ms(self) -> int:
        return max(
            15 * 60 * 1_000,
            round(self.consumer_poll_interval_seconds * 10 * 1_000),
        )


class PersonalizationSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="PERSONALIZATION_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    enabled: bool = False
    provider: str = Field(default="openai", min_length=1, max_length=80)
    model: str = Field(default="gpt-5.6-luna", min_length=1, max_length=120)
    api_key: SecretStr | None = None
    api_base_url: str = "https://api.openai.com/v1"
    api_auth_scheme: Literal["x-api-key", "bearer"] = "x-api-key"
    request_timeout_seconds: float = Field(default=60.0, ge=1.0, le=300.0)
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_security_protocol: Literal["PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"] = "PLAINTEXT"
    kafka_client_id: str = "camel-personalization-worker"
    consumer_group: str = "camel-personalization-workers-v1"
    ray_address: str = Field(default="auto", min_length=1, max_length=255)
    maximum_command_bytes: int = Field(default=2 * 1024 * 1024, ge=1024, le=10 * 1024 * 1024)
    maximum_concurrency: int = Field(default=16, ge=1, le=256)
    log_level: str = "INFO"
    jobs_topic: str = "camel.mail.personalization.jobs.v1"
    results_topic: str = "camel.mail.personalization.results.v1"
    retry_topic: str = "camel.mail.personalization.retry.v1"
    dead_letter_topic: str = "camel.mail.personalization.dlt.v1"
    retry_delay_seconds: float = Field(default=30.0, ge=1.0, le=300.0)

    @model_validator(mode="after")
    def enabled_requires_key(self) -> PersonalizationSettings:
        if self.enabled and (self.api_key is None or not self.api_key.get_secret_value().strip()):
            raise ValueError("Enabled personalization requires PERSONALIZATION_API_KEY")
        if self.provider not in {"openai", "anthropic"}:
            raise ValueError("Personalization provider must be openai or anthropic")
        parsed = urlsplit(self.api_base_url)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("Personalization API URL must be HTTPS without credentials or query")
        return self
