# Research outreach production verification — 2026-09-13

## Deployed configuration

- Feature commit: `208572d`; campaign-purpose hotfix: `c925784`.
- Backend API and mail worker run `c925784`; frontend and personalization/Ray services run `208572d`. All affected services passed health checks. Existing host Nginx virtual hosts were not changed.
- SendPulse SMTP: `smtp-pulse.com:587`, required STARTTLS; From: `no-reply@salmon.cloudflare.lat`; display name: `Camel Hub Research Team`.
- Limits: 400 per rolling 24 hours and 12,000 per UTC calendar month, plus 5/minute, 50/hour and 20/recipient-domain/hour. Month reset is the first day at 08:00 Asia/Shanghai. Counts cover this application's shared account history, not usage from other SendPulse clients.
- SMTP credentials are encrypted in the application database, not stored in this repository. The existing monitored reply mailbox remains the Reply-To destination.
- SMTP and reply-mailbox connection tests passed. Public SPF, DKIM selector `sign`, and DMARC records were present; this is not evidence of a delivered message's authentication results.

## Operator entry points

- [Reusable branded invitation](https://arxiv.nodexi.top/email/templates/aba2d9b7-dae9-44b3-a9f2-48f804c929a5)
- [Three-recipient review draft and safety-run results](https://arxiv.nodexi.top/email/campaigns/83a3c1d4-3809-4912-be27-902eeec5d3ff)
- Broad segment: `Camel Hub · Agent Researchers`, paper keyword `agent`.

The draft uses three authors of *Agent Audit: A Security Analysis System for LLM Agent Applications*, arXiv `2603.22853`. These contacts are review candidates, not sent campaign recipients. The campaign remains `DRAFT`.

## AI and UI verification

Real Anthropic-format API generation using `claude-opus-4-6` completed for all three recipients through the production Kafka/Ray workflow, with zero generation failures. Each generated subject, research connection, proposal, and internal rationale was inspected against the stored public paper abstract. The invitation proposes joint experiments and discusses compute/token support without inventing an award or guaranteed budget.

The saved HTML retains the five-table layout, inline brand styling, paper and website links, and both uniquely identified AI slots. Production Edge desktop/mobile checks found no horizontal overflow or JavaScript errors. Branded templates open in HTML mode without a rich-text editor round-trip that would strip their layout. The template is still version 1.

Multiline campaign purposes now preserve CR/LF/TAB while rejecting other control characters. Header/name validation remains strict. A real production campaign update with the multiline purpose succeeded.

## Safety send and callbacks

Safety run: `34aa06bd-9273-4bba-8e89-d51bb8c86d9b`.

At 00:10:21–00:10:29 UTC, all three messages were redirected exclusively to the configured, previously authorized owner test inbox. Each had exactly one SMTP attempt and a `250 OK: message queued` response after DATA. The run completed with three `SMTP_ACCEPTED`, no failures and no unknown outcomes. No message was sent to an author.

Actual per-message callback URLs were parsed and exercised in memory; live tokens were not copied into this report:

- Three open-pixel requests returned HTTP 200 with `image/gif` and recorded three OPEN events.
- Six tracked links returned HTTP 302 to the expected paper or Camel Hub website and recorded six CLICK events.
- Three unsubscribe confirmation GETs did not create unsubscribe events; the subsequent POSTs returned HTTP 200 and recorded three safety UNSUBSCRIBE events.
- These are synthetic test interactions, **not evidence that a human opened or clicked a message**. Heuristic classifications do not change their test origin.
- Formal delivery attempts for this campaign, formal tracking/unsubscribe records, suppression entries, and recipient cooldowns remained zero.

## Outstanding external verification

As of the final check around 00:16 UTC, none of the three messages was found via read-only IMAP searches of the owner's inbox, junk, or standard classified-mail folders. Searches used both the new From address and the known generated subjects. The mailbox synchronizer remained healthy with no recorded error, and no matching reply or bounce was recorded.

Therefore SMTP acceptance and application callbacks are verified; **recipient-server delivery, inbox placement, received-message DKIM/SPF results, preservation of Message-ID by SendPulse, and a natural reply through this new sender are not yet verified**. No synthetic reply was manufactured to stand in for that missing evidence, and no duplicate invitation was sent.

The next diagnostic is the three queued messages' status in the SendPulse SMTP dashboard (including sender activation and delivery errors), followed by an inbox-header check and one owner reply if they arrive. Account-specific queue/moderation status cannot be inferred from the SMTP `250` response. See [SendPulse's SMTP setup and statistics guidance](https://sendpulse.com/knowledge-base/smtp/setup-smtp-server?content_block=faq). The provided SMTP credentials are not the separate API ID/Secret required for SendPulse statistics API access.

## Automated checks

- Backend feature baseline: 736 tests passed; hotfix workflow suite: 26 tests passed, including two new validation regressions.
- Worker: 248 tests passed; targeted personalization checks, Ruff and mypy passed.
- Frontend final suite: 143 tests passed; typecheck, lint and production build passed.
- Existing production email/campaign Edge end-to-end suite: four tests passed.
- Git whitespace checks passed. Scoped credential-pattern scan found only the explicit Mailpit test dummy password.

Production configuration and a validated PostgreSQL custom-format backup were captured before deployment. No unrelated service or production campaign was changed.
