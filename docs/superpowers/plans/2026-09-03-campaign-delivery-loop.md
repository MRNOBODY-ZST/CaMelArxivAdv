# Campaign Delivery and Feedback Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete and prove a durable paper-to-personalized-mail pipeline with campaign approval, rate-limited SMTP delivery, engagement callbacks, unsubscribe handling, inbound reply/bounce monitoring, and an isolated 20-message safety live run.

**Architecture:** PostgreSQL is the source of truth and owns campaign state, eligibility, delivery leases, limits, tokens, and reconciliation. The API writes privacy-minimal Kafka wake-ups through the existing outbox; the Spring `mail-worker` claims due work transactionally and performs SMTP/IMAP I/O after commit. Safety validation has dedicated tables and sends only to the deployment-fixed inbox, while production delivery uses the existing campaign recipient and tracking tables.

**Tech Stack:** Java 25, Spring Boot WebFlux 4.1, Reactor, R2DBC PostgreSQL 17, Flyway, Spring Kafka, Angus Mail, JUnit/Testcontainers, Vue 3, TypeScript 6, Vitest, Tailwind CSS, Docker Compose, Nginx, Anthropic-compatible Claude personalization through Python/Ray.

**Spec:** `docs/superpowers/specs/2026-09-03-campaign-delivery-loop-design.md`

## Global Constraints

- Safety validation sends only to the deployment variable `CAMPAIGN_SAFETY_RECIPIENT`; an API request can never supply or override the destination.
- A safety run is capped at 20 messages, requires literal confirmation `SAFETY_REDIRECT`, obeys the SMTP account's minute/hour/day/domain limits, and never mutates production recipient, attempt, tracking, unsubscribe, suppression, cooldown, or analytics state.
- Production delivery requires `HIGH` confidence, `human_verified=true`, `CONFIRMED` evidence, an author relation, active syntax-valid contact, no suppression/unsubscribe/exclusion, and no SMTP acceptance for the email HMAC during the previous 180 days.
- SMTP `250`/2xx means `SMTP_ACCEPTED`, never delivered. An uncertain post-DATA outcome becomes terminal `OUTCOME_UNKNOWN` and is never automatically retried.
- Only explicit retryable 4xx outcomes may retry, at most three total attempts, with one-minute then five-minute delay.
- Kafka payloads and logs never contain raw email addresses, encrypted email fields, SMTP/mailbox credentials, rendered bodies, callback tokens, unsubscribe tokens, or model-provider secrets.
- Public open/click/unsubscribe tokens are independently domain-separated, signed, expiring, non-enumerable, and stored only as digests.
- Inbound matching uses RFC Message-ID references or structured DSN fields; sender address, subject, IMAP Seen, an image proxy, or a scanner click never proves reply, delivery, or human reading.
- Mailbox synchronization is read-only and stores no message bodies or attachments.
- Existing test-mail records/tokens, campaign drafts, Kafka flows, Nginx virtual hosts, database volumes, credentials, and unrelated services remain compatible.
- Every production code change is preceded by a focused failing test and followed by focused green tests plus the full affected suite before its task commit.
- Do not include the assistant product name in source, branches, commits, deployment metadata, or user-facing copy.

---

