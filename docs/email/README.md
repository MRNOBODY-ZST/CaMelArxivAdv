# Research outreach email assets

- [Reusable invitation and AI workflow](camel-hub-research-invitation.md)
- [Illustrative HTML preview](camel-hub-research-invitation-preview.html)

Use `app.personalization.research_invitation.reusable_template(reply_to)` to build
the template API payload and `CAMPAIGN_PURPOSE` from the same module for the draft
campaign objective. Supply the monitored reply mailbox from deployment settings.
Credentials and live message tracking links do not belong in these files.

The AI receives the actual public paper title, abstract, category, author, and URL,
alongside the operator's current campaign purpose. It writes a specific research
connection and one proposed experiment into two marked template spans. Styling and
other operator edits stay intact; the text alternative is derived from the final
HTML. See the workflow document for slot editing and review details.

The HTML example uses a fictional researcher and paper for layout review. It is not
a production AI result or evidence of message delivery.
