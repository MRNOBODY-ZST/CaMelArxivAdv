# Test Mail Engagement Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make test-mail send outcomes cancellation-safe, reconcile stale pending rows, add signed link-click callbacks, and present public callback evidence truthfully in the production UI.

**Architecture:** Extend the existing isolated `email/tracking` module and `mail_send_records` aggregate. Persist validated link targets and opaque token hashes before SMTP, resolve public click callbacks without accepting caller-supplied redirect URLs, and keep transport finalization alive once the SMTP attempt starts. A bounded reconciler converts abandoned pending rows to `UNKNOWN`; Vue surfaces separate image and click evidence.

**Tech Stack:** Java 25, Spring Boot WebFlux 4.1, Reactor, R2DBC PostgreSQL 17, Flyway, jsoup, JUnit/Testcontainers, Vue 3, TypeScript 6, Vitest, Tailwind CSS, Docker Compose, Nginx, Edge Browser runtime.

**Spec:** `docs/superpowers/specs/2026-09-01-mail-engagement-reliability-design.md`

## Global Constraints

- Keep test-send records and events separate from campaign recipients and campaign analytics.
- Do not resend automatically after an uncertain SMTP outcome.
- Do not claim a callback proves human reading or clicking.
- Never expose callback tokens or caller-selected redirect targets in management APIs, logs, screenshots, or command output.
- Preserve existing open tokens, records, Nginx vhosts, database volumes, credentials, and unrelated services.
- Use the existing `trackOpens` request field for backward compatibility; enabling it adds eligible link tracking as well as the image pixel.
- Deploy only after full backend/frontend verification and a database backup.
- Do not include the assistant product name in source, branches, commits, or deployment artifacts.

---

### Task 1: Persist test-mail link definitions and click observations

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__mail_click_tracking.sql`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/migration/FlywayMigrationTest.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingModels.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingRepository.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailTrackingApiIntegrationTest.java`

**Interfaces:**
- Produces: `MailClickLink`, `MailClickEvent`, record-level `rawClickCount`, `automatedClickCount`, `firstClickAt`, and `lastClickAt`.
- Produces repository operations `insertLinks`, `resolveClick`, `observeClick`, `latestLinks`, `latestClickEvents`, and `reconcileStale`.
- Consumes existing `Classification`, fingerprint digest, record UUID, and token digest conventions.

- [ ] **Step 1: Write failing migration and repository integration tests**

Add assertions that a fresh and upgraded database contains `mail_click_links`, `mail_click_events`, their foreign keys and unique deduplication constraints. Add a real-PostgreSQL test that inserts one link, deduplicates repeated click observations in the same minute, and returns literal expected aggregate counts.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.migration.FlywayMigrationTest --tests com.camel_hub.advertisement.email.tracking.MailTrackingApiIntegrationTest
```

Expected: failure because the V15 tables, model fields, and repository methods do not exist.

- [ ] **Step 3: Add the V15 schema and minimal repository/model implementation**

Use constrained tables with these observable invariants:

```sql
CREATE TABLE mail_click_links (
    id UUID PRIMARY KEY,
    record_id UUID NOT NULL REFERENCES mail_send_records(id) ON DELETE CASCADE,
    target_url VARCHAR(2048) NOT NULL,
    target_url_hash BYTEA NOT NULL CHECK (octet_length(target_url_hash) = 32),
    label VARCHAR(255),
    position INTEGER NOT NULL CHECK (position >= 1),
    token_hash BYTEA NOT NULL CHECK (octet_length(token_hash) = 32),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (record_id, target_url_hash),
    UNIQUE (token_hash)
);

