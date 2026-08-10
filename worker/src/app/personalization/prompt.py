from __future__ import annotations

from app.personalization.contracts import PersonalizationCommand, PersonalizationTarget

INSTRUCTIONS = """You draft concise, professional academic outreach email for human review.
Ground the opening and value proposition only in the supplied public paper metadata.
Do not claim to have read material that is not supplied. Do not invent affiliations or achievements.
Return the requested JSON only. Preserve the literal {{unsubscribe_url}} token
in both HTML and text.
Do not add tracking pixels, external images, forms, scripts, or attachments.
The draft is never auto-sent and must be suitable for an operator to review."""


def public_generation_input(
    command: PersonalizationCommand,
    target: PersonalizationTarget,
) -> dict[str, object]:
    return {
        "campaignPurpose": command.payload.purpose,
        "author": {
            "name": target.author_name,
            "organization": target.organization,
        },
        "paper": {
            "title": target.paper_title,
            "abstract": target.paper_abstract,
            "arxivId": target.arxiv_id,
            "primaryCategory": target.primary_category,
            "url": target.paper_url,
        },
        "styleReference": {
            "subject": command.payload.template_subject,
            "html": command.payload.template_html,
            "text": command.payload.template_text,
        },
    }
