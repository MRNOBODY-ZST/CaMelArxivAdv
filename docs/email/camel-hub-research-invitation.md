# Camel Hub research collaboration invitation

The reusable template is **Camel Hub · Agent Research Collaboration**. It introduces
Camel Hub as an API Gateway team, invites collaboration on AI agent research, and
offers a discussion of model API token credits or compute sponsorship for an agreed
research plan. No budget, award, publication, or capability is promised.

The template has an email-client-friendly fluid table layout, inline styling, a
linked paper citation, a research proposal, a support panel, the official
`https://api.camel-hub.com/` link, and an unsubscribe footer. The sender display name
is **Camel Hub Research Team**. Deployment supplies the monitored Reply-To mailbox;
the SMTP account supplies `no-reply@salmon.cloudflare.lat` as the From address.

## Install and use

`app.personalization.research_invitation.reusable_template(reply_to)` returns the
complete request body for `POST /api/v1/templates`. Supply the existing monitored
mailbox as `reply_to`; no credential belongs in this artifact. The same module
exports `CAMPAIGN_PURPOSE` for a draft campaign's `purpose` field.

Create an Agent-focused segment, then create a draft campaign with that template,
segment, purpose, and SMTP account. Starting personalization uses the configured
AI provider through the normal Kafka/Ray pipeline. It does not send the campaign.

## Personalization and editing

The official website link's title, `Camel Hub research invitation v1`, selects a
small structured AI response: subject, research connection, collaboration idea,
and an internal grounding rationale. Only public author and paper metadata are
provided. The actual campaign purpose remains part of every generation request;
it can narrow the proposed experiment or priorities. Fixed introductory and
sponsorship copy remains visible and editable in the template.

The model fills exactly two spans, identified by their titles:

- `Camel Hub research connection`
- `Camel Hub collaboration idea`

Other HTML, styling, links, and operator copy edits are preserved. Preserve one of
each span when editing the source; a missing or duplicate slot fails generation
instead of silently producing an unpersonalized invitation. Generated prose is
escaped before insertion. The final plain-text alternative is derived from the
personalized HTML, including link destinations, so both formats carry the same
content and unsubscribe link. The subject is generated for the actual paper.

The opt-in template uses the fixed collaboration layout even when the campaign
purpose changes; for an unrelated campaign, select a different template or remove
the versioned website-link title to use the general full-email generator.

The review rationale identifies the public-paper detail behind the proposal; it
is not included in the email. This improves reviewability, but it is not an
independent factual verifier. Operators should still check that the proposal and
available support match the intended research relationship before sending.

`camel-hub-research-invitation-preview.html` is an illustrative layout preview.
Its fictional paper and draft prose demonstrate the design; they are not evidence
of a generated, delivered, or accepted research invitation.