### Task 1: Add durable campaign-delivery persistence and typed configuration

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__campaign_delivery_feedback_loop.sql`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryModels.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryProperties.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignSafetyProperties.java`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/migration/FlywayMigrationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryPropertiesTest.java`

**Interfaces:**
- Produces `CampaignStatus`, `RecipientStatus`, `AttemptStatus`, `SafetyRunStatus`, `SafetyMessageStatus`, `InboundEventType`, and `TransportStage` enums in `CampaignDeliveryModels`.
- Produces `CampaignDeliveryProperties(batchSize, leaseDuration, productionCooldown, maximumAttempts, firstRetryDelay, secondRetryDelay, pollDelay)` with defaults `10`, `PT2M`, `P180D`, `3`, `PT1M`, `PT5M`, and `PT1S`.
- Produces `CampaignSafetyProperties(enabled, recipient, maximumRecipients)` with defaults `false`, blank, and `20`; `validatedRecipient()` rejects blank/invalid email when enabled.
- Produces the V16 schema consumed by Tasks 2–7.

- [ ] **Step 1: Write failing migration and property tests**

Add real PostgreSQL migration assertions for all new columns, constraints, foreign keys, indexes, and tables. Add literal property-validation tests that reject batch sizes outside 1–100, leases outside 30 seconds–15 minutes, cooldowns below one day, attempts outside 1–3, non-increasing retry delays, safety maximum outside 1–20, and an enabled safety mode with an invalid recipient.

```java
assertThat(columns("campaigns")).contains("lock_version", "mailbox_account_id", "review_preflight_digest");
assertThat(tables()).contains(
    "recipient_delivery_cooldowns", "campaign_safety_runs", "campaign_safety_messages",
    "campaign_safety_attempts", "campaign_safety_links", "campaign_safety_events",
    "mailbox_sync_cursors", "mailbox_inbound_events");
assertThat(new CampaignSafetyProperties(true, "zstbmw@163.com", 20).validatedRecipient())
    .isEqualTo("zstbmw@163.com");
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.migration.FlywayMigrationTest --tests com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryPropertiesTest
```

Expected: compilation/migration assertions fail because V16 and the property records do not exist.

- [ ] **Step 3: Implement the additive V16 schema and typed properties**

The migration must add campaign lock/review/mailbox fields; recipient lease/retry/RFC Message-ID/reply/unknown fields; structured attempt stage/code; `UNSUBSCRIBE` token type; production cooldown; dedicated safety run/message/attempt/link/event tables; and mailbox cursor/inbound-event tables. Use checks that encode these literal status sets:

```sql
CHECK (status IN ('QUEUED','CONNECTING','SMTP_ACCEPTED','TEMPORARY_FAILURE',
                  'PERMANENT_FAILURE','BOUNCED','SUPPRESSED','UNSUBSCRIBED',
                  'CANCELED','OUTCOME_UNKNOWN'));
CHECK (event_type IN ('OPEN','CLICK','UNSUBSCRIBE','REPLY','AUTO_REPLY','BOUNCE'));
CHECK (inbound_type IN ('REPLY','AUTO_REPLY','BOUNCE','UNMATCHED'));
```

Use `BYTEA CHECK (octet_length(...) = 32)` for digests, unique `(mailbox_account_id, folder_name, uid_validity, remote_uid)` for inbound idempotency, unique `(run_id, campaign_recipient_id)` for safety messages, and due-work indexes whose leading columns are state and `next_attempt_at`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all selected tests pass.

- [ ] **Step 5: Run the backend suite and commit**

```bash
cd backend
./gradlew test --no-daemon
git add src/main src/test
git commit -m "feat: add campaign delivery persistence"
```

### Task 2: Implement campaign editing, preflight, review, and lifecycle APIs

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignDtos.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignService.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignRepository.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignConfiguration.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignWorkflowService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignWorkflowRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignPreflightService.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/campaign/CampaignApiTest.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/campaign/CampaignServiceIntegrationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/CampaignWorkflowIntegrationTest.java`

**Interfaces:**
- `CampaignView` adds `mailboxAccountId`, tracking flags, `lockVersion`, review actor/times, schedule/start/complete timestamps, and delivery counts.
- `CampaignPreflightService.preflight(UUID)` returns `PreflightView(ready, checks, counts, estimatedMinutes, digest)`; management responses expose the digest as lowercase hex, not raw bytes.
- `CampaignWorkflowService` exposes `update`, `submitReview`, `approve`, `reject`, `schedule`, `start`, `pause`, `resume`, and `cancel`, each accepting actor/context and `expectedLockVersion`.
- Start/schedule insert one outbox record for `camel.mail.delivery.jobs.v1` with only version, message/run identity, action, trace, and timestamp.

