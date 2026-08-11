from __future__ import annotations

import logging
from collections.abc import MutableMapping
from typing import Any, cast

import structlog

_SENSITIVE_KEY_PARTS = (
    "authorization",
    "cookie",
    "email",
    "jwt",
    "password",
    "secret",
    "source_content",
    "token",
)


def redact_sensitive(
    logger: Any,
    method_name: str,
    event_dict: MutableMapping[str, Any],
) -> MutableMapping[str, Any]:
    del logger, method_name
    for key in tuple(event_dict):
        normalized = key.lower()
        if any(part in normalized for part in _SENSITIVE_KEY_PARTS):
            event_dict[key] = "[REDACTED]"
    return event_dict


def configure_logging(level: str) -> None:
    numeric_level = logging.getLevelNamesMapping().get(level.upper(), logging.INFO)
    logging.basicConfig(level=numeric_level, format="%(message)s", force=True)
    for dependency_logger in ("aiokafka", "kafka"):
        logging.getLogger(dependency_logger).setLevel(logging.WARNING)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            redact_sensitive,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(numeric_level),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger() -> structlog.typing.FilteringBoundLogger:
    return cast(structlog.typing.FilteringBoundLogger, structlog.get_logger())
