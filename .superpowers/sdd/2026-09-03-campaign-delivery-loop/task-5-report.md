# Task 5 Report: Isolated Campaign Safety Live Runs

## Status

COMPLETE. Independent specification and quality/safety re-review both passed with no
P0 or P1 findings. The candidate adds an explicitly
enabled safety-run API and worker path that sends immutable generated campaign drafts
only to one startup-validated configured inbox. Safety runs use dedicated persistence,
leases, attempts, callback capabilities, events, cancellation, and aggregation. They
share only the SMTP-account reservation lock and rolling provider limits with production.

No public SMTP was used, no external message was sent, and no deployment or proxy
configuration was changed. The implementation starts from Task 4 commit `5f85dc6` and
is intentionally left uncommitted for independent review.

## TDD evidence

### Initial RED

The first configuration, API, materialization, and tagged-executor tests failed because
the safety properties, API, repository, destination policy, and `SafetyClaim` branch did
not exist. The implementation was then grown through focused vertical slices.

### Security and concurrency RED / GREEN cycles

Each correction below was reproduced by a focused failing regression before its fix:

- A rate-limited safety message returned an empty claim and fell through to production
  in the same transaction, mutating a due production recipient. Claiming now returns an
  internal claimed/handled/no-work decision and starts a separate production transaction
  only for genuine no-work.
- Jackson coercion accepted string or floating-point lock versions and recipient limits.
  Exact deserializers now reject wrong scalar types, nulls, duplicates, unknown fields,
  and trailing JSON roots before service invocation. A real reactive method-security
  context also proved anonymous 401, wrong-authority 403, and authorized success.
- Draft management responses exposed raw, entity-encoded, deeply percent-encoded,
  NFKC-compatible, comment-split, and element-split safety capabilities. The shared
  public redactor now fails closed over subject, serialized HTML, joined visible text,
  every attribute, and plain text even before artifacts are frozen.
- Production first-send preparation had the analogous split-node gap. A real production
  prepare test first accepted safety tokens split across a `span` or comment; the same
  inspection boundary now rejects them with zero production tracking artifacts.
- Production frozen retry retained a separate serialization-only scan. Safety,
  production, and test-mail capabilities split across elements or comments were first
  accepted by a real frozen retry; joined-visible-node validation now rejects all three
  issuer domains without changing the existing production artifacts.
- Frozen safety validation initially trusted regex counts rather than exact structure.
  Twelve tamper variants now prove cross-message tokens, extra unsubscribe tokens,
  attacker nesting, wrong origin/path, wrong DOM roles, arbitrary attributes, bare
  tokens, duplicate/orphan links, and split-node safety open/click/unsubscribe tokens
  all fail before SMTP without changing stored artifacts.
- Safety and production reservations originally enforced shared capacity in only one
  direction. Parameterized minute/hour/day/domain-hour tests now prove both historical
  directions and concurrent competition for the final slot without oversubscription.
- Safety start read SMTP health without locking the account row. A PostgreSQL concurrency
  test held and invalidated that row and showed the old start did not wait. Materialize
  now locks campaign then SMTP account and reads a fresh health/exclusivity snapshot.
- Two simultaneous safety starts both created active runs because the subquery snapshot
  preceded the campaign-row wait. A real RED produced two aggregates. A post-lock fresh
  exclusivity read plus a V17 partial unique index now permits exactly one active run and
  returns an explicit conflict for the loser.
- A production claim racing a safety start now proves the same campaign lock boundary:
  either production obtains a committed SMTP lease or safety materializes, never both.
- SMTP response sanitization missed encoded and punctuation-adjacent safety/test-mail
  forms. Raw and repeatedly encoded tests now cover production, safety, and test-mail
  open/click/unsubscribe token shapes without persisting the capability.

### Independent review repair cycle

The first independent final review ran the focused and complete backend suites but
correctly rejected the candidate with three P1 findings and one P2 finding. Each was
then reproduced by a failing regression before repair:

- A retryable SMTP 450 followed by a shared hourly-limit defer could outlive the
  frozen callback TTL and become a permanent preparation failure. Safety retries now
  accept expired capabilities only for structural and durable-artifact verification,
  and rotate them only when the directly preceding persisted attempt is an explicit
  retryable `SMTP_REJECTED` 4xx. Rotation updates the existing link rows with CAS
  predicates inside the lease-fenced preparation transaction, preserving Message-ID,
  correlation, link IDs, targets, and existing event foreign keys. Nine negative
  provenance/tamper cases and a late lease failure prove fail-closed rollback.
- Disabling safety mode previously removed the repository/API/callback wiring while
  production SQL still excluded campaigns with active safety runs. The flag now
  prevents only new starts and claims. Read/cancel remains available, valid callbacks
  continue while tracking is enabled, and the worker durably and idempotently marks
  active runs canceled before pumping production in the same scheduler tick. Handed-off
  messages retain their lease fence and cannot revive the canceled aggregate.
