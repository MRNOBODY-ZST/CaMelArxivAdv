# Selection, Worker, and Contact Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make paper selection efficient and visually stable while eliminating false Worker timeouts, invalid duplicate-author extraction messages, and contact-list failures caused by legacy ciphertext.

**Architecture:** Keep the UI change page-scoped, calculate an effective job heartbeat in SQL, canonicalize extracted authors at the Worker boundary, and make masked contact disclosure independent of successful plaintext recovery. Existing API contracts and backend validation remain intact.

**Tech Stack:** Vue 3, TypeScript, Tailwind CSS, Vitest, Spring Boot WebFlux, R2DBC PostgreSQL, JUnit/Testcontainers, Python 3.12, Pydantic, pytest, Docker Compose, RabbitMQ.

## Global Constraints

- All bulk-selection copy must say “本页” when it only covers loaded preview rows.
- Full contact disclosure must never fall back to an invented or masked value.
- Backend extraction author uniqueness validation remains enabled.
- SMTP stays local-only; no public deployment or external email delivery is performed.

---

### Task 1: Fixed Checkboxes and Page Import Actions

**Files:**
- Modify: `frontend/src/components/design-skill/DsCheckbox.vue`
- Modify: `frontend/src/modules/arxiv/components/CategoryTree.vue`
- Modify: `frontend/src/modules/arxiv/ArxivDiscoveryView.vue`
- Test: `frontend/src/modules/arxiv/__tests__/phase3.views.spec.ts`

**Interfaces:**
- Consumes: `PreviewResult.papers` and `arxivApi.importSelected(arxivIds: string[])`.
- Produces: `toggleCurrentPage(checked: boolean)` and `importCurrentPage(): Promise<void>` UI behavior.

- [ ] **Step 1: Write failing frontend tests**

Add a two-paper preview case that clicks “全选本页”, verifies both row checkboxes and the selected count, then clicks “一键导入本页 2 篇” and expects `importSelected(['2608.00001', '2608.00002'])`. Mount `CategoryTree` with a wrapping category label and assert its checkbox includes `size-4`, `min-h-4`, `min-w-4`, and `shrink-0`.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `npm test -- --run src/modules/arxiv/__tests__/phase3.views.spec.ts`

Expected: FAIL because the bulk controls and fixed-size classes are absent.

- [ ] **Step 3: Implement the minimal UI behavior**

Add computed current-page IDs and all-selected state, a select-all header checkbox, explicit select/clear controls, and an immediate page import action that submits a snapshot of the current IDs. Add `shrink-0 min-h-4 min-w-4` to the category, result-row, and shared design-system checkboxes.

- [ ] **Step 4: Run frontend quality checks**

Run: `npm test -- --run src/modules/arxiv/__tests__/phase3.views.spec.ts && npm run typecheck && npm run lint`

Expected: all commands exit 0.

### Task 2: Effective Job Heartbeat

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/job/persistence/JobRepository.java`
- Test: `backend/src/test/java/com/camel_hub/advertisement/job/service/JobServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `jobs.heartbeat_at` and `worker_heartbeats.last_seen_at` for matching `current_job_id`.
- Produces: `JobRecord.heartbeatAt()` equal to the newest available timestamp.

- [ ] **Step 1: Write a failing repository/service integration test**

Insert a running job with a two-hour-old `jobs.heartbeat_at`, insert a matching Worker heartbeat at `now()`, call `service.get(jobId)`, and assert `workerStale()` is false and the returned heartbeat is newer than the job heartbeat.

- [ ] **Step 2: Run the focused backend test and verify failure**

Run: `./gradlew test --tests com.camel_hub.advertisement.job.service.JobServiceIntegrationTest`

Expected: FAIL because `coalesce(j.heartbeat_at, wh.last_seen_at)` chooses the stale non-null job timestamp.

- [ ] **Step 3: Select the newest heartbeat in SQL**

Replace the heartbeat projection with `greatest(j.heartbeat_at, wh.last_seen_at) AS heartbeat_at`; retain the lateral lookup and existing stale threshold.

- [ ] **Step 4: Re-run the focused backend test**

Run: `./gradlew test --tests com.camel_hub.advertisement.job.service.JobServiceIntegrationTest`

Expected: PASS.

### Task 3: Worker Author Canonicalization

**Files:**
- Modify: `worker/src/app/extraction/contact_extractor.py`
- Test: `worker/tests/extraction/test_contact_extractor.py`

**Interfaces:**
- Consumes: repeated `ExtractedAuthor` entries and `_Candidate.author_order` values collected across TeX files.
- Produces: `_canonicalize_authors(...) -> tuple[list[ExtractedAuthor], dict[int, int]]` and remapped candidates with contiguous canonical orders.

- [ ] **Step 1: Write failing extraction tests**

Create `main.tex` and an included author file that repeat the same names with whitespace/case variation and complementary affiliations. Assert unique first-seen authors, merged affiliations, contiguous orders, and contacts mapped to the canonical orders.

- [ ] **Step 2: Run the focused Worker test and verify failure**

Run: `uv run pytest tests/extraction/test_contact_extractor.py -q`

Expected: FAIL because duplicate normalized author names remain in the document.