CREATE TABLE mail_click_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_id UUID NOT NULL REFERENCES mail_click_links(id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    classification VARCHAR(20) NOT NULL,
    reason VARCHAR(80) NOT NULL,
    fingerprint_hash BYTEA NOT NULL CHECK (octet_length(fingerprint_hash) = 32),
    minute_bucket BIGINT NOT NULL,
    UNIQUE (link_id, fingerprint_hash, minute_bucket)
);
```

Extend list/detail SQL with lateral click aggregates; keep open-event fields and JSON names unchanged.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the command from Step 2. Expected: all selected tests pass.

- [ ] **Step 5: Commit the database slice**

```bash
git add backend/src/main/resources/db/migration/V15__mail_click_tracking.sql backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingModels.java backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingRepository.java backend/src/test/java/com/camel_hub/advertisement/migration/FlywayMigrationTest.java backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailTrackingApiIntegrationTest.java
git commit -m "feat: persist test mail click observations"
```

### Task 2: Rewrite eligible HTML links and serve safe signed redirects

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingSigner.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailLinkRewriter.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailClickController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingService.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingConfiguration.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailTrackingSignerTest.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailTrackingApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailOpenPrivacyTest.java`

**Interfaces:**
- Produces: `MailTrackingSigner.issueClick(UUID recordId, UUID linkId, Instant expiresAt)` and `verifyClick(String token, Instant now)`.
- Produces: `MailLinkRewriter.rewrite(String html, UUID recordId, Instant expiresAt)` returning rewritten HTML and immutable link definitions.
- Produces: anonymous `GET|HEAD /t/c/{token}` with valid `302 Location` or generic invalid `404`.
- Consumes Task 1 repository link persistence/resolution and existing classifier.

- [ ] **Step 1: Write focused failing tests**

Name and cover these breaks: an open token accepted as a click token; unsafe/userinfo/relative/mailto links rewritten; caller-selected redirect accepted; HEAD counted; POST redirected; altered/expired/failed-record token redirected; event insertion failure breaking a resolved link; same-minute concurrent clicks counted repeatedly.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.email.tracking.MailTrackingSignerTest --tests com.camel_hub.advertisement.email.tracking.MailTrackingApiIntegrationTest --tests com.camel_hub.advertisement.email.tracking.MailOpenPrivacyTest
```

Expected: failures because click signing, rewriting, persistence, and the controller are absent.

- [ ] **Step 3: Implement domain-separated click signing and deterministic rewriting**

Use a `camel-arxiv:mail-click:v1:` HMAC context and payload `v1c.<record-uuid>.<link-uuid>.<expiry-epoch>.<32-char-nonce>.<43-char-signature>`. Validate canonical UUIDs, seconds, nonce, signature and expiry exactly as open tokens are validated.

Parse only the outbound HTML copy with jsoup. Track unique absolute `http/https` targets without userinfo and at most 2048 characters, preserve duplicate target identity, leave ineligible links unchanged, append the existing pixel, and leave text/subject/saved template content unchanged.

- [ ] **Step 4: Implement safe click resolution and redirect behavior**

Resolve the stored target only after signature and database checks. Build responses with fixed cache/privacy headers. Insert a classified event best-effort after resolution; redirect continuity must not depend on event insertion success. Never accept `url`, `target`, or `redirect` request parameters.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the command from Step 2. Expected: all selected tests pass.

- [ ] **Step 6: Commit the click callback slice**

```bash
git add backend/src/main/java/com/camel_hub/advertisement/email/tracking backend/src/test/java/com/camel_hub/advertisement/email/tracking
git commit -m "feat: add signed test mail click callbacks"
```

### Task 3: Make SMTP finalization cancellation-safe and reconcile stale pending records

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingProperties.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailSendReconciliationJob.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingConfiguration.java`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailTrackingPropertiesTest.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailTrackingApiIntegrationTest.java`

**Interfaces:**
- Produces: property `app.mail-tracking.stale-sending-after`, default `PT15M`, whole seconds from five minutes through one day.
- Produces: `MailSendReconciliationJob.reconcile()` scheduled every minute and on application ready.
- Consumes Task 1 `reconcileStale(Instant cutoff, Instant completedAt)`.

- [ ] **Step 1: Write the cancellation and stale-row failing tests**

Use the real repository and service. Block the mocked external SMTP transport after it starts, cancel the HTTP/service subscription, let transport return success, and assert the row becomes `SMTP_ACCEPTED`. Insert a row older than 15 minutes, invoke the job, and assert literal state `UNKNOWN`, failure `SEND_OUTCOME_MISSING`, and one completion timestamp; assert a recent row remains `SENDING` and a second run changes nothing.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
cd backend
./gradlew test --tests com.camel_hub.advertisement.email.tracking.MailTrackingPropertiesTest --tests com.camel_hub.advertisement.email.tracking.MailTrackingApiIntegrationTest
```