- [ ] **Step 1: Write failing API, state-machine, eligibility, and race tests**

Cover each permitted transition and literal rejection. Prove preflight excludes MEDIUM, unverified, unconfirmed, authorless, suppressed, unsubscribed, campaign-excluded, syntax-invalid, and 180-day-cooled recipients. Prove a suppression inserted after an earlier preflight makes submit/start fail. Prove stale `expectedLockVersion` returns conflict and no state/outbox change. Inspect the outbox JSON and assert it contains no `email`, `subject`, `html`, `text`, `token`, `ciphertext`, or `nonce` key.

```java
webTestClient.post().uri("/api/v1/campaigns/{id}/submit-review", id)
    .bodyValue(Map.of("expectedLockVersion", 0))
    .exchange().expectStatus().isOk()
    .expectBody().jsonPath("$.status").isEqualTo("READY_FOR_REVIEW");
```

- [ ] **Step 2: Run focused tests and verify RED**

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.campaign.CampaignApiTest --tests com.camel_hub.advertisement.campaign.CampaignServiceIntegrationTest --tests com.camel_hub.advertisement.campaign.CampaignWorkflowIntegrationTest
```

Expected: missing DTOs/endpoints/services and failed preflight assertions.

- [ ] **Step 3: Implement draft update and side-effect-free preflight**

Use one SQL aggregation over the frozen recipients and current contact/evidence/suppression/cooldown state. Return named checks `CONTENT_READY`, `UNSUBSCRIBE_PRESENT`, `SENDER_VALID`, `SMTP_READY`, `MAILBOX_READY`, `TRACKING_READY`, and `RECIPIENTS_ELIGIBLE`. Draft updates are restricted to name, purpose, mailbox, from-name, reply-to, and tracking flags; changes increment `lock_version`.

- [ ] **Step 4: Implement lifecycle transitions, audit, and privacy-minimal wake-up outbox**

All transition SQL must include `WHERE id=:id AND status IN (...) AND lock_version=:expected`. Persist actor/timestamp and the preflight digest. Map permission annotations exactly: create/edit/submit `campaign:create`; approve/reject `campaign:approve`; schedule/start `campaign:send`; pause/resume/cancel `campaign:pause`.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all selected tests pass.

- [ ] **Step 6: Run the backend suite and commit**

```bash
cd backend
./gradlew test --no-daemon
git add src/main src/test
git commit -m "feat: add campaign review workflow"
```

### Task 3: Build the production delivery worker, atomic limits, and safe SMTP outcomes

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/smtp/SmtpTransport.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/smtp/SmtpTransportException.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/messaging/KafkaTopics.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/messaging/OutboxRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/DeliveryMessagingConfiguration.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryExecutor.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryScheduler.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryListener.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryWorkerConfiguration.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/MailWorkerProfileIsolationTest.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/email/smtp/SmtpTransportTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryRepositoryIntegrationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryExecutorTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryConcurrencyIntegrationTest.java`

**Interfaces:**
- `SmtpTransport.sendDetailed(account, message)` returns `SmtpOutcome(status, stage, responseCode, responseSummary)` or throws a typed exception carrying the same safe metadata; existing `send` behavior remains source-compatible for diagnostic/template sends.
- `CampaignDeliveryRepository.claimNext(Instant)` returns at most one leased `ProductionClaim` after send-time eligibility, SMTP-row locking, four-window capacity reservation, and cooldown locking.
- `CampaignDeliveryExecutor.pumpOnce()` sends at most one claim and persists one terminal/retry state.
- `CampaignDeliveryListener` consumes `camel.mail.delivery.jobs.v1` with manual acknowledgment; payload validation failure goes to the existing DLT publisher and duplicate wake-ups are harmless.

