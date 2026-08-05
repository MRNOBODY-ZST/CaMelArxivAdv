# Phase 1 Engineering Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible foundation where the Spring API, Python worker, Vue frontend, and required infrastructure build, expose health status, and start together without implementing later business workflows as mocks.

**Architecture:** Preserve the existing reactive Spring Boot application and convert its accidental MySQL/Mongo setup to PostgreSQL, Redis, RabbitMQ, Flyway, Actuator, and OpenAPI. Add a typed Python worker foundation and a Vue 3/Tailwind application shell adapted from the licensed DesignSkill components. Compose connects production-shaped services while Mailpit and live-SMTP protection keep development safe.

**Tech Stack:** Java 25, Spring Boot 4.1, Gradle 9.5, WebFlux, R2DBC PostgreSQL, Flyway, Redis, RabbitMQ, Python 3.12, uv, Pydantic, Vue 3, TypeScript strict, Vite, Tailwind CSS 4, Vitest, Playwright, Docker Compose.

## Global Constraints

- Preserve `backend/`, its Gradle Wrapper, `AdvertisementApplication`, and package `com.camel_hub.advertisement`.
- UI copy is Simplified Chinese; identifiers and API/database fields are English.
- Persist UTC and display browser-local time.
- Use `/api/v1`, unified pagination, and RFC 7807-style errors with `traceId` and `fieldErrors`.
- `ALLOW_LIVE_SMTP=false` by default; development and tests use Mailpit only.
- Do not execute TeX, retain Source archives, guess emails, or log secrets/full email addresses.
- Use DesignSkill `Sidebar with header` and a non-uniform `Bento Grids` layout with traceable component mapping.
- No production code for behavior may be written before its failing test has been observed.

---

### Task 1: Repository guardrails and dependency resolution

**Files:**
- Create: `.gitignore`
- Create: `.env.example`
- Create: `backend/settings.gradle` repository configuration additions
- Modify: `backend/build.gradle`
- Modify: `backend/src/main/resources/application.yaml`
- Test: `backend/src/test/java/com/camel_hub/advertisement/AdvertisementApplicationTests.java`

**Interfaces:**
- Consumes: existing Gradle Wrapper and `AdvertisementApplication`.
- Produces: `test`, `check`, and `bootJar` Gradle tasks with PostgreSQL/Redis/RabbitMQ/Flyway/Actuator/OpenAPI dependencies.

- [ ] **Step 1: Replace the empty context test with an isolated configuration test**

```java
@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class AdvertisementApplicationTests {
    @Test void contextLoads() {}
}
```

- [ ] **Step 2: Run the test and record the dependency-resolution failure**

Run: `cd backend && ./gradlew test --tests '*AdvertisementApplicationTests'`

Expected before mirror/config changes: Gradle fails resolving Spring artifacts with Maven Central HTTP 403.

- [ ] **Step 3: Configure repositories and the production dependency set**

Use Spring WebFlux/Security/Validation/Actuator/AMQP/Data Redis/Data R2DBC, PostgreSQL R2DBC/JDBC, Flyway PostgreSQL, Spring Mail, JWT, Jsoup, Bucket4j, springdoc WebFlux, Lombok, Testcontainers, Reactor Test and Spring Security Test. Remove MongoDB, MySQL and Gateway dependencies because no existing code consumes them. Add `.gitignore` entries for `.DS_Store`, `.env`, build output, IDE files, Python caches, Node output, Playwright output, object-store data and `.worktrees/`.

- [ ] **Step 4: Run the isolated context test**

Run: `cd backend && ./gradlew test --tests '*AdvertisementApplicationTests'`

Expected: `BUILD SUCCESSFUL` and one passing test.

- [ ] **Step 5: Commit the foundation build**

```bash
git add .gitignore .env.example backend
git commit -m "build: establish platform dependencies"
```

### Task 2: Unified errors, trace context, and health summary

**Files:**
- Create: `backend/src/main/java/com/camel_hub/advertisement/common/api/ApiError.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/common/api/FieldViolation.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/common/api/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/common/observability/TraceIdWebFilter.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/system/SystemHealthController.java`
- Test: `backend/src/test/java/com/camel_hub/advertisement/common/api/GlobalExceptionHandlerTest.java`
- Test: `backend/src/test/java/com/camel_hub/advertisement/system/SystemHealthControllerTest.java`