Expected: cancellation leaves `SENDING` and the reconciler/property are absent.

- [ ] **Step 3: Implement minimal durable finalization and reconciliation**

Wrap only the already-subscribed transport/outcome/complete Mono in a Reactor cache so upstream continues after the only HTTP subscriber cancels. Do not subscribe manually and do not detach before SMTP begins. Add the idempotent SQL transition:

```sql
UPDATE mail_send_records
SET status = 'UNKNOWN', failure_category = 'SEND_OUTCOME_MISSING', completed_at = :completed
WHERE status = 'SENDING' AND created_at < :cutoff
```

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the command from Step 2. Expected: all selected tests pass.

- [ ] **Step 5: Commit the send reliability slice**

```bash
git add backend/src/main/java/com/camel_hub/advertisement/email/tracking backend/src/main/resources/application.yaml backend/src/test/java/com/camel_hub/advertisement/email/tracking
git commit -m "fix: finalize interrupted test mail sends"
```

### Task 4: Present configured and observed public callbacks truthfully

**Files:**
- Modify: `frontend/src/modules/email/mail-tracking.types.ts`
- Modify: `frontend/src/modules/email/MailTrackingOption.vue`
- Modify: `frontend/src/modules/email/MailSendRecordsPanel.vue`
- Modify: `frontend/src/modules/email/MailSendRecordDialog.vue`
- Modify: `frontend/src/modules/email/__tests__/mail-tracking.components.spec.ts`
- Modify: `frontend/src/modules/email/__tests__/mail-tracking.send-dialogs.spec.ts`
- Modify: `frontend/src/modules/email/__tests__/mail-tracking.api.spec.ts`
- Modify: `frontend/src/modules/email/__tests__/mail-tracking.deliveries.spec.ts`

**Interfaces:**
- Consumes backend scope `PUBLIC_HTTPS_CONFIGURED`, click aggregate fields, `links`, and `clickEvents`.
- Produces visible configured-only and observed-evidence states, combined opt-in copy, list click counts, and dialog link/click details.

- [ ] **Step 1: Write failing Vue behavior tests**

Use complete API fixtures. Assert a public configured status never says “尚未验证”; a record set with no callbacks says configured-only; a record with a click says “已收到公网回传”; the option mentions both image loads and link clicks; the detail renders a literal target, click count, automated count, and classification without “人工点击” or “已阅读”.

- [ ] **Step 2: Run focused Vitest and verify RED**

```bash
cd frontend
npm test -- --run src/modules/email/__tests__/mail-tracking.components.spec.ts src/modules/email/__tests__/mail-tracking.send-dialogs.spec.ts src/modules/email/__tests__/mail-tracking.api.spec.ts src/modules/email/__tests__/mail-tracking.deliveries.spec.ts
```

Expected: fixture/type and copy/rendering failures for the absent click fields and new scope.

- [ ] **Step 3: Implement the minimal typed UI changes**

Keep the existing layout and design-system primitives. Add compact click evidence to the list and a separate accessible click section in the dialog. Derive “observed” only from nonzero returned image or click counts. Use configured-only copy in the send option where no record evidence exists.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all selected tests pass.

- [ ] **Step 5: Commit the frontend slice**

```bash
git add frontend/src/modules/email
git commit -m "fix: show truthful test mail callback evidence"
```

### Task 5: Update operations documentation and run complete local verification

**Files:**
- Modify: `docs/EMAIL_TRACKING.md`
- Modify: `docs/API.md`
- Modify: `docs/OPERATIONS.md`