- [ ] **Step 1: Write failing transport, limit, lease, concurrency, and retry tests**

Use GreenMail or the existing SMTP test harness to distinguish accepted, explicit 4xx, explicit 5xx, pre-DATA connection failure, and post-DATA uncertainty. With real PostgreSQL, run concurrent claims against limits `2/minute`, `10/hour`, `30/day`, `10/domain/hour` and assert no 11th same-domain hourly reservation. Assert only one worker claims a recipient, stale leases become `OUTCOME_UNKNOWN`, explicit 4xx retries at +1m/+5m, attempt 3 is terminal, and post-DATA uncertainty never requeues.

```java
assertThat(outcome.status()).isEqualTo(AttemptStatus.SMTP_ACCEPTED);
assertThat(repository.claimNext(now).block()).isNull();
assertThat(status(recipientId)).isEqualTo("OUTCOME_UNKNOWN");
```

- [ ] **Step 2: Run focused tests and verify RED**

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.email.smtp.SmtpTransportTest --tests 'com.camel_hub.advertisement.campaign.delivery.*' --tests com.camel_hub.advertisement.MailWorkerProfileIsolationTest
```

Expected: missing delivery worker types and failed structured-outcome/limit assertions.

- [ ] **Step 3: Implement structured SMTP outcomes without breaking test sends**

Keep certificate/hostname verification and password clearing. Assign a stable RFC Message-ID and correlation header. Preserve `send()` as an adapter that throws on non-acceptance; the delivery executor uses detailed stage/code/certainty metadata and sanitizes response text to 500 control-free characters.

- [ ] **Step 4: Implement transactional claim, capacity reservation, cooldown, and completion**

Claim with `FOR UPDATE SKIP LOCKED`, lock the SMTP account, count `CONNECTING` plus accepted reservations across production and safety attempt tables, create the attempt/lease before I/O, and condition completion on the lease digest. Decrypt only the claimed recipient immediately before transport and discard the string reference after use. Recompute `next_attempt_at` from rolling windows and specified retry delays.

- [ ] **Step 5: Add Kafka topic/listener and reconciliation scheduler**

Declare only `camel.mail.delivery.jobs.v1` for this flow, extend the outbox allowlist, manually acknowledge after `pumpOnce` has durably settled or found no work, and schedule the same pump every second plus lease/campaign reconciliation. The mail-worker profile must expose no management controllers.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all selected tests pass.

- [ ] **Step 7: Run the backend suite and commit**

```bash
cd backend
./gradlew test --no-daemon
git add src/main src/test
git commit -m "feat: add rate limited campaign mail worker"
```

### Task 4: Add production open, click, and unsubscribe callbacks

**Files:**
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingSigner.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignLinkRewriter.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignUnsubscribeController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingConfiguration.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailOpenController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailClickController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryExecutor.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingSignerTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingApiIntegrationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignUnsubscribeIntegrationTest.java`

**Interfaces:**
- Signer formats are domain-separated as `campaign-open:v1`, `campaign-click:v1`, and `campaign-unsubscribe:v1`; verification returns canonical recipient/link/expiry values only.
- `CampaignTrackingService.prepare(ProductionClaim)` persists token/link digests and returns final subject/HTML/text plus `List-Unsubscribe` headers before SMTP.
- Existing `/t/o/{token}` and `/t/c/{token}` attempt existing test-mail resolution then campaign resolution without distinguishing failure responses.
- `GET /u/{token}` returns a no-store confirmation page; `POST /u/{token}` and one-click form encoding perform the atomic production unsubscribe.

- [ ] **Step 1: Write failing signature, rewrite, callback, and unsubscribe tests**