**Interfaces:**
- Consumes: Spring WebFlux and Actuator `HealthEndpoint`.
- Produces: `ApiError(type,title,status,detail,instance,traceId,fieldErrors)`, response header `X-Trace-Id`, and `GET /api/v1/system/health`.

- [ ] **Step 1: Write failing WebTestClient tests**

```java
webTestClient.get().uri("/api/v1/system/health").exchange()
    .expectStatus().isOk()
    .expectHeader().exists("X-Trace-Id")
    .expectBody().jsonPath("$.status").isEqualTo("UP");

webTestClient.post().uri("/api/v1/test/validation").bodyValue(Map.of()).exchange()
    .expectStatus().isBadRequest()
    .expectBody().jsonPath("$.type").isEqualTo("validation_error")
    .jsonPath("$.traceId").isNotEmpty();
```

- [ ] **Step 2: Verify both tests fail for missing endpoints/handlers**

Run: `cd backend && ./gradlew test --tests '*GlobalExceptionHandlerTest' --tests '*SystemHealthControllerTest'`

Expected: FAIL with 404 or missing bean assertions.

- [ ] **Step 3: Implement immutable error DTOs, exception advice, trace filter, and health controller**

Generate a 32-character lowercase hex trace ID when the inbound header is absent; put it in Reactor Context and the response header. Map binding/validation failures to `validation_error`, access denial to 403, missing resources to 404, and unexpected errors to a generic 500 without stack details.

- [ ] **Step 4: Run focused tests and backend check**

Run: `cd backend && ./gradlew test --tests '*GlobalExceptionHandlerTest' --tests '*SystemHealthControllerTest' && ./gradlew check`

Expected: focused tests and `check` pass.

- [ ] **Step 5: Commit API foundation**

```bash
git add backend/src
git commit -m "feat: add traceable API error foundation"
```

### Task 3: Flyway schema baseline

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__identity_and_audit.sql`
- Create: `backend/src/main/resources/db/migration/V2__arxiv_papers_contacts_jobs.sql`
- Create: `backend/src/main/resources/db/migration/V3__templates_campaigns_tracking.sql`
- Create: `backend/src/main/resources/db/migration/V4__analytics_and_retention.sql`
- Test: `backend/src/test/java/com/camel_hub/advertisement/migration/FlywayMigrationTest.java`

**Interfaces:**
- Consumes: PostgreSQL 17 and Flyway.
- Produces: all named tables and constraints from sections 9, 14, 17, and 19 of the accepted prompt, using UUID and `timestamptz`.

- [ ] **Step 1: Write a failing PostgreSQL Testcontainers migration test**

```java
@Test
void migratesEmptyDatabaseAndCreatesCriticalConstraints() {
    var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load();
    assertThat(flyway.migrate().success).isTrue();
    assertThat(tableNames()).contains("users", "arxiv_categories", "papers", "contacts", "jobs", "campaigns", "tracking_events", "audit_logs");
    assertThat(uniqueConstraints()).contains("uk_papers_arxiv_id", "uk_campaign_recipient", "uk_jobs_idempotency_key");
}
```

- [ ] **Step 2: Verify failure because migrations do not exist**

Run: `cd backend && ./gradlew test --tests '*FlywayMigrationTest'`

Expected: FAIL because required tables are absent.

- [ ] **Step 3: Add normalized migrations and indexes**

Create identity/audit, arXiv/contact/job, email/campaign/tracking, and aggregate/retention migrations. Add check constraints for enum-like states, foreign keys with deliberate delete behavior, partial indexes for active jobs and scheduled campaigns, and comments distinguishing SMTP Accepted from delivery.

- [ ] **Step 4: Verify clean and repeated migrations**

Run: `cd backend && ./gradlew test --tests '*FlywayMigrationTest'`

Expected: first migration succeeds; a second `migrate()` applies zero migrations; all constraint assertions pass.

- [ ] **Step 5: Commit migrations**

```bash
git add backend/src/main/resources/db backend/src/test/java/com/camel_hub/advertisement/migration
git commit -m "feat: add production database baseline"
```

### Task 4: Python worker foundation

**Files:**
- Create: `worker/pyproject.toml`
- Create: `worker/src/app/__init__.py`
- Create: `worker/src/app/config.py`
- Create: `worker/src/app/observability/logging.py`
- Create: `worker/src/app/messaging/contracts.py`
- Create: `worker/src/app/main.py`
- Create: `worker/tests/test_config.py`
- Create: `worker/tests/test_contracts.py`

**Interfaces:**
- Consumes: environment variables with prefix `ARXIV_WORKER_`.
- Produces: `Settings`, `MessageEnvelope[T]`, JSON structured logging, and a RabbitMQ worker entry point that refuses unknown message versions.

- [ ] **Step 1: Write failing settings and message-contract tests**

```python
def test_defaults_enforce_safe_arxiv_rate_and_hosts() -> None:
    settings = Settings()
    assert settings.min_request_interval_seconds >= 3
    assert settings.allowed_arxiv_hosts == {"export.arxiv.org", "arxiv.org"}

