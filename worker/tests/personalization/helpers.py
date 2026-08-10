from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

from app.personalization.contracts import PersonalizationCommand


def command(target_count: int = 3) -> PersonalizationCommand:
    targets = [
        {
            "recipientId": str(uuid4()),
            "authorName": f"Author {index}",
            "paperTitle": f"Paper {index}",
            "paperAbstract": f"Public abstract {index}",
            "arxivId": f"2608.{index + 1:05d}",
            "primaryCategory": "cs.AI",
            "paperUrl": f"https://arxiv.org/abs/2608.{index + 1:05d}",
            "organization": "Research University",
        }
        for index in range(target_count)
    ]
    return PersonalizationCommand.model_validate(
        {
            "version": 1,
            "messageId": str(uuid4()),
            "type": "PERSONALIZE_CAMPAIGN",
            "jobId": str(uuid4()),
            "campaignId": str(uuid4()),
            "idempotencyKey": "personalization:test",
            "traceId": "0123456789abcdef",
            "occurredAt": datetime.now(UTC).isoformat(),
            "payload": {
                "purpose": "Discuss the work",
                "templateSubject": "About {{paper_title}}",
                "templateHtml": '<a href="{{unsubscribe_url}}">Unsubscribe</a>',
                "templateText": "Reference {{unsubscribe_url}}",
                "targets": targets,
            },
        }
    )