Cover cross-token rejection, altered/expired tokens, relative/userinfo/javascript/data/open-redirect targets, HEAD no-count, same-minute deduplication, classification, unknown token indistinguishability, idempotent unsubscribe, concurrent unsubscribe, global suppression creation, pending-recipient transition, and no raw email/token in API responses or logs.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
cd backend
./gradlew test --tests 'com.camel_hub.advertisement.campaign.tracking.*' --tests com.camel_hub.advertisement.email.tracking.MailTrackingApiIntegrationTest
```

Expected: missing campaign signer/callback/unsubscribe components.

- [ ] **Step 3: Implement signing, final rendering, link persistence, and callback routing**

Replace `{{unsubscribe_url}}` in HTML and text first, exclude that URL from click rewriting, persist validated absolute HTTP(S) targets, append one pixel when enabled, and preserve existing test-mail token behavior. Persist raw and classified campaign events with per-minute fingerprint deduplication and update first-event timestamps conditionally.

- [ ] **Step 4: Implement idempotent unsubscribe and suppression**

In one transaction, resolve the digest/expiry, insert `unsubscribe_records` and `suppression_entries` with conflict-safe semantics, update unsent matching campaign recipients to `UNSUBSCRIBED`, and never expose whether the token had already been consumed. Safety tokens are not handled by this production repository.

- [ ] **Step 5: Integrate prepared content and headers into delivery, run GREEN, and commit**

```bash
cd backend
./gradlew test --tests 'com.camel_hub.advertisement.campaign.tracking.*' --tests com.camel_hub.advertisement.email.tracking.MailTrackingApiIntegrationTest --tests 'com.camel_hub.advertisement.campaign.delivery.*'
./gradlew test --no-daemon
git add src/main src/test
git commit -m "feat: add campaign engagement callbacks"
```

### Task 5: Implement isolated campaign safety live runs

**Files:**
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/safety/CampaignSafetyRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/safety/CampaignSafetyService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/safety/CampaignSafetyTrackingService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/safety/CampaignSafetyController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryRepository.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryExecutor.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryWorkerConfiguration.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailOpenController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailClickController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignUnsubscribeController.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/safety/CampaignSafetyIntegrationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/safety/CampaignSafetyIsolationTest.java`

**Interfaces:**
- `POST /api/v1/campaigns/{id}/safety-runs` accepts `SafetyStartRequest(expectedLockVersion, recipientLimit, confirmation)` and returns `SafetyRunView`; no destination field exists.
- `GET /api/v1/campaigns/{id}/safety-runs` and `GET /api/v1/campaigns/{id}/safety-runs/{runId}` expose masked destination, progress, terminal counts, safe event counts, and message rows without raw logical addresses or tokens.
- `CampaignDeliveryRepository.claimNext` returns either a `ProductionClaim` or `SafetyClaim`; both share SMTP account locking/limits but use disjoint completion repositories.

- [ ] **Step 1: Write failing configuration, authorization, cap, routing, and isolation tests**