def test_message_requires_supported_version() -> None:
    with pytest.raises(ValidationError):
        MessageEnvelope.model_validate({"version": 99, "messageId": str(uuid4()), "type": "ARXIV_SYNC_TAXONOMY", "payload": {}})
```

- [ ] **Step 2: Verify tests fail because the package is absent**

Run: `cd worker && uv run pytest tests/test_config.py tests/test_contracts.py -q`

Expected: FAIL with import errors.

- [ ] **Step 3: Implement typed settings, contracts, logs, and process lifecycle**

Pin runtime and development dependencies, expose an `arxiv-worker` console script, configure JSON logs with `traceId/jobId/messageId`, validate supported version `1`, and close RabbitMQ connections on SIGTERM without logging payload secrets.

- [ ] **Step 4: Run worker quality gates**

Run: `cd worker && uv run pytest -q && uv run ruff check . && uv run mypy src`

Expected: tests, Ruff and MyPy pass.

- [ ] **Step 5: Commit worker foundation**

```bash
git add worker
git commit -m "feat: initialize typed arxiv worker"
```

### Task 5: Vue application and DesignSkill primitives

**Files:**
- Create: `frontend/package.json`, `frontend/package-lock.json`, `frontend/tsconfig*.json`, `frontend/vite.config.ts`, `frontend/vitest.config.ts`, `frontend/index.html`
- Create: `frontend/src/main.ts`, `frontend/src/App.vue`, `frontend/src/styles.css`
- Create: `frontend/src/router/index.ts`, `frontend/src/stores/auth.ts`, `frontend/src/api/client.ts`
- Create: `frontend/src/components/design-skill/{DsButton,DsInput,DsSelect,DsCheckbox,DsRadio,DsSwitch,DsBadge,DsAlert,DsModal,DsDrawer,DsDropdown,DsTabs,DsTable,DsPagination,DsCard,DsEmptyState,DsSkeleton,DsToast,DsTooltip,DsBreadcrumb}.vue`
- Create: `frontend/src/layouts/AppShell.vue`
- Create: `frontend/src/views/dashboard/DashboardView.vue`
- Create: `frontend/src/components/design-skill/__tests__/primitives.spec.ts`
- Create: `frontend/src/layouts/__tests__/AppShell.spec.ts`
- Create: `docs/DESIGN_SKILL_COMPONENT_MAP.md`

**Interfaces:**
- Consumes: `/api/v1/system/health` and DesignSkill component names/source URLs.
- Produces: typed Vue primitives, `AppShell`, dashboard route `/`, an Axios base client that queues concurrent refresh attempts behind one `Promise`, and a source-to-adapter mapping.

- [ ] **Step 1: Scaffold only test/build configuration, then write failing component tests**

```ts
it('emits click and exposes accessible button text', async () => {
  const wrapper = mount(DsButton, { slots: { default: '创建任务' } })
  await wrapper.get('button').trigger('click')
  expect(wrapper.emitted('click')).toHaveLength(1)
})

it('renders the licensed sidebar/header and non-uniform bento layout', () => {
  const wrapper = mount(AppShell, { global: { plugins: [router] } })
  expect(wrapper.get('[data-design-skill="sidebar-with-header"]').exists()).toBe(true)
  expect(wrapper.get('[data-design-skill="bento-grid"]').classes()).toContain('lg:grid-cols-3')
})
```

- [ ] **Step 2: Verify component tests fail because adapters are absent**

Run: `cd frontend && npm test -- --run src/components/design-skill/__tests__/primitives.spec.ts src/layouts/__tests__/AppShell.spec.ts`

Expected: FAIL with unresolved Vue component imports.

- [ ] **Step 3: Adapt DesignSkill components and bind real health state**

Use `Sidebar with header`, `Two row bento grid with three column second row`, and the cataloged application-ui primitives. Preserve major DOM/class structure and accessibility behavior; replace demo content with Chinese navigation and real `/api/v1/system/health` loading, error, empty, and success states. Record original component, catalog path/upstream URL, adapter path, and fidelity notes in the component map.

- [ ] **Step 4: Run frontend quality gates**

Run: `cd frontend && npm test -- --run && npm run typecheck && npm run lint && npm run build`

Expected: Vitest, TypeScript, ESLint and Vite build pass.

- [ ] **Step 5: Commit frontend foundation**

```bash
git add frontend docs/DESIGN_SKILL_COMPONENT_MAP.md
git commit -m "feat: add licensed DesignSkill Vue shell"
```

### Task 6: Containers and reverse proxy

**Files:**
- Create: `docker-compose.yml`
- Create: `docker-compose.dev.yml`
- Create: `backend/Dockerfile`
- Create: `worker/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `infra/nginx/default.conf`
- Create: `infra/postgres/init/.gitkeep`
- Test: `scripts/verify-compose.sh`