- A user-controlled From display name could carry a production, test-mail, or safety
  capability into the final MIME. Materialization and final pre-SMTP validation now
  inspect sender metadata across raw, repeated encoding, entity, RFC 2047, and NFKC
  views; mailbox address fields retain separate strict address validation. Encoded
  injection variants and post-materialization tampering all stop before SMTP.
- Kafka wake-up parsing now enables duplicate-field detection, rejects trailing JSON
  roots, and requires the exact canonical lowercase UUID serialization. Invalid input
  still produces only the fixed redacted DLT envelope, acknowledges after durable DLT
  publication, and never invokes the delivery pump.
- Re-review then exercised composed encodings instead of only repeated encodings. All
  four public-content boundaries now apply a bounded fixed point of NFKC, URL, HTML
  entity, and RFC 2047 decoding, then fail closed when another decoding round would
  still change the value. Fullwidth-percent compositions and capability tokens wrapped
  in arbitrary text are rejected at safety materialization, production initial/frozen
  preparation, management API redaction, and SMTP response sanitization.
- Source and final sender metadata now use the same fail-closed policy: display names
  are decoded and inspected, while From and Reply-To must also be strict bare mailbox
  values and capability-free. Regression tests cover both source-row injection and
  post-materialization tampering before any SMTP connection.
- A deleted safety-run creator originally made disabled-mode cancellation bind a null
  audit actor incorrectly and roll back. A real PostgreSQL `ON DELETE SET NULL` path now
  proves cancellation, null-actor audit insertion, and immediate production-gate
  release. Disabled-mode callback continuity directly exercises OPEN, CLICK, and
  UNSUBSCRIBE, including the click target, isolated event counts, and an unchanged
  production-table snapshot.
- Expiry-rotation tests now use the minimum legal retry configuration and exercise old
  versus rotated OPEN, CLICK, and UNSUBSCRIBE callbacks, stable sender/recipient/body
  metadata, link targets, Message-ID, and correlation. The invalid-provenance and
  lease-loss rollback suites run under the same startup-valid configuration.
- The otherwise legal one-minute callback TTL combined with the default two-minute
  delivery lease could previously start successfully and then fail every safety message
  before SMTP. The API and worker safety policy now consume the configured lease and
  reject startup unless the TTL has at least one full second of headroom beyond it. This
  accounts for whole-second capability expiry truncation while leases retain sub-second
  precision; exact boundary and real configuration-context regressions prove the gate.

## Final verification

Focused Task 5 command:

```bash
cd backend
./gradlew test \
  --tests 'com.camel_hub.advertisement.campaign.safety.*' \
  --tests 'com.camel_hub.advertisement.campaign.delivery.*' \
  --tests 'com.camel_hub.advertisement.campaign.tracking.*' \
  --tests 'com.camel_hub.advertisement.email.smtp.SmtpTransportTest' \
  --tests 'com.camel_hub.advertisement.migration.FlywayMigrationTest' \
  --no-daemon
```

Fresh serial result and JUnit XML aggregation:

```text
BUILD SUCCESSFUL in 1m 8s
tests=259
failures=0
errors=0
skipped=0
```

Full backend command:

```bash
cd backend
./gradlew test --no-daemon
```

Fresh serial result and JUnit XML aggregation:

```text
BUILD SUCCESSFUL in 2m 4s
tests=682
failures=0
errors=0
skipped=0
```

Static verification:

```bash
git diff --check
# no output
```

A filename-only scan across all 40 changed, untracked, and report files found no supplied
public address, host, password, API-key prefix, other known deployment credential,
assistant-product name, or unfinished-work marker. Whitespace checks also included
the untracked files that plain `git diff --check` does not visit.

## Implementation summary

- `CampaignSafetyProperties` accepts at most one strict ASCII mailbox, canonicalizes its
  domain, enforces a 1..20 configured cap, and remains disabled by default.
  `CampaignSafetyRuntimePolicy` requires live SMTP and public HTTPS callback readiness,
  computes a domain-separated destination HMAC, and revalidates that HMAC at creation,
  claim, preparation, and final send boundaries.
- The API exposes start/list/get plus explicit optimistic-lock cancel. The request body
  has no destination field, requires the literal `SAFETY_REDIRECT`, uses exact JSON
  scalar contracts, and applies `campaign:send`, `campaign:read`, and `campaign:pause`
  permissions. Responses contain only a mask, safe progress/event totals, IDs, and
  delivery states.
- `CampaignSafetyRepository` obtains campaign then SMTP locks, takes a fresh current-state
  snapshot, rejects unhealthy SMTP, active safety, production `CONNECTING`, stale
  version, empty selection, and non-immutable drafts, then copies at most 20 `GENERATED`
  rows in recipient-UUID order. It writes only dedicated safety tables plus a minimal
  outbox wake-up and audit record. Destination plaintext and logical author addresses
  are never copied.
- V17 freezes approved From name/address, Reply-To, and tracking flags on the run, adds
  strict non-null/control checks, and enforces one active run per campaign. This avoids
  claim-time reads of mutable campaign sender configuration while SMTP account secrets
  remain locked/decrypted only at transport time.
