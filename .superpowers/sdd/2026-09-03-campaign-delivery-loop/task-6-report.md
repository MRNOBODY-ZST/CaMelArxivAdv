# Task 6 Report: Read-only IMAP Reply and Bounce Reconciliation

## Status

COMPLETE. Independent specification and quality/safety review passed with no P0 or P1
findings. The implementation adds a disabled-by-default, read-only IMAP reconciliation
worker for production and safety campaign replies, auto-replies, temporary delivery
reports, and permanent bounces.

No public mailbox was accessed, no external message was sent, and no production
configuration was changed in this task.

## TDD and repair evidence

The initial parser, mailbox transport, leased cursor, privacy, and PostgreSQL integration
tests were written before implementation. Review-driven RED/GREEN cycles then covered:

- strict permanent-DSN semantics and conflicting/malformed structured metadata;
- bounded raw and decoded MIME budgets, part-count limits, header-only attached messages,
  transfer encodings, poison multipart boundaries, and transient socket/Angus protocol
  failures;
- unique mailbox-scoped controlled Message-ID matching, reference overflow, UIDVALIDITY
  reset tail bounds, duplicate UIDs, and per-UID transactional cursor advancement;
- lease expiry/hash fencing, slow-read heartbeats, stale-owner failure audit rejection,
  fair rotation after prior successes and subsequent failures, and 21-mailbox starvation;
- production/safety isolation, frozen safety mailbox snapshots, privacy-minimal diagnostics,
  accurate structured audit rows, permanent-bounce suppression, and the shared email-HMAC
  cooldown lock used by concurrent delivery claims.

The database concurrency suite proves both linearization orders: a bounce that owns the
cooldown fence suppresses a waiting same-address claim, while a claim that commits first
may remain in the already handed-off `CONNECTING` state.

## Final independent verification

Focused command covered mailbox transport/identity, all inbound tests, Flyway, safety,
and delivery concurrency:

```text
BUILD SUCCESSFUL in 41s
tests=93 failures=0 errors=0 skipped=0
```

Full backend command:

```bash
cd backend
./gradlew test --no-daemon
```

Independent result:

```text
BUILD SUCCESSFUL in 2m 06s
tests=720 failures=0 errors=0 skipped=0
```

`git diff --check` passed. Sensitive-value, assistant-name, and unfinished-work-marker
scans found nothing in the changed/untracked Task 6 files.

## Implementation summary

- `MailboxTransport` opens IMAP folders as `READ_ONLY`, caps each poll at 50 messages,
  validates positive UIDVALIDITY, bootstraps only the newest bounded window, and returns
  privacy-minimal envelopes. It never changes flags, moves, or deletes messages.
- MIME parsing uses independent shared 64 KiB raw/decoded budgets and at most 16 report
  parts. `message/rfc822` stops after its first header terminator; legal
  `text/rfc822-headers` may end at part EOF. Ordinary bodies and attachments are never
  materialized.
- `InboundMailParser` accepts only generated production/safety Message-IDs, caps distinct
  references at 20, treats ambiguous/overflow/malformed input as `UNMATCHED`, and stores
  only a canonical transport/status diagnostic tuple.
- `InboundMailRepository` claims hash-fenced expiring cursor leases, persists each event
  and its cursor advance atomically, preserves the last successful sync time on failure,
  and writes safe structured audits only for the current lease owner.
- Permanent production bounces lock both the recipient and the shared email-HMAC delivery
  cooldown, transition only accepted/unknown outcomes, ensure global suppression, and
  audit the actual active suppression reason/source. Safety bounces never mutate
  production recipients or suppression.
- Safety runs now snapshot `mailbox_account_id` at materialization. Matching and due-mailbox
  selection use that immutable snapshot even if a draft campaign later changes mailbox.
- V18 normalizes pre-release inbound rows, enforces event-domain/diagnostic semantics,
  installs deletion-safe foreign keys and indexes, and backfills the safety mailbox
  snapshot from each campaign.

## Known non-blocking residual

Jakarta Mail's fetch API retrieves a requested header field before `boundedHeader()` can
apply the local 4096-character limit. A hostile oversized folded `References` field may
therefore cause limited network or heap amplification for the bounded 50-message batch.
It is still rejected before classification, matching, auditing, or persistence. The
provider API offers no partial-value fetch for individual header fields, so this remains
a documented P2 rather than a release blocker.