**Interfaces:**
- Consumes: application images and `.env` variables.
- Produces: `postgres`, `redis`, `rabbitmq`, `minio`, `backend-api`, `mail-worker`, `arxiv-worker`, `frontend`, and `mailpit` services with health checks and internal networks.

- [ ] **Step 1: Add a failing declarative Compose verification script**

```bash
required='postgres redis rabbitmq minio backend-api mail-worker arxiv-worker frontend mailpit'
actual=$(docker compose config --services)
for service in $required; do grep -qx "$service" <<<"$actual"; done
test "$(docker compose config --format json | jq -r '.services["mail-worker"].environment.ALLOW_LIVE_SMTP')" = "false"
```

- [ ] **Step 2: Verify failure because root Compose is absent**

Run: `bash scripts/verify-compose.sh`

Expected: FAIL because `docker-compose.yml` does not exist.

- [ ] **Step 3: Implement pinned, non-root multi-stage images and Compose**

Expose only Nginx, Mailpit web UI in the development override, and MinIO console when explicitly enabled. Keep PostgreSQL/Redis/RabbitMQ internal. Use named volumes, service health dependencies, resource guidance, non-root users, `.env` interpolation, and Nginx `/api` plus `/t` proxy rules with security headers.

- [ ] **Step 4: Verify configuration and build images**

Run: `bash scripts/verify-compose.sh && docker compose config --quiet && docker compose build`

Expected: verification and configuration pass; all application images build.

- [ ] **Step 5: Commit deployment foundation**

```bash
git add docker-compose.yml docker-compose.dev.yml backend/Dockerfile worker/Dockerfile frontend/Dockerfile infra scripts
git commit -m "build: add production-shaped compose stack"
```

### Task 7: Phase 1 integration verification and documentation

**Files:**
- Modify: `README.md`
- Create: `docs/ARCHITECTURE.md`
- Create: `docs/ERD.md`
- Create: `docs/API.md`
- Create: `docs/DEPLOYMENT.md`
- Create: `docs/OPERATIONS.md`
- Modify: `IMPLEMENTATION_PLAN.md`
- Modify: `TASKS.md`

**Interfaces:**
- Consumes: all Phase 1 deliverables.
- Produces: documented startup, architecture, schema, API, operations, and recorded verification evidence.

- [ ] **Step 1: Start the full development stack from an empty Compose project**

Run: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build`

Expected: all nine services start; health-dependent services become healthy without manual database changes.

- [ ] **Step 2: Verify runtime endpoints**

Run: `docker compose ps && curl -fsS http://localhost:8080/api/v1/system/health && curl -fsS http://localhost:8025/api/v1/info`

Expected: Compose reports healthy services; backend returns `status=UP`; Mailpit returns service info.

- [ ] **Step 3: Run all Phase 1 quality gates**

Run: `cd backend && ./gradlew clean check bootJar`

Run: `cd worker && uv run pytest -q && uv run ruff check . && uv run mypy src`

Run: `cd frontend && npm test -- --run && npm run typecheck && npm run lint && npm run build`

Expected: every command exits zero.

- [ ] **Step 4: Write durable documentation and actual results**

Document the architecture diagram, directory tree, Flyway migrations, API error/health contracts, environment variables, startup/stop/log commands, backup notes, default security settings, DesignSkill licensing/mapping, and any verification blocked by network or runtime state. Check completed Phase 1 tasks only when their evidence exists.

- [ ] **Step 5: Commit Phase 1 acceptance**

```bash
git add README.md docs IMPLEMENTATION_PLAN.md TASKS.md
git commit -m "docs: complete phase one acceptance"
```
