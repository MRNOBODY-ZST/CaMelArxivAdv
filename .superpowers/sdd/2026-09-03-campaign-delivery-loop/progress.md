# SDD ledger — plan: docs/superpowers/plans/2026-09-03-campaign-delivery-loop.md

Branch: `hades/campaign-delivery-loop`
Branch merge base: `8d582a2e0eeca83cfa077d360ee03d4e5614f955`
Implementation plan head: `d22587d`
Spec: `docs/superpowers/specs/2026-09-03-campaign-delivery-loop-design.md`

## Pre-flight consistency scan

| Scope | Producer / consumer check | Finding |
|---|---|---|
| Task 1 self | V16 schema and typed properties match tests and later names | Consistent; `UNSUBSCRIBE` requires widening token type and is explicitly part of V16. |
| Task 2 self | API DTOs, workflow repository/service, status transitions, audit, outbox | Consistent; every mutation carries `expectedLockVersion`. |
| Task 3 self | SMTP detail, DB claim, Kafka wake-up, executor and reconciliation | Consistent after adding the disabled-by-default preparer gate. |
| Task 4 self | Production tracking signer/repository/rendering/callback/unsubscribe | Consistent; implements the Task 3 preparer boundary. |
| Task 5 self | Safety API, fixed destination, dedicated persistence and callbacks | Consistent; destination cannot enter the API request. |
| Task 6 self | Read-only IMAP envelope, parser, leased cursor, production/safety updates | Consistent; matching is RFC-ID/structured-DSN only. |
| Task 7 self | Reporting DTOs and Vue client/views/tests | Consistent; status/personalization and safety/production remain separate. |
| Task 8 self | Compose/env/scripts/runbook/deploy/live acceptance | Consistent; external-author SMTP is explicitly outside the authorized safety run. |
| Tasks 1 → 2 | Campaign lock/mailbox/review digest columns and enums feed workflow SQL | Names and status sets match. |
| Tasks 1 → 3 | Recipient/attempt lease, retry, RFC-ID, cooldown fields feed delivery repository | Names and outcome sets match. |
| Tasks 1 → 4 | Campaign link/token/event schema feeds tracking services | `UNSUBSCRIBE` and digest width are accounted for. |
| Tasks 1 → 5 | Safety run/message/attempt/link/event schema feeds safety services | Names match; safety attempts are dedicated and counted by shared rate logic. |
| Tasks 1 → 6 | Mailbox cursor/inbound event schema feeds synchronizer | UID idempotency and event types match. |
| Tasks 1 → 7 | All persisted statuses and metrics feed reporting | Reporting includes every terminal/engagement type. |
| Tasks 2 → 3 | `RUNNING` state and privacy-minimal outbox wake delivery worker | Match; worker rechecks current state and eligibility. |
| Tasks 2 → 5 | Campaign draft/generated snapshots feed safety run materialization | Match; safety does not require production eligibility. |
| Tasks 2 → 7 | Workflow DTO/state/version feeds typed frontend actions | Match; frontend always sends lock version. |
| Tasks 3 → 4 | Executor consumes `CampaignOutboundPreparer` implemented by tracking service | Match after plan correction. |
| Tasks 3 → 5 | `CampaignDeliveryRepository`/executor/config gain safety claim branch | Intentional interface extension; production branch remains unchanged. |
| Tasks 3 → 6 | Worker configuration supplies SMTP/crypto/database beans reused by inbound sync | Match; mailbox transport remains read-only. |
| Tasks 3 → 8 | Delivery topic/properties are exposed through Compose only after full implementation | Match; runtime default remains disabled. |
| Tasks 4 → 5 | Open/click controllers and unsubscribe controller route domain-separated safety tokens | Match; invalid namespace remains indistinguishable. |
| Tasks 4 → 6 | Stable production RFC Message-ID is consumed by inbound matcher | Match; safety uses its own stable RFC Message-ID. |
| Tasks 4 → 7 | Production callback/unsubscribe events feed reporting | Match; classifications and truthful labels align. |
| Tasks 5 → 6 | Safety RFC Message-ID and event table receive inbound safety matches | Match; no production mutation. |
| Tasks 5 → 7 | Safety run/messages/events feed dedicated UI tab and progress | Match; no production denominator includes safety data. |
| Tasks 6 → 7 | Reply/bounce/unmatched inbound events feed campaign reporting | Match; only matched production rows affect recipient metrics. |
| Tasks 7 → 8 | Operational UI/API contract is exercised in deployed Edge validation | Match; browser checks use the final client routes. |

Ruling: Task 3 delivery beans stay disabled unless both `app.campaign-delivery.enabled=true` and a real `CampaignOutboundPreparer` bean exist — this prevents an intermediate branch from sending drafts before unsubscribe/tracking rendering; if wrong, delivery remains safely unavailable until Task 4/8 rather than emitting incomplete mail.

Ruling: The existing single administrator may perform the separate approve action — the deployment has one administrator and the spec requires an explicit audited approval action, not two-person segregation; if wrong, a later policy change must require distinct creator/approver identities before production campaigns can start.

Ruling: Task 5 evolves `claimNext` from production-only to a disjoint production-or-safety claim — a shared SMTP-account lock is required to enforce aggregate provider limits, while completion repositories remain separate; if wrong, the safe fallback is disabling safety mode without affecting production claims.

## Task status

- Task 1: complete (commits `e99ffa2`, `f62e79c`, `814405f`, `798bd62`, repair `0f848dc`; focused 9 tests and full backend 412 tests passed; independent repair review: specification PASS, quality/safety PASS, no findings).
- Task 2: complete (commits `5eb2b08`, `ecc7857`; focused 32 tests and full backend 411 tests passed; independent re-review: specification PASS, quality/safety PASS, no findings). The reviewer noted only a non-blocking future optimization: hash each rendered row before ordered aggregation to cap PostgreSQL aggregate memory for very large campaigns.
- Task 3: complete (this task commit; focused delivery suite and full backend 514 tests passed; independent final review: specification PASS, quality/safety PASS, no P0/P1 findings). Non-blocking follow-ups are indexed same-HMAC/rate scans, finer reconciliation reasons, and large multi-campaign fairness.
- Task 4: complete (this task commit; focused tracking/delivery/SMTP suite 193 tests and full backend 602 tests passed; independent final review: specification PASS and quality/safety PASS, no P0/P1/P2 findings; reviewer focused 219 tests and full backend 602 tests passed; independent expiry-rotation safety audit PASS with no findings).
- Task 5: complete (independent specification PASS and quality/safety PASS; focused 259 tests and full backend 682 tests passed with zero failures/errors/skips; changed/untracked whitespace and filename-only credential/name/unfinished-work scans are clean; no P0/P1 findings). Sole P2: disabling safety requires a coordinated no-overlap restart of all API/worker replicas in Task 8.
- Task 6: pending
- Task 7: pending
- Task 8: pending
