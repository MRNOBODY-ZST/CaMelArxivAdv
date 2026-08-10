# Personalized Email Generation and Navigation Implementation Plan

> Execute each task test-first. Keep Spring Boot as the only public API, keep model credentials worker-only, and do not send campaign email during verification.

**Goal:** Replace the broken sidebar hash links with functional pages and add a safe paper-aware per-author draft generation workflow backed by RabbitMQ and Ray Core.

**Architecture:** Vue calls Spring Boot for segments, campaigns, reporting, and runtime status. Spring Boot materializes suppression-safe campaign recipients and publishes a versioned command. A Python consumer fans recipient work into Ray tasks that call the OpenAI Responses API with strict Structured Outputs, then publishes per-recipient results. Spring validates and sanitizes results before storing drafts.

**Stack:** Vue 3, TypeScript, Vue Router, Vitest, Spring Boot WebFlux, R2DBC PostgreSQL, RabbitMQ, Python 3.12, Pydantic, httpx, Ray Core, Docker Compose.

---

## Task 1: Reproduce and Repair Every Sidebar Route

**Files:**

- Modify: `frontend/src/layouts/__tests__/AppShell.spec.ts`
- Modify: `frontend/src/modules/auth/__tests__/router.permissions.spec.ts`
- Modify: `frontend/src/layouts/AppShell.vue`
- Modify: `frontend/src/router/index.ts`
- Create: `frontend/src/modules/campaigns/SegmentsView.vue`
- Create: `frontend/src/modules/campaigns/CampaignsView.vue`
- Create: `frontend/src/modules/campaigns/CampaignDetailView.vue`
- Create: `frontend/src/modules/campaigns/DeliveriesView.vue`
- Create: `frontend/src/modules/campaigns/CampaignAnalyticsView.vue`
- Create: `frontend/src/modules/campaigns/LinkAnalyticsView.vue`
- Create: `frontend/src/modules/admin/SystemSettingsView.vue`

1. Add a regression test that mounts the application shell as an administrator and asserts every sidebar link is an absolute path, contains no hash, and resolves to a registered authenticated route with the expected permission.
2. Run `npm run test -- --run src/layouts/__tests__/AppShell.spec.ts src/modules/auth/__tests__/router.permissions.spec.ts` and confirm the existing hash links fail the new assertion.
3. Add lazy route records and initial accessible empty-state views, then replace all hash targets with absolute paths.
4. Re-run the focused tests and `npm run typecheck`.
5. Commit with `fix: repair email and analytics navigation`.

## Task 2: Add Forward-Only Campaign Personalization State

**Files:**

- Create: `backend/src/main/resources/db/migration/V12__campaign_personalization.sql`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/schema/SchemaMigrationTest.java`

1. Add schema assertions for campaign generation state/provider/model/job/timestamps and recipient personalization state/rationale/error/attempt/timestamp plus their constraints and indexes.
2. Run the focused schema test and confirm it fails before the migration exists.
3. Add the forward-only migration without changing historical migrations.
4. Re-run the schema test and migration integration suite.
5. Commit with `feat: add campaign personalization state`.

## Task 3: Implement Constrained Segments Test-First

**Files:**

- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/SegmentRuleTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/SegmentServiceTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/SegmentControllerTest.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/SegmentModels.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/SegmentDtos.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/SegmentRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/SegmentService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/SegmentController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignConfiguration.java`

1. Write validation tests that accept only allowlisted fields/operators/values and reject raw SQL-like or unknown expressions.
2. Write service/repository tests for pagination, preview counts, masked samples, and mandatory exclusion of example domains, inactive contacts, suppressions, and unsubscribes.
3. Write controller authorization and validation tests for list/get/create/preview.
4. Run the focused tests and confirm missing production types fail compilation.
5. Implement the smallest models, query builder, repository, service, configuration, and controller that pass. Parameterize every rule value; never interpolate user values into SQL.
6. Re-run the focused tests and backend static checks.
7. Commit with `feat: add safe recipient segments`.

## Task 4: Implement Draft Campaigns and Recipient Materialization

**Files:**

- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/CampaignServiceTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/CampaignControllerTest.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignModels.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignDtos.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignConfiguration.java`

1. Write service tests for draft creation, immutable template version/sender snapshots, mandatory unsubscribe, draft-only generation, configured-provider gate, maximum recipient count, suppression-safe materialization, and idempotent retries.
2. Write controller tests for list/get/create/recipients/start-personalization and permissions.
3. Run tests to establish RED.
4. Implement repository and service transaction boundaries, using existing encrypted contact fields without decrypting them for personalization messages.
5. Return truthful per-state generation counts and safe per-recipient draft/error fields.
6. Re-run tests and commit with `feat: add personalized draft campaigns`.

## Task 5: Publish Privacy-Safe Personalization Commands

**Files:**

- Create: `backend/src/test/java/com/camel_hub/advertisement/messaging/PersonalizationCommandPublisherTest.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationCommandMessage.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationCommandPublisher.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationMessagingConfiguration.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignConfiguration.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignService.java`

1. Write a contract test that serializes a command and proves it includes public paper context while no field or value contains recipient email, SMTP password, ciphertext, nonce, or HMAC.
2. Write publisher tests for durable exchange/routing configuration, mandatory publishing, correlation headers, size limit, and publisher failure behavior.
3. Run focused tests to establish RED.
4. Implement the versioned record and publisher and integrate it after transactional recipient materialization through the existing outbox pattern.
5. Re-run tests and commit with `feat: queue personalized email jobs`.

## Task 6: Validate and Persist Personalization Results

**Files:**

- Create: `backend/src/test/java/com/camel_hub/advertisement/messaging/PersonalizationResultHandlerTest.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationResultMessage.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationResultHandler.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationResultConsumer.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationMessagingConfiguration.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignRepository.java`

1. Write handler tests for identifiers, duplicate results, stale jobs, unsafe HTML removal, header injection, content limits, missing unsubscribe token, provider failure, and aggregate campaign completion/partial failure.
2. Run focused tests to establish RED.
3. Implement strict message validation, reuse `TemplateEngine.prepare`, require a valid result and `unsubscribe_url`, and update recipients idempotently.
4. Configure a durable result queue and dead-letter behavior. Acknowledge only after database persistence.
5. Re-run tests and commit with `feat: persist generated email drafts`.

## Task 7: Implement the OpenAI Client and Strict Output Contract

**Files:**

- Modify: `worker/pyproject.toml`
- Modify: `worker/uv.lock`
- Create: `worker/tests/personalization/test_contracts.py`
- Create: `worker/tests/personalization/test_openai_client.py`
- Create: `worker/src/app/personalization/__init__.py`
- Create: `worker/src/app/personalization/contracts.py`
- Create: `worker/src/app/personalization/openai_client.py`
- Create: `worker/src/app/personalization/prompt.py`
- Modify: `worker/src/app/config.py`

1. Write Pydantic tests for strict command/result schemas, limits, UUID identity, required unsubscribe variable, and explicit absence of email fields.
2. Write httpx transport tests for Responses API request shape, strict JSON schema, parsed output, authentication failure, rate limit and 5xx retry classification, timeout classification, malformed output, and key redaction.
3. Run the focused tests to establish RED.
4. Add Ray 2.x and implement settings, prompt construction, and the async Responses API client. No fake or fallback generator is included in production.
5. Run focused tests, Ruff, and mypy.
6. Commit with `feat: add structured email generation client`.

## Task 8: Fan Out Personalization Through Ray

**Files:**

- Create: `worker/tests/personalization/test_ray_executor.py`
- Create: `worker/tests/personalization/test_consumer.py`
- Create: `worker/src/app/personalization/ray_executor.py`
- Create: `worker/src/app/personalization/consumer.py`
- Create: `worker/src/app/personalization/main.py`
- Modify: `worker/src/app/messaging/rabbit.py`
- Modify: `worker/pyproject.toml`

1. Write local-mode executor tests for per-recipient fan-out, bounded concurrency, preserved identifiers, transient retries, partial failure, and shutdown.
2. Write Rabbit consumer tests for message size/content type, invalid command dead-lettering, successful per-recipient result publishing, and provider-disabled results.
3. Run focused tests to establish RED.
4. Implement Ray remote tasks with `max_retries=2`, bounded concurrency, and result publication. Keep queue callbacks non-blocking.
5. Add the personalization worker entry point and graceful shutdown.
6. Run worker tests, Ruff, and mypy; commit with `feat: distribute email generation with ray`.

## Task 9: Add Real Reporting and Safe Runtime Status APIs

**Files:**

- Create: `backend/src/test/java/com/camel_hub/advertisement/campaign/CampaignReportingTest.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignReportingRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignReportingService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignReportingController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/system/RuntimeStatusProperties.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/system/RuntimeStatusController.java`
- Modify: `backend/src/main/resources/application.yml`

1. Write query tests for delivery attempts, campaign totals/rates, and link totals with bot/prefetch classification kept separate from likely-human events.
2. Write API tests that verify true empty results and no credential-shaped field in runtime status.
3. Run tests to establish RED.
4. Implement paginated/read-only queries and safe configuration booleans/names.
5. Re-run tests and commit with `feat: add campaign reporting and runtime status`.

## Task 10: Build the Functional Campaign Frontend

**Files:**

- Create: `frontend/src/modules/campaigns/__tests__/campaign.views.spec.ts`
- Create: `frontend/src/modules/campaigns/campaigns.api.ts`
- Create: `frontend/src/modules/campaigns/campaigns.types.ts`
- Modify: `frontend/src/modules/campaigns/SegmentsView.vue`
- Modify: `frontend/src/modules/campaigns/CampaignsView.vue`
- Modify: `frontend/src/modules/campaigns/CampaignDetailView.vue`
- Modify: `frontend/src/modules/campaigns/DeliveriesView.vue`
- Modify: `frontend/src/modules/campaigns/CampaignAnalyticsView.vue`
- Modify: `frontend/src/modules/campaigns/LinkAnalyticsView.vue`
- Modify: `frontend/src/modules/admin/SystemSettingsView.vue`

1. Write view tests for segment create/preview, campaign create, generation-disabled status, generation start/polling, individual draft review, pagination, reporting empty states, API errors, and responsive action controls.
2. Run focused tests to establish RED.
3. Implement typed API calls and functional pages using existing application components and styles. Use ECharts only for non-empty real metrics.
4. Make the campaign detail page link directly to its selected template editor and clearly separate personalization state from delivery state.
5. Re-run frontend tests, typecheck, lint, and production build.
6. Commit with `feat: add personalized campaign workspace`.

## Task 11: Deploy Ray Internally With Compose

**Files:**

- Modify: `worker/Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `README.md`
- Create: `docs/operations/personalization-worker.md`
- Modify: `scripts/verify-compose.sh`