- `CampaignDeliveryRepository.claimNext` is an exhaustive sealed union of
  `ProductionClaim` and `SafetyClaim`. Safety has priority when due, but both branches
  serialize on the same SMTP account and count the union of production and safety
  `CONNECTING`/accepted attempts in all four rolling windows. Safety never decrypts
  `ContactCrypto`, writes cooldowns, calls the production preparer, or invokes production
  settlement.
- Safety MIME uses the configured address as both envelope RCPT and visible `To`, stable
  frozen Message-ID/correlation/sender snapshots, multipart HTML/text, an unmistakable
  banner, and exact RFC 8058 headers. Final content validation rejects email-like logical
  data, controls, NFKC confusables, foreign capabilities, and malformed callback roles.
- `CampaignSafetySigner` uses independent `campaign-safety-open`,
  `campaign-safety-click`, and `campaign-safety-unsubscribe` HMAC contexts. Digest-only
  rows bind tokens to one safety message/link and canonical public origin. Existing
  generic callback controllers route the registered namespace, so HEAD never records,
  cross-type/cross-issuer tokens fail indistinguishably, redirect targets use the shared
  policy, and unsubscribe POST records simulation only—never production suppression.
- Explicit retryable SMTP 4xx outcomes retry at +1m/+5m through attempt three. Other
  failures are permanent; post-DATA uncertainty and expired leases become
  `OUTCOME_UNKNOWN`. Cancellation is sticky across callbacks, late acceptance, and
  lease reconciliation. Terminal aggregation implements COMPLETED, FAILED,
  PARTIALLY_FAILED, and explicit CANCELED exactly.
- Kafka accepts only the existing exact production shape or the privacy-minimal safety
  discriminator (`action=SAFETY_START`, `safetyRunId`, no recipient/body/token/address).
  Mixed, expanded, wrongly typed, or unknown payloads are rebuilt as a fixed redacted
  DLT envelope before acknowledgment.

## Isolation evidence

The required 20-message local SMTP acceptance is implemented in
`CampaignSafetyIntegrationTest` rather than a separate `CampaignSafetyIsolationTest`
file so one PostgreSQL/SMTP fixture can verify the complete lifecycle without duplicating
heavy setup. It captures all 20 envelope transactions and MIME messages and asserts:

- every envelope RCPT and visible `To` is the configured fixture address;
- no logical address appears in MIME, logs, Kafka payloads, API output, or stored safety
  destination fields;
- there is no Cc/Bcc, Message-ID/correlation values are unique and stable, both MIME
  alternatives carry the safety banner, and all callbacks use only the safety namespace;
- production campaigns, recipients, attempts, contacts, exclusions, cooldowns,
  tracking, unsubscribe, and suppression table content digests are unchanged across
  materialization, accept, callbacks, unsubscribe simulation, explicit failure,
  cancellation, and lease reconciliation.
- Expired frozen safety capabilities are never treated as valid callbacks. They may be
  authenticated without an expiry check only during a fenced retry preparation, after
  exact body/artifact/provenance validation, so an explicit prior retryable SMTP 4xx can
  atomically receive fresh capabilities without changing its stable message identity.
- Turning safety mode off is a durable kill switch rather than a bean-removal trap:
  existing runs remain observable/cancelable, active work converges to sticky canceled,
  valid already-sent callbacks remain isolated and observable, and production gating is
  released without sending new safety mail.

## Changed scope

Production additions are the nine classes under
`backend/src/main/java/com/camel_hub/advertisement/campaign/safety/` and migration
`V17__campaign_safety_sender_snapshots.sql`. Production modifications are limited to the
campaign delivery claim/executor/listener/scheduler/wiring and safety properties, plus
cross-namespace public redaction, production draft validation, and SMTP summary
sanitization. Tests cover safety API/content/policy/signer/integration, delivery
repository/executor/listener/scheduler/concurrency/wiring, production tracking, SMTP,
and fresh/upgrade Flyway behavior.

The existing open/click/unsubscribe controllers did not require Task 5 edits because
Task 4 already supplied the ordered `CampaignCallbackNamespace` extension point and
central final redirect policy. No Task 6 inbound implementation, Task 7 UI/reporting,
Task 8 deployment configuration, V16 checksum, or public infrastructure was changed.

## Remaining non-blocking work

- Reply, auto-reply, and bounce population of safety events belongs to the read-only
  inbound mailbox implementation in Task 6; the Task 5 schema and aggregate view already
  expose those counts.
- Public callback routing, real account configuration, and external smoke testing remain
  Task 8 work. This task deliberately used only loopback SMTP fixtures.
- Disabling safety is a coordinated restart operation in the single-machine Compose
  deployment, not a dynamic cross-node feature flag. Task 8 must stop and drain every
  old `safety=true` API/worker instance before starting the disabled configuration, so
  old and new policy replicas never overlap.
- The first independent specification and quality/safety review found the four issues
  documented above. Their regressions, the additional re-review boundary tests, and the
  complete backend suite now pass. Final independent re-review found no remaining P0 or
  P1 issue; the coordinated no-overlap restart is the sole documented P2 constraint.
