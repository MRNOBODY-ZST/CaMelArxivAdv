from __future__ import annotations

import os
import socket
from pathlib import Path

from pydantic import Field, SecretStr
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
    rabbitmq_url: SecretStr = SecretStr("amqp://guest:guest@localhost:5672/")
    redis_url: SecretStr = SecretStr("redis://localhost:6379/0")
    results_exchange: str = "arxiv.results"
    jobs_exchange: str = "arxiv.jobs"
    retry_exchange: str = "arxiv.retry"
    dead_exchange: str = "arxiv.dead"
    jobs_queue: str = "arxiv.jobs.worker"
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