1. Add a Compose configuration test or verification assertion for internal Ray head, Ray worker, personalization consumer, no published Ray port, required health dependencies, resource bounds, and disabled-by-default provider state.
2. Run the verification and confirm it fails before the services exist.
3. Add internal Ray services and environment configuration. Document that ChatGPT subscriptions do not provide API credentials and that an API key plus explicit enable flag is required.
4. Rebuild and start the full stack, then verify health and service logs without exposing secrets.
5. Commit with `ops: deploy internal ray personalization workers`.

## Task 12: Full Verification, Browser Proof, and GitHub Push

**Files:**

- Modify only if verification reveals a defect.

1. Run `./gradlew clean test` in `backend`.
2. Run `npm run test -- --run`, `npm run typecheck`, `npm run lint`, and `npm run build` in `frontend`.
3. Run `.venv/bin/pytest`, `.venv/bin/ruff check src tests`, and `.venv/bin/mypy` in `worker`.
4. Run the Compose verification script, inspect `docker compose ps`, and check backend, personalization worker, Ray head/worker, frontend, and RabbitMQ logs.
5. Authenticate through `http://127.0.0.1:8080`, visit every repaired sidebar route, create and preview a segment, create a campaign, verify provider-disabled generation behavior without an API key, check pagination and empty reporting pages, and confirm no console errors.
6. Exercise corresponding APIs directly through the frontend origin and verify authorization/error contracts.
7. Confirm no campaign send action occurred and Mailpit contains no unintended message.
8. Run `git diff --check`, inspect the complete diff, and search the new commits/branch content for the prohibited name.
9. Commit any final fixes with a neutral product-focused message.
10. Push `feature/personalized-email-ray` to `origin` and report the local URL, test evidence, provider configuration status, commit, and remote branch.

