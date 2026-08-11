from __future__ import annotations

import logging

from app.observability.logging import configure_logging, redact_sensitive


def test_structured_log_processor_redacts_sensitive_fields() -> None:
    event = redact_sensitive(
        None,
        "info",
        {
            "event": "connection_failed",
            "password": "secret-value",
            "authorization": "Bearer token-value",
            "email": "person@example.edu",
            "workerId": "worker-1",
        },
    )

    assert event["password"] == "[REDACTED]"
    assert event["authorization"] == "[REDACTED]"
    assert event["email"] == "[REDACTED]"
    assert event["workerId"] == "worker-1"


def test_dependency_protocol_logs_remain_quiet_in_debug_mode() -> None:
    configure_logging("DEBUG")

    assert logging.getLogger("aiokafka").getEffectiveLevel() >= logging.WARNING