Prove disabled safety mode rejects; missing/wrong confirmation rejects; limit 0/21 rejects; JSON destination fields are rejected; a 20-row run always sends to the configured safety address; logical addresses never appear in MIME, logs, Kafka, or responses; production recipient/attempt/tracking/unsubscribe/suppression/cooldown tables remain byte-for-byte/count-for-count unchanged after accepts, open/click callbacks, unsubscribe simulation, and safety failure.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
cd backend
./gradlew test --tests 'com.camel_hub.advertisement.campaign.safety.*' --tests 'com.camel_hub.advertisement.campaign.delivery.*'
```

Expected: missing safety API/repository/executor support and failed isolation assertions.

- [ ] **Step 3: Implement run materialization and fixed-destination delivery**

Snapshot up to the requested number of `GENERATED` drafts ordered by recipient UUID, add a visible safety banner, use the fixed address for both MIME `To` and SMTP envelope, write only dedicated safety tables, and enqueue a privacy-minimal wake-up. Use the same account rate reservation as production and independent safety leases/attempts.

- [ ] **Step 4: Implement isolated safety callbacks and terminal run aggregation**

Use domain-separated safety open/click/unsubscribe tokens. Record only safety events. Complete a run when every safety message is `SMTP_ACCEPTED`, `PERMANENT_FAILURE`, `CANCELED`, or `OUTCOME_UNKNOWN`; label mixed terminal outcomes `PARTIALLY_FAILED`.

- [ ] **Step 5: Run GREEN, full backend suite, and commit**

```bash
cd backend
./gradlew test --tests 'com.camel_hub.advertisement.campaign.safety.*' --tests 'com.camel_hub.advertisement.campaign.delivery.*' --tests 'com.camel_hub.advertisement.campaign.tracking.*'
./gradlew test --no-daemon
git add src/main src/test
git commit -m "feat: add isolated campaign safety runs"
```

### Task 6: Add read-only IMAP reply and bounce reconciliation

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxTransport.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/inbound/InboundMailModels.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/inbound/InboundMailParser.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/inbound/InboundMailRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/inbound/InboundMailSynchronizer.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/inbound/InboundMailConfiguration.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryWorkerConfiguration.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/email/mailbox/MailboxTransportTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/inbound/InboundMailParserTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/inbound/InboundMailIntegrationTest.java`

**Interfaces:**
- `MailboxTransport.readSince(account, folder, lastUid, limit)` returns UIDVALIDITY plus bounded `InboundEnvelope` values containing only UID, Message-ID, In-Reply-To, References, Auto-Submitted, content type, received time, and structured DSN fields.
- `InboundMailParser.classify(InboundEnvelope)` returns `ParsedInbound(type, referencedMessageIds, diagnosticCode, permanent)`.
- `InboundMailSynchronizer.syncOnce(mailboxId)` uses a cursor lease, persists each UID idempotently, associates only by controlled RFC Message-ID, and advances the cursor only after durable event persistence.

- [ ] **Step 1: Write failing parser, UID cursor, privacy, and state tests**

Fixtures cover a human reply, RFC auto-reply, permanent multipart/report DSN, temporary DSN, altered/unrelated message IDs, duplicate UID, UIDVALIDITY reset, and malformed MIME. Assert subject/sender alone never matches; permanent production DSN produces `BOUNCED` plus suppression; reply sets `replied_at` only; safety matches create safety events only; no body/attachment/raw sender is persisted.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.email.mailbox.MailboxTransportTest --tests 'com.camel_hub.advertisement.campaign.inbound.*'
```

Expected: missing read-since/parser/synchronizer behavior.

- [ ] **Step 3: Implement bounded read-only IMAP envelopes and deterministic parsing**

Use `UIDFolder`, `READ_ONLY`, a maximum batch of 50, bounded header values, and only DSN MIME parts needed for `Action`, `Status`, `Diagnostic-Code`, and original Message-ID. Never fetch attachment bytes or call flag/move/delete APIs.

- [ ] **Step 4: Implement leased cursor synchronization and isolated state updates**

Persist the inbound event and cursor advance in one transaction per UID. On malformed/unmatched input record `UNMATCHED`; on transient mailbox failure retain the previous cursor and categorized sync error. Match production and safety Message-IDs in separate queries and apply separate state transitions.

- [ ] **Step 5: Run GREEN, full backend suite, and commit**

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.email.mailbox.MailboxTransportTest --tests 'com.camel_hub.advertisement.campaign.inbound.*'
./gradlew test --no-daemon
git add src/main src/test
git commit -m "feat: monitor campaign replies and bounces"
```

