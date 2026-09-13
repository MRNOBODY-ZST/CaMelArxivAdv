# Salmon SMTP cutover — 2026-09-14

Production configuration was switched through the authenticated application API;
no application binary, host Nginx configuration, DNS record, or firewall was changed.
The existing backend remained healthy throughout the cutover.

## Active configuration

Submission server: `mail.salmon.cloudflare.lat:465`, implicit TLS with certificate
and hostname verification, authenticated as `hades@itshades.dev`. SMTP 587 with
STARTTLS also passed a TLS probe, but 465 is the configured sending port.

| From identity | SMTP account | Rolling daily limit | UTC monthly limit |
| --- | --- | ---: | ---: |
| `hades@itshades.dev` | `93fd9e0d-e50d-4e0e-8142-ca4448b1bf47` | 300 | 9,000 |
| `hades@salmon.cloudflare.lat` | `3c85ace4-1e2b-4f77-a94f-688895584c4b` | 100 | 3,000 |

The two new identities together are bounded to 400/day and 12,000/month within
the application's sending history. This is a static allocation, not a shared
quota pool: unused capacity is not borrowed between identities. Other mail
clients and independent accounts are outside these counters. The main identity
uses 4/minute and 40/hour; Salmon uses 1/minute and 10/hour.

Both identities use the display name `Camel Hub Research Team` and their own
address as Reply-To. They share the same physical mailbox, monitored through
IMAP 993 with implicit TLS, account `3defd1fc-02db-4a3c-b82e-a78f7d8ef5e0`.
SMTP authentication and IMAP login succeeded using the supplied credential,
which is encrypted in the application database and is not in this repository.
POP3 995 timed out during the preceding probe and was not configured.

The enabled-account ordering currently selects `hades@itshades.dev` when creating
a campaign. The UI has no persistent global-default flag; review the selected
sender after editing another enabled SMTP account.

The old SendPulse account was disabled for future sending only after verifying
that it had no running, scheduled, or paused campaign. Its credentials, audit
history, old campaign, safety runs, and old mailbox associations were retained.
Other SMTP accounts were not disabled or changed.

## Research invitation

- [New research draft](https://arxiv.nodexi.top/email/campaigns/82a832ac-c7cf-47f2-9e84-238947267ed8)
- [Reusable branded template](https://arxiv.nodexi.top/email/templates/aba2d9b7-dae9-44b3-a9f2-48f804c929a5)

The new campaign explicitly snapshots the new From address and binds the new
monitored IMAP/Reply-To. Three paper-grounded invitations were generated through
the real Anthropic-format `claude-opus-4-6` API and the existing Kafka/Ray pipeline.
Generation job: `514ecb2e-b8b6-4a46-a003-3787134f6078`.

The reusable template now has Reply-To `hades@itshades.dev` at version 2. Its
HTML and text are byte-for-byte unchanged. The new campaign was created using
version 1 and explicitly overrides its reply header with the new mailbox; old
campaign/template version snapshots were not rewritten.

## Verification evidence

1. Two owner-only SMTP diagnostics, one from each identity, were submitted by the
   production API to `hades@itshades.dev`. Both were found in the new INBOX using
   read-only IMAP. From and Reply-To were correct; both messages carried DKIM
   signatures. Their received Message-IDs exactly matched the application's
   original diagnostic IDs. Signature presence on these local deliveries is
   not a remote recipient's SPF/DKIM authentication verdict.
2. Safety run `1d939b9b-e0bb-42d0-90af-2c78dd017c6e` selected only one generated
   invitation and redirected it to the previously authorized owner test inbox
   at 163. It completed with one `SMTP_ACCEPTED`, one attempt, no failures, and
   no unknown outcome at `2026-09-13T16:50:21Z`. The campaign remained `DRAFT`;
   formal campaign delivery attempts were zero. No author received a message.
3. Actual rendered safety URLs were exercised in memory: one open returned
   HTTP 200/GIF, two clicks returned HTTP 302 to the expected paper/website, and
   unsubscribe GET showed a confirmation without adding an event before POST.
   The database recorded OPEN=1, CLICK=2, UNSUBSCRIBE=1. These are synthetic
   tests regardless of heuristic event classifications, not natural engagement.
4. One explicitly labeled synthetic owner-only reply was submitted locally to
   the new reply mailbox, deliberately referencing the safety message's stored
   RFC Message-ID. The normal IMAP worker recorded `REPLY` against safety message
   `bca40424-70a3-452c-bb36-3ca90a18aa59` at `2026-09-13T16:52:22Z`, remote UID 7,
   with no formal campaign-recipient association. The cursor advanced and had
   no error. This verifies reply matching and IMAP ingestion; it is not a
   researcher reply or proof that the external recipient received the invitation.

External inbox placement and a natural reply to this newly submitted invitation
remain unverified. The local diagnostic IDs were preserved, but this does not
prove that a downstream outbound relay preserves IDs on external delivery. The
previous SendPulse rewrite issue for historical messages has not been repaired
by changing the submission account. Do not resend the external safety message
solely because SMTP acceptance is not an inbox-delivery receipt.

Before mutation, production configuration and a PostgreSQL custom-format dump
were backed up to the server's `backups/salmon-cutover-20260914.GmduW6` directory;
the dump's archive listing was verified. At the final check the application
health endpoint was UP. This was a configuration-only cutover, so no new binary
build or code-test-suite run was required.