- [ ] **Step 3: Canonicalize authors and remap candidates**

Normalize names with Unicode NFKC, collapse whitespace, and case-fold. Merge affiliations without duplicates, OR the corresponding flag, build old-to-new order mappings, and use `dataclasses.replace` to remap candidate author orders before `_mapping` runs.

- [ ] **Step 4: Run Worker checks**

Run: `uv run pytest tests/extraction/test_contact_extractor.py -q && uv run ruff check src tests && uv run mypy`

Expected: all commands exit 0.

### Task 4: Resilient Masked Contact Views

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/contact/ContactService.java`
- Test: `backend/src/test/java/com/camel_hub/advertisement/contact/ContactServiceTest.java`

**Interfaces:**
- Consumes: `ContactRow.displayCiphertext`, `displayNonce`, `domain`, and the caller’s permissions.
- Produces: conservative `***@domain` only for masked disclosure when ciphertext is unavailable or fails authentication; full disclosure still throws.

- [ ] **Step 1: Write failing contact-service tests**

Use a row encrypted under a different key. Assert `list(...)` and `get(..., false, ...)` return `***@university.edu`; assert `get(..., true, ...)` throws and never audits a successful disclosure.

- [ ] **Step 2: Run the focused contact test and verify failure**

Run: `./gradlew test --tests com.camel_hub.advertisement.contact.ContactServiceTest`

Expected: FAIL with encrypted contact authentication failure.

- [ ] **Step 3: Add masked-only fallback**

Separate masked and full disclosure paths. Catch invalid/missing encrypted values only in the masked path and construct the domain-only mask after validating the stored domain is nonblank. Leave the full path’s decrypt-and-audit behavior unchanged.

- [ ] **Step 4: Run the focused contact test**

Run: `./gradlew test --tests com.camel_hub.advertisement.contact.ContactServiceTest`

Expected: PASS.

### Task 4A: Source-to-Metadata Author Identity Reconciliation

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/arxiv/extraction/SourceExtractionResultRepository.java`
- Test: `backend/src/test/java/com/camel_hub/advertisement/messaging/SourceExtractionResultHandlerIntegrationTest.java`

**Interfaces:**
- Consumes: normalized Source author names, Source author order, and existing `paper_authors` rows created from arXiv metadata.
- Produces: `AuthorLink` values keyed by Source order while reusing an existing paper-author identity by normalized name before positional fallback.

- [ ] **Step 1: Write a failing integration test for reordered Source authors**

Seed one metadata author at order 1, return two distinct Source author names where the metadata author appears at Source order 2, and map a contact to Source order 2. Assert persistence reuses the existing paper-author row, succeeds without a unique-key violation, and links the contact to that row.

- [ ] **Step 2: Run the focused integration test and verify failure**

Run: `./gradlew test --tests com.camel_hub.advertisement.messaging.SourceExtractionResultHandlerIntegrationTest`

Expected: FAIL on `uk_paper_authors_author`.

- [ ] **Step 3: Prefer normalized-name reconciliation**

Query an existing paper-author by `authors.normalized_name`, update its corresponding/affiliation fields, and return an `AuthorLink` using the Source order. Only when no name match exists should persistence reuse the current positional behavior or create an author.

- [ ] **Step 4: Re-run the focused integration test**

Run: `./gradlew test --tests com.camel_hub.advertisement.messaging.SourceExtractionResultHandlerIntegrationTest`

Expected: PASS.

### Task 5: Local Deployment and End-to-End Verification

**Files:**
- Modify only if validation exposes a regression: the owning source/test file from Tasks 1–4.

**Interfaces:**
- Consumes: local Compose services, authenticated API, RabbitMQ, and Edge.
- Produces: verified local application at `http://127.0.0.1:8080` with no external SMTP delivery.

- [ ] **Step 1: Run all automated suites and builds**

Run backend `./gradlew test`, Worker `uv run pytest -q`, frontend `npm test -- --run`, and frontend `npm run build` from their respective directories.

- [ ] **Step 2: Rebuild affected containers**

Run: `docker compose up -d --build backend-api mail-worker arxiv-worker frontend`

Expected: all Compose services report healthy.

- [ ] **Step 3: Verify authenticated APIs**

Log in as local admin, assert `/api/v1/contacts?page=1&pageSize=20` returns HTTP 200 with database-backed items, create a bounded source-extraction job for imported papers, and poll until terminal without `workerStale=true` while Worker heartbeats continue.

- [ ] **Step 4: Verify queues and persisted extraction**

Assert the new job has no pending item, no new extraction result is dead-lettered, duplicate normalized author names are absent, and contacts/evidence are persisted. Retain historical dead-letter messages unless exact cleanup is required for a clean retry.

- [ ] **Step 5: Verify Edge UI**

At `http://127.0.0.1:8080`, confirm wrapped category checkboxes have equal rendered dimensions, exercise “全选本页” and “一键导入本页”, inspect job progress without a false timeout, and confirm `/contacts` renders masked contacts. Do not submit an external email.

- [ ] **Step 6: Review and commit**

Review the diff for secrets and unrelated edits, re-run focused regression checks, then commit the completed fix on `codex/arxiv-platform`.