### Task 7: Expose truthful reporting and build the campaign operations UI

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignReportingRepository.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignReportingService.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignReportingController.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/campaign/CampaignReportingIntegrationTest.java`
- Modify: `frontend/src/modules/campaigns/campaigns.types.ts`
- Modify: `frontend/src/modules/campaigns/campaigns.api.ts`
- Modify: `frontend/src/modules/campaigns/CampaignDetailView.vue`
- Modify: `frontend/src/modules/campaigns/DeliveriesView.vue`
- Modify: `frontend/src/modules/campaigns/CampaignAnalyticsView.vue`
- Modify: `frontend/src/modules/campaigns/LinkAnalyticsView.vue`
- Modify: `frontend/src/modules/campaigns/__tests__/campaign.views.spec.ts`
- Create: `frontend/src/modules/campaigns/__tests__/campaign.delivery.spec.ts`
- Create: `frontend/e2e/campaign-delivery.spec.ts`

**Interfaces:**
- Reporting DTOs add production `OUTCOME_UNKNOWN`, bounce, unsubscribe, reply, raw/likely-human/automated open/click counts and safety-run summaries; every rate handles denominator zero.
- Frontend API exports `preflightCampaign`, lifecycle mutation methods, `startSafetyRun`, `listSafetyRuns`, `getSafetyRun`, and campaign-scoped delivery/engagement reads.
- `CampaignDetailView` polls every three seconds only while personalization, safety, or production work is nonterminal and stops on unmount.

- [ ] **Step 1: Read the frontend Tailwind/design-system skill before editing UI**

Read `/Users/hades/.codex/skills/frontend-tailwind-css/SKILL.md` completely and preserve the existing `DsCard`, `DsBadge`, `DsAlert`, `DsButton`, `DsModal`, `DsTabs`, `DsPagination`, and `DsEmptyState` visual language. Do not introduce another component library.

- [ ] **Step 2: Write failing backend reporting and frontend behavior tests**

Backend fixtures must prove safety events do not enter production totals and zero denominators return zero. Vue tests must prove permission-aware controls, lifecycle labels, preflight exclusion counts, literal confirmation, fixed masked destination, 1–20 limit, progress polling, terminal stop, separate test/safety/production tabs, and truthful wording: “SMTP 已接受不等于最终送达” and “回传不等于确认人工阅读”.

- [ ] **Step 3: Run focused tests and verify RED**

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.campaign.CampaignReportingIntegrationTest
cd ../frontend
npm test -- --run src/modules/campaigns/__tests__/campaign.views.spec.ts src/modules/campaigns/__tests__/campaign.delivery.spec.ts
```

Expected: missing DTO fields, client methods, controls, tabs, and copy.

- [ ] **Step 4: Implement reporting queries, typed API client, and focused operational UI**

Keep campaign status and personalization status separate. Put lifecycle/preflight/primary actions at the top of the detail page, safety validation in one bounded card, and per-recipient evidence below. Never return/display raw addresses, credentials, message bodies from IMAP, or callback tokens. Use existing pagination and horizontal table overflow.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the command from Step 3. Expected: all selected tests pass.

- [ ] **Step 6: Run full frontend/backend verification and commit**

```bash
cd backend
./gradlew test --no-daemon
cd ../frontend
npm test -- --run
npm run typecheck
npm run lint
npm run build
git add ../backend/src/main ../backend/src/test src e2e
git commit -m "feat: add campaign delivery operations UI"
```

### Task 8: Wire runtime configuration, verify the stack, deploy, and execute the authorized safety run

**Files:**
- Modify: `.env.example`
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Modify: `scripts/verify-compose.sh`
- Modify: `scripts/verify-container-images.sh`
- Create: `docs/operations/campaign-delivery-runbook.md`
- Modify only if required for `/u/`: the project-owned Nginx deployment template or production `arxiv.nodexi.top` location block; do not touch other virtual hosts.

**Interfaces:**
- Compose creates `camel.mail.delivery.jobs.v1`, configures mail-worker delivery/safety/IMAP properties, and keeps secrets in runtime environment only.
- The runbook defines preflight, backup, rollback, Kafka lag, SMTP/IMAP health, rate-window wait, callback privacy, safety-isolation queries, and the external-production stop gate.

- [ ] **Step 1: Write failing configuration-contract checks**

