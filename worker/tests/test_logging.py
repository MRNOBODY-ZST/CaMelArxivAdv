from __future__ import annotations

from app.observability.logging import redact_sensitive


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

