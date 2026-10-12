# Research outreach email fixtures

[Branded automatic-outreach JSON](camel-hub-auto-outreach-template.json) is a synthetic layout and rendering fixture used by `OutreachBrandedTemplateTest`. Its reply address and Research Team sign-off are fictional; the arXiv paper and unsubscribe variables remain late-bound by the application. It is not deployment configuration, approval to contact anyone, or evidence of delivery. The only fixed website link in the fixture is the official Camel Hub website.

The fixture retains table layout, inline styles, frozen-paper binding, hostile-input checks, and unsubscribe safeguards. Do not substitute real author contacts, tracking capabilities, operational accounts, or production message content. Synthetic test literals elsewhere require exact path/value/byte review, not a domain-wide exception.

[Static synthetic HTML preview](camel-hub-auto-outreach-preview.html) uses the exact sanitized JSON HTML body and layout, with its five variables replaced by a fictional researcher, paper title, `SYNTHETIC-PAPER` label, and non-issued `.invalid` paper/unsubscribe URLs. It has no JavaScript, images, external stylesheets, or automatic network loads. The fictional paper label is a layout sample, not a valid arXiv identifier or a runtime rendering test. The official Camel Hub website remains the only real fixed website link; do not treat this preview as a sent message, an issued unsubscribe capability, or permission to contact anyone.

Use `app.personalization.research_invitation.reusable_template(reply_to)` only with operator-controlled runtime settings. The generation workflow preserves its marked spans and derives plain text from the final HTML; generation does not authorize sending.

[Preparation diagnostics](preparation-diagnostics.md) describes safe failure fields and outcome limits. Private operational reports, historical previews, contact lists, and screenshots are intentionally not distributed.