**Interfaces:**
- Documents V15 link data, `/t/c/{token}`, stale reconciliation, public configured/observed language, and rollback boundaries.

- [ ] **Step 1: Update operator and API documentation**

Document exact response behavior, non-human evidence semantics, `SEND_OUTCOME_MISSING`, 15-minute default, and the fact that target URLs are stored for diagnostic/test links only. Do not include a live token, credential, or recipient address.

- [ ] **Step 2: Run the complete verification suite**

```bash
cd backend && ./gradlew clean test bootJar
cd ../frontend && npm test -- --run && npm run lint && npm run typecheck && npm run build
cd .. && git diff --check && git status --short
```

Expected: zero test failures, zero lint/type errors, successful backend/frontend production builds, and only intentional tracked changes before the documentation commit.

- [ ] **Step 3: Commit documentation**

```bash
git add docs/EMAIL_TRACKING.md docs/API.md docs/OPERATIONS.md
git commit -m "docs: document reliable mail engagement callbacks"
```

### Task 6: Integrate, push, deploy, and verify production

**Files:**
- Deployment checkout: `/home/stzhang/CaMelArxivAdv/source`
- Runtime compose overlay: `/home/stzhang/CaMelArxivAdv/compose.runtime.yml`
- Existing domain vhost: `/etc/nginx/sites-available/arxiv.nodexi.top.conf`

**Interfaces:**
- Consumes verified `main`, existing Compose secrets/runtime overlay, PostgreSQL volume, and domain-only Nginx route.
- Produces healthy migrated backend/frontend services and a public Edge-verified callback flow.

- [ ] **Step 1: Record and back up the production state**

Capture the current git commit and image IDs. Create a timestamped compressed `pg_dump` under `/home/stzhang/CaMelArxivAdv/backups` with mode `600`; validate it with `gzip -t`. Inspect the domain vhost and do not edit it unless the existing `/t/` privacy or rate-limit location is missing.

- [ ] **Step 2: Integrate and push main**

After the finishing-branch verification gate, merge the named feature branch into `main`, rerun the focused backend/frontend suites on merged `main`, and push `main` to `origin` without force.

- [ ] **Step 3: Deploy only the affected services**

In `/home/stzhang/CaMelArxivAdv/source`, fast-forward pull `main`, build backend and frontend with the existing Compose files, and recreate only `backend-api` and `frontend`. Let Flyway apply V15 during backend startup. Do not recreate PostgreSQL, Kafka, workers, Mailpit, Redis, MinIO, or Nginx.

- [ ] **Step 4: Verify health, migration, and reconciliation**

Wait conditionally for both containers to report healthy. Verify V15 in `flyway_schema_history`, both click tables and constraints, and that the historical record `cfab6c51-bd41-410c-bcac-844a78bf310e` is now `UNKNOWN|SEND_OUTCOME_MISSING` rather than `SENDING`. Confirm open-event counts remain unchanged.

- [ ] **Step 5: Exercise public callbacks without external mail**

Create a synthetic tracked test message through the authenticated production API using the existing internal Mailpit SMTP account, extract its opaque click URL only into a mode-600 temporary file, then test public HTTPS valid GET/HEAD, redirect target, no-count HEAD, same-minute deduplication, classifier variants, altered/expired/missing tokens, POST 405, rate-limit recovery, and log/token privacy. Delete the temporary token file afterward.

- [ ] **Step 6: Validate the production UI in Edge**

The flow under test is: `https://arxiv.nodexi.top/email/deliveries` -> open the synthetic test record -> refresh callback evidence -> observe separate image-load and link-click counts/classifications with no unverified or human-read claim.

Verify URL/title, meaningful DOM, no framework overlay, `tab.dev.logs({ levels: ["error", "warn"], limit: 50 })`, interaction state, desktop screenshot, and one mobile-width screenshot when practical.

- [ ] **Step 7: Final production and repository checks**

Verify all production containers remain healthy, unrelated vhost checksums are unchanged, Nginx config passes, the public site returns 200, the source checkout and local `main` are clean and equal to `origin/main`, and no callback token exists in logs or temporary files.
