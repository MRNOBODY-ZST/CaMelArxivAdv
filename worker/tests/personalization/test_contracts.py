from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.personalization.contracts import (
    GeneratedEmail,
    PersonalizationCommand,
)


def command_json() -> dict[str, object]:
    return {
        "version": 1,
        "messageId": str(uuid4()),
        "type": "PERSONALIZE_CAMPAIGN",
        "jobId": str(uuid4()),
        "campaignId": str(uuid4()),
        "idempotencyKey": "personalization:test",
        "traceId": "0123456789abcdef",
        "occurredAt": datetime.now(UTC).isoformat(),
        "payload": {
            "purpose": "Discuss the author's work",
            "templateSubject": "About {{paper_title}}",
            "templateHtml": '<p>Reference</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
            "templateText": "Reference {{unsubscribe_url}}",
            "targets": [
                {
                    "recipientId": str(uuid4()),
                    "authorName": "Ada Lovelace",
                    "paperTitle": "Safe Distributed Intelligence",
                    "paperAbstract": "A public abstract.",
                    "arxivId": "2608.00001",
                    "primaryCategory": "cs.AI",
                    "paperUrl": "https://arxiv.org/abs/2608.00001",
                    "organization": "Analytical Engine University",
                }
            ],
        },
    }


def test_command_accepts_camel_case_public_paper_context() -> None:
    command = PersonalizationCommand.model_validate(command_json())

    assert command.payload.targets[0].author_name == "Ada Lovelace"
    assert command.payload.targets[0].paper_abstract == "A public abstract."
    assert "email" not in command.model_dump_json(by_alias=True).casefold()


def test_command_rejects_unknown_email_fields_and_duplicate_recipients() -> None:
    with_email = command_json()
    payload = with_email["payload"]
    assert isinstance(payload, dict)
    targets = payload["targets"]
    assert isinstance(targets, list)
    target = targets[0]
    assert isinstance(target, dict)
    target["recipientEmail"] = "ada@university.edu"
    with pytest.raises(ValidationError):
        PersonalizationCommand.model_validate(with_email)

    duplicated = command_json()
    duplicate_payload = duplicated["payload"]
    assert isinstance(duplicate_payload, dict)
    duplicate_targets = duplicate_payload["targets"]
    assert isinstance(duplicate_targets, list)
    assert isinstance(duplicate_targets[0], dict)
    duplicate_targets.append(dict(duplicate_targets[0]))
    with pytest.raises(ValidationError):
        PersonalizationCommand.model_validate(duplicated)


def test_generated_email_requires_unsubscribe_in_both_bodies() -> None:
    valid = GeneratedEmail(
        subject="A paper-aware subject",
        html='<p>Hello Ada</p><a href="{{unsubscribe_url}}">Unsubscribe</a>',
        text="Hello Ada\n\nUnsubscribe: {{unsubscribe_url}}",
        rationale="Connected the invitation to the public paper abstract.",
    )
    assert valid.subject == "A paper-aware subject"

    with pytest.raises(ValidationError):
        GeneratedEmail(
            subject="Missing compliance link",
            html="<p>Hello Ada</p>",
            text="Hello Ada",
            rationale="Invalid output",
        )
