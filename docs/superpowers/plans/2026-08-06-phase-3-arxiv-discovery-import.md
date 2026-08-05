# Phase 3 arXiv Discovery and Import Implementation Plan

> **Execution mode:** Apply the repository's test-driven workflow task by task. Every behavior change starts with a failing focused test, then the smallest implementation, then the relevant regression suite. Commit at the checkpoints below.

**Goal:** Deliver a production-shaped vertical slice in which an authorized user can browse the official arXiv taxonomy, preview a normalized category query, save it, create an idempotent asynchronous metadata import, observe and control the job through SSE or polling, and browse the imported papers.

**Architecture:** Keep interactive preview in the Spring WebFlux API so it can return immediately, but put bulk Legacy/OAI harvesting in the isolated Python arXiv worker. Both clients use the same Redis-backed global lease key, enforcing one official request across all instances no sooner than three seconds after the previous lease. Spring owns PostgreSQL state, job transitions, authorization, audit, SSE, RabbitMQ publishing, and result persistence. The worker validates versioned commands, manually ACKs only after publishing a durable result, and treats pause/cancel as cooperative checkpoints between official requests.

**Current official protocol facts:** The Legacy API returns Atom and asks clients making repeated calls to wait three seconds and cache queries. Bulk harvesting should use OAI-PMH. Since March 2025 the OAI base URL is `https://oaipmh.arxiv.org/oai`; the set hierarchy is `group:archive:CATEGORY`; resumption tokens expire daily and no longer contain a cursor or total. Sources: [API manual](https://info.arxiv.org/help/api/user-manual.html), [OAI harvester notes](https://info.arxiv.org/help/oa/index.html), and [category taxonomy](https://arxiv.org/category_taxonomy).

**Technology:** Java 25, Spring Boot 4.1 WebFlux/R2DBC/Security/Redis/RabbitMQ, PostgreSQL/Flyway, Python 3.12/httpx/aio-pika/redis/Pydantic, Vue 3/TypeScript/Pinia/Axios/Tailwind Plus-derived design primitives, JUnit/Testcontainers, pytest, Vitest, and browser QA.

---

## Task 1: Extend the Phase 3 persistence contract

**Files:**

- Create: `backend/src/main/resources/db/migration/V6__arxiv_discovery_and_job_runtime.sql`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/migration/FlywayMigrationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/persistence/ArxivSchemaMigrationTest.java`

**Behavior:**

- Add a versioned `arxiv_category_snapshots` table and source metadata without deleting historical categories.
- Add saved-search query hash and normalized criteria indexes.
- Add job command/outbox delivery state, OAI cursor fields, optimistic version, transition constraints, and indexes needed for owner/status pagination and event replay.
- Add paper search indexes and an import-source/job association while keeping `arxiv_id` the upsert key.
- Preserve every Phase 1/2 constraint and migration-from-empty behavior.

**TDD:** Assert the new tables, columns, indexes, and constraints against a fresh Testcontainers PostgreSQL instance before writing V6.

**Checkpoint:** `feat: add phase three persistence runtime`

## Task 2: Ship a versioned offline taxonomy snapshot

**Files:**

- Create: `backend/src/main/resources/arxiv/taxonomy-2026-08.json`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/config/ArxivProperties.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/config/ArxivConfiguration.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/taxonomy/TaxonomySnapshotLoader.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/taxonomy/TaxonomySnapshotLoaderTest.java`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `.env.example`
- Modify: `docker-compose.yml`

**Behavior:**

- Package a normalized snapshot generated once from the official taxonomy/ListSets data, including group, archive, category name, description when available, alias marker, target, source URL, snapshot version, and timestamp.
- Reject duplicate category IDs, malformed aliases, unknown alias targets, and incomplete hierarchy data at startup/test time.
- Configuration pins official hosts, Legacy/OAI endpoints, cache TTL, request timeout, retry limits, page limits, and a minimum request interval that cannot be below three seconds.

**TDD:** Loader tests prove the packaged snapshot is complete, unique, internally referential, and usable without network access.

## Task 3: Persist and expose the taxonomy tree

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/taxonomy/TaxonomyRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/taxonomy/TaxonomyService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/ArxivTaxonomyController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/ArxivTaxonomyDtos.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/taxonomy/TaxonomyRepositoryTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/api/ArxivTaxonomyApiTest.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/identity/security/Permission.java`
- Modify: `frontend/src/modules/auth/auth.permissions.ts`

**Behavior:**

- Bootstrap from the packaged snapshot only when no active snapshot exists.
- `GET /api/v1/arxiv/taxonomy` requires `paper:read`, returns stable group/archive/category ordering, aliases, snapshot version/source, and last sync state.
- `POST /api/v1/arxiv/taxonomy/sync` requires `system:manage`, creates an idempotent taxonomy job, and writes an audit record.
- A later snapshot marks disappeared records inactive but never deletes them or breaks paper relations.

**TDD:** Cover offline bootstrap, stable tree shape, permissions, duplicate sync idempotency, and historical-category preservation.

**Checkpoint:** `feat: expose versioned arxiv taxonomy`

## Task 4: Normalize and validate discovery criteria

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/search/ArxivSearchCriteria.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/search/ArxivQueryNormalizer.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/search/ArxivLegacyQueryBuilder.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/search/ArxivQueryNormalizerTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/search/ArxivLegacyQueryBuilderTest.java`

**Behavior:**

- Support category IDs, primary/cross-list mode, submitted/updated range, title/abstract/author terms, DOI/journal-reference presence, source availability, sort, page, and bounded page size.
- Trim and normalize Unicode/whitespace, deduplicate/sort categories, canonicalize booleans/dates/sort, and hash canonical JSON for cache/idempotency keys.
- Build only fielded Legacy API expressions; reject raw query fragments, control characters, unknown/inactive categories, inverted ranges, oversized input, and unsupported sort values.
- Label source-related filters as platform-derived and do not pretend they are official Legacy fields.

**TDD:** Include injection-like input, canonical equivalence, locale-independent ordering, date boundaries, and maximum-size cases.

## Task 5: Enforce the Redis global arXiv request lease

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/client/GlobalArxivRateLease.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/client/RedisGlobalArxivRateLease.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/client/RedisGlobalArxivRateLeaseTest.java`
- Create: `worker/src/app/arxiv/rate_limit.py`
- Create: `worker/tests/arxiv/test_rate_limit.py`
- Modify: `worker/pyproject.toml`
- Modify: `worker/src/app/config.py`

**Behavior:**

- One atomic Redis script reserves the next slot using Redis server time and a shared key; Java and Python implement the same key/protocol.
- Concurrent callers receive monotonically spaced reservations of at least the configured interval; configuration below three seconds is rejected.
- Cancellation while waiting does not issue an external request. Redis failure fails closed with a dependency error rather than allowing unthrottled traffic.

**TDD:** Test concurrent reservations against Redis, cross-language protocol constants, minimum interval validation, and failure behavior.

## Task 6: Implement cached Legacy API preview

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/client/ArxivLegacyClient.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/client/AtomFeedParser.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/search/ArxivPreviewService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/ArxivSearchController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/ArxivSearchDtos.java`
- Create: `backend/src/test/resources/arxiv/legacy-preview.xml`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/client/AtomFeedParserTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/search/ArxivPreviewServiceTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/api/ArxivSearchApiTest.java`

**Behavior:**

- `POST /api/v1/arxiv/search/preview` requires `paper:read`, returns normalized criteria, total, page metadata, official-vs-derived filter annotations, cache status, and paper preview rows.
- Parse Atom namespaces, all authors/categories, primary category, DOI, journal reference, comment, license, PDF URL, submission/update times, and arXiv versions without expanding entities or allowing DTDs.
- Cache identical canonical queries in Redis and coalesce concurrent misses. Apply the global lease only on cache miss.
- Retry 429/5xx/timeouts with bounded exponential backoff and jitter; never retry other 4xx. Map upstream exhaustion to sanitized 503.

**TDD:** Use a local stub server and fixtures; prove cache hits make no second request, concurrent misses coalesce, leases are invoked, XML attacks are rejected, retries are bounded, and errors expose no upstream body.

**Checkpoint:** `feat: add throttled arxiv query preview`

## Task 7: Save normalized searches

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/search/SavedSearchRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/search/SavedSearchService.java`
- Extend: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/ArxivSearchController.java`
- Extend: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/ArxivSearchDtos.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/api/SavedSearchApiTest.java`

**Behavior:**

- Add list/create/update/delete endpoints under `/api/v1/arxiv/saved-searches`.
- Searches are owner-scoped, store canonical criteria plus hash, use bounded unique names, and cannot be read or mutated by another user.
- Creating or updating records an audit entry; API returns the saved canonical query, never an unvalidated raw expression.

**TDD:** Cover ownership, uniqueness, canonical storage, pagination, permission, validation, and audit.

## Task 8: Implement the job state machine and persistence APIs

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/job/domain/JobStatus.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/job/domain/JobAction.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/job/domain/JobStateMachine.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/job/persistence/JobRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/job/service/JobService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/job/api/JobController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/job/api/JobDtos.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/job/domain/JobStateMachineTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/job/service/JobServiceIntegrationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/job/api/JobApiTest.java`

**Behavior:**

- Centralize allowed transitions among all eight required statuses. Pause/resume/cancel/retry are idempotent and use optimistic concurrency.
- `GET /api/v1/jobs` and `GET /api/v1/jobs/{id}` provide bounded pagination, counts, stage, progress, timestamps, worker heartbeat freshness, and allowed actions.
- Control endpoints require `job:manage`; readers with `paper:read` see arXiv jobs but not sensitive parameters.
- Retry creates a new execution lineage/idempotency key while retaining the original history; terminal jobs cannot be paused/resumed/canceled.
- Every transition appends a `job_events` record and auditable user action in the same transaction.

**TDD:** Exhaustively table-test valid/invalid transitions and race two commands against the same version.

## Task 9: Add job SSE with replay and polling fallback

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/job/service/JobEventStream.java`
- Extend: `backend/src/main/java/com/camel_hub/advertisement/job/api/JobController.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/job/api/JobEventStreamApiTest.java`

**Behavior:**

- `GET /api/v1/jobs/{id}/events` paginates persisted history.
- `GET /api/v1/jobs/{id}/stream` emits `text/event-stream` with database event ID as SSE ID, honors `Last-Event-ID`, replays missed events, sends heartbeat comments, and completes after the terminal event.
- Streams are bounded, cancelable, permission checked, and do not leak one job's events to another subscriber.
- The ordinary job detail endpoint remains the explicit polling fallback.

**TDD:** Verify replay boundaries, ordering, heartbeat, disconnect cancellation, terminal completion, auth, and polling equivalence.

## Task 10: Create idempotent import and OAI sync commands

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/importing/ArxivImportService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/ArxivImportController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/ArxivImportDtos.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/ArxivMessagingConfiguration.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/ArxivCommandPublisher.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/api/ArxivImportApiTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/messaging/ArxivCommandPublisherTest.java`

**Behavior:**

- `POST /api/v1/arxiv/imports` accepts either explicitly selected arXiv IDs or canonical criteria plus a bounded import ceiling; `POST /api/v1/arxiv/oai/sync` accepts an official set and optional incremental `from` date.
- Both require `paper:import`, create job/outbox/event atomically, derive a stable idempotency key from actor+command+canonical payload, and return the existing job for duplicate submissions.
- Publisher declares durable `arxiv.jobs`, `arxiv.results`, retry, and dead-letter topology and sends versioned envelopes with message ID/job ID/idempotency key/trace ID but no email or secret.
- A dispatcher only marks an outbox row published after broker confirmation.

**TDD:** Cover selected/all imports, maximum limits, official set validation, duplicate submissions, outbox atomicity, message contract, and RabbitMQ confirms.

**Checkpoint:** `feat: add controllable arxiv import jobs`

## Task 11: Implement Python Legacy and OAI clients

**Files:**

- Create: `worker/src/app/arxiv/models.py`
- Create: `worker/src/app/arxiv/api_client.py`
- Create: `worker/src/app/arxiv/oai_client.py`
- Create: `worker/src/app/arxiv/taxonomy.py`
- Create: `worker/tests/fixtures/arxiv/legacy-page.xml`
- Create: `worker/tests/fixtures/arxiv/oai-page-1.xml`
- Create: `worker/tests/fixtures/arxiv/oai-page-2.xml`
- Create: `worker/tests/arxiv/test_api_client.py`
- Create: `worker/tests/arxiv/test_oai_client.py`
- Create: `worker/tests/arxiv/test_taxonomy.py`

**Behavior:**

- Use only configured HTTPS official hosts, bounded timeouts/response sizes/redirects, shared Redis lease, user-agent contact configuration, and retry policy.
- Parse Legacy Atom and OAI `arXiv` metadata with hardened XML parsing; preserve latest metadata, categories, authors, DOI, journal ref, comment, license and OAI datestamp.
- Follow opaque resumption tokens exactly, never combine a token with other OAI arguments, persist each returned token/date through progress results, and handle token expiry as a retryable cursor restart.
- ListSets normalization produces the same group/archive/category model as the offline snapshot and preserves aliases.

**TDD:** All network tests use `httpx.MockTransport`; prove official-host enforcement, response limits, retry behavior, multi-page token handling, deletions, malformed XML, and token expiry classification.

## Task 12: Consume commands and publish versioned progress/results

**Files:**

- Create: `worker/src/app/jobs/arxiv_consumer.py`
- Create: `worker/src/app/jobs/job_control.py`
- Extend: `worker/src/app/messaging/contracts.py`
- Modify: `worker/src/app/main.py`
- Create: `worker/tests/jobs/test_arxiv_consumer.py`
- Create: `worker/tests/test_main.py`

**Behavior:**

- Declare/bind durable queues with manual ACK, QoS=1, retry routing, and dead lettering.
- Validate strict v1 commands and idempotency. Publish started/progress/checkpoint/item/result/failed messages containing a bounded batch of metadata and no secret.
- Check cooperative pause/cancel state between pages and on backoff. Paused jobs retain their cursor; canceled jobs stop before the next external call.
- ACK only after durable result publication succeeds; malformed/permanently failed messages go to dead letter, retryable failures use bounded exponential delay.
- Continue publishing worker heartbeats with current job/status.

**TDD:** Use fake channel/client/control stores to verify ACK ordering, duplicate handling, retry/dead-letter decisions, pause/cancel checkpoints, and payload bounds.

## Task 13: Persist worker results and paper metadata

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/paper/PaperRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/ArxivResultConsumer.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/ArxivResultHandler.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/paper/PaperRepositoryTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/messaging/ArxivResultHandlerIntegrationTest.java`

**Behavior:**

- Strictly validate version/result type/job identity. Insert `processed_messages` first in a transaction and ACK duplicates without repeating side effects.
- Upsert papers by arXiv ID, versions, ordered authors, primary/cross-list/alias relations, and raw metadata. Update records only when upstream metadata is not older.
- Apply progress/checkpoint atomically with events and counts; enforce monotonic counts/progress and legal job transitions.
- Persist item-level failures without aborting unrelated papers; compute succeeded/partially-succeeded/failed terminal state from counts.
- Update worker heartbeat records and identify stale workers without rewriting a terminal job.

**TDD:** Cover duplicate/out-of-order messages, replay, older metadata, author/category replacement, partial failure, stale heartbeat, and rollback on invalid payload.

**Checkpoint:** `feat: complete arxiv worker metadata pipeline`

## Task 14: Add paper list/detail APIs

**Files:**

- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/paper/PaperQueryService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/PaperController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/arxiv/api/PaperDtos.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/arxiv/api/PaperApiTest.java`

**Behavior:**

- `GET /api/v1/papers` requires `paper:read`, uses database-side pagination/filtering/sorting, and supports the Phase 3 subset of category, dates, title, author, source status, DOI, and journal reference.
- `GET /api/v1/papers/{id}` returns base metadata, ordered authors, categories, versions, import provenance, and sanitized raw metadata; no contact/source content is exposed in Phase 3.
- Bound page sizes and searchable text; use parameterized SQL and stable secondary ordering.

**TDD:** Verify filtering semantics, pagination, stable ordering, empty state, permission, 404, and query-plan index eligibility.

## Task 15: Build the Vue discovery workflow

**Files:**

- Create: `frontend/src/modules/arxiv/arxiv.types.ts`
- Create: `frontend/src/modules/arxiv/arxiv.api.ts`
- Create: `frontend/src/modules/arxiv/ArxivDiscoveryView.vue`
- Create: `frontend/src/modules/arxiv/components/CategoryTree.vue`
- Create: `frontend/src/modules/arxiv/components/SearchCriteriaSummary.vue`
- Create: `frontend/src/modules/arxiv/__tests__/ArxivDiscoveryView.spec.ts`
- Create: `frontend/src/modules/arxiv/__tests__/CategoryTree.spec.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/AppShell.vue`

**Behavior:**

- Replace the discovery hash link with `/arxiv/discovery` and a `paper:read` route.
- Render backend taxonomy as an accessible expandable multi-select tree, responsive filter form, criteria summary, official/platform-derived labels, preview table, empty/loading/error states, save-search flow, selected import, and confirm-all import ceiling.
- Keep all filtering server-side. Buttons require the applicable permission and explain why unavailable.

**TDD:** Cover taxonomy selection, canonical request payload, preview, error/empty/loading, save, selected/all import, permission-gated controls, keyboard labels, and narrow layout.

## Task 16: Build import jobs and paper library UI

**Files:**

- Create: `frontend/src/modules/jobs/jobs.types.ts`
- Create: `frontend/src/modules/jobs/jobs.api.ts`
- Create: `frontend/src/modules/jobs/ImportJobsView.vue`
- Create: `frontend/src/modules/jobs/JobDetailView.vue`
- Create: `frontend/src/modules/jobs/useJobProgress.ts`
- Create: `frontend/src/modules/jobs/__tests__/ImportJobsView.spec.ts`
- Create: `frontend/src/modules/jobs/__tests__/useJobProgress.spec.ts`
- Create: `frontend/src/modules/papers/papers.api.ts`
- Create: `frontend/src/modules/papers/PapersView.vue`
- Create: `frontend/src/modules/papers/PaperDetailView.vue`
- Create: `frontend/src/modules/papers/__tests__/PapersView.spec.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/AppShell.vue`

**Behavior:**

- Replace job/paper hash links with real permission-guarded routes.
- Jobs list/detail show status, progress/counts, stage timeline, heartbeat freshness, errors, and only legal/authorized actions.
- `useJobProgress` connects SSE with access token, replays via last event ID where supported, and falls back to bounded polling on unsupported/disconnected streams without duplicate updates.
- Paper library provides server-side filters/pagination, responsive rows/cards, and detail tabs for metadata/authors/categories/versions/raw metadata.

**TDD:** Cover action visibility/state transitions, SSE-to-poll fallback, cleanup, deduplication, pagination, paper empty/error/loading, and responsive semantics.

**Checkpoint:** `feat: add arxiv discovery and import workspace`

## Task 17: Phase 3 documentation, runtime, and acceptance

**Files:**

- Modify: `docs/API.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/OPERATIONS.md`
- Modify: `docs/DEPLOYMENT.md`
- Modify: `docs/ERD.md`
- Modify: `README.md`
- Modify: `IMPLEMENTATION_PLAN.md`
- Modify: `TASKS.md`
- Modify: `scripts/verify-compose.sh`
- Modify: `scripts/verify-container-images.sh`

**Verification sequence:**

1. Backend focused tests after each task, then `cd backend && ./gradlew --no-daemon clean check bootJar`.
2. Worker `cd worker && .venv/bin/pytest`, `.venv/bin/ruff check .`, and `.venv/bin/mypy`.
3. Frontend `npm test -- --run`, `npm run lint`, `npm run typecheck`, and `npm run build`.
4. Build and recreate affected Compose services; run both repository verification scripts and confirm every service healthy.
5. Against the proxied runtime, verify login, taxonomy offline availability, cache behavior, idempotent duplicate import, job controls, SSE replay/poll response, worker metadata persistence, and paper pagination. Never send email.
6. Browser-test desktop and 390×844 discovery/jobs/papers flows, keyboard focus, horizontal overflow, and console errors.
7. Review the Phase 3 diff for authorization, audit, SSRF/XML, rate-limit, idempotency, state-machine, transaction, and secret-leak regressions.

**Acceptance:** An authorized data user can choose an official category, preview results, save the query, create one idempotent import job, observe/control it through SSE or polling, and browse imported papers. Multiple Java/Python instances still produce no official arXiv request interval below three seconds. Offline taxonomy remains available when arXiv is unavailable.

**Final checkpoint:** update Phase 3 to complete only after all observed evidence passes, then commit `feat: complete phase three arxiv discovery and import`.