Extend verification scripts/tests to require the delivery topic, mail-worker profile, safety maximum 20, default-disabled safety/live switches in `.env.example`, health checks, no host-published Kafka/Ray ports, and no secret literals. Execute the checks and record their expected failures before changing Compose/docs.

```bash
bash scripts/verify-compose.sh
bash scripts/verify-container-images.sh
```

- [ ] **Step 2: Add minimal runtime configuration and operations documentation**

Add `CAMPAIGN_SAFETY_ENABLED=false`, blank `CAMPAIGN_SAFETY_RECIPIENT`, `CAMPAIGN_SAFETY_MAX_RECIPIENTS=20`, delivery timing defaults from Task 1, and inbound polling defaults. The production runtime may set the fixed safety recipient outside Git. Document that production authors remain blocked until concrete identity/purpose and final approval exist.

- [ ] **Step 3: Run complete local verification**

```bash
cd backend
./gradlew --no-daemon clean check bootJar
cd ../worker
UV_PROJECT_ENVIRONMENT=/Users/hades/CaMelArxivAdv/worker/.venv uv run pytest -q
UV_PROJECT_ENVIRONMENT=/Users/hades/CaMelArxivAdv/worker/.venv uv run ruff check .
UV_PROJECT_ENVIRONMENT=/Users/hades/CaMelArxivAdv/worker/.venv uv run mypy src tests
cd ../frontend
npm test -- --run
npm run typecheck
npm run lint
npm run build
cd ..
bash scripts/verify-compose.sh
bash scripts/verify-container-images.sh
docker compose config --quiet
```

Expected: every command exits zero; Vitest and Pytest report zero failures; Gradle reports `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit runtime wiring and request whole-branch review**

```bash
git add .env.example docker-compose.yml README.md scripts docs/operations
git commit -m "ops: wire campaign delivery runtime"
```

Generate the full review package from the branch merge base through HEAD. Fix all Critical/Important findings in one reviewed fix wave before merging.

- [ ] **Step 5: Merge, push, back up, and deploy affected production services**

Fast-forward the reviewed branch into `main`, push `origin/main`, create/checksum a PostgreSQL dump, record current image SHAs, build immutable images from the merged commit, run the V16 migration, and update only backend API, mail worker, and frontend. Verify health, Nginx config, the `arxiv.nodexi.top` site, Kafka topic descriptions/consumer lag, and authenticated campaign APIs before enabling safety mode.

- [ ] **Step 6: Execute the authorized 20-message safety live acceptance**

Set the runtime-only fixed safety recipient to `zstbmw@163.com` and enable safety mode. Through authenticated APIs and the frontend: discover/import a bounded relevant paper set, run source extraction, materialize 20 distinct generated drafts through Claude/Ray, start one `SAFETY_REDIRECT` run with limit 20, and monitor it through the provider's real rate windows until terminal. Verify 20 matching IMAP headers without printing bodies or raw callback tokens.

- [ ] **Step 7: Verify public callbacks and safety isolation**

Exercise one opaque open, click, and safety-unsubscribe callback through `https://arxiv.nodexi.top`, keeping tokens in a mode-600 temporary file and deleting it afterward. Confirm callback counts/classifications, Nginx no-log/rate-limit behavior, zero production delivery/tracking/unsubscribe/suppression/cooldown changes attributable to the safety run, healthy containers, zero actionable Kafka lag, and no secret/token leakage in logs.

- [ ] **Step 8: Validate the production UI in Edge and stop at the external-send gate**

Open campaign detail, safety progress, deliveries, campaign analytics, and link analytics in Edge at desktop and narrow viewport widths. Confirm meaningful DOM, no framework overlay, no console error/warning, working controls/pagination, truthful SMTP/engagement copy, and the 20 terminal safety messages. Prepare but do not send the real-author campaign; report that the concrete sender identity and contact purpose are still required before its final review/approval.
