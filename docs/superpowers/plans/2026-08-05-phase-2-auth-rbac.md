# Phase 2 Authentication and RBAC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver username/email authentication, short-lived JWT access tokens, rotating HttpOnly refresh cookies, forced invalidation, five default roles, method-level permissions, auditable user/role management, and a Vue login/permission experience.

**Architecture:** PostgreSQL remains the authority for users, role grants, token families, login attempts and audit events. Spring Security validates signed JWTs and reloads user status/token version for protected requests; opaque random refresh values are stored only as SHA-256 hashes and rotated transactionally. Vue stores access tokens only in Pinia memory, refreshes once per concurrent 401 burst through an HttpOnly cookie, and derives route/navigation/button visibility from the server permission set.

**Tech Stack:** Java 25, Spring Boot 4.1 WebFlux/Security/R2DBC, Spring Security OAuth2 Resource Server/Jose, Flyway/PostgreSQL, JUnit/Testcontainers/WebTestClient, Vue 3, Pinia, Vue Router, Axios, Vitest, TypeScript strict.

## Global Constraints

- Login accepts username or email without disclosing which principal exists.
- Passwords use Spring's delegating encoder with BCrypt strength 12; raw passwords never enter logs, audit JSON or API responses.
- Access JWT lifetime defaults to 10 minutes; refresh lifetime defaults to 14 days.
- Refresh values use at least 256 random bits, are stored only as SHA-256 hashes, rotate on every use, and revoke the entire family on replay.
- Production refresh cookies are `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth`; the development override alone may set `Secure=false` for localhost.
- Access tokens remain in memory and are never written to localStorage, sessionStorage, IndexedDB or URLs.
- A disabled/locked user or mismatched `tokenVersion` is rejected even when the JWT signature and expiry are valid.
- Five system roles and all 23 permission codes from the original prompt are seeded idempotently.
- Backend method authorization is authoritative; frontend visibility is usability only.
- Login success/failure, password mutation, user state changes, role/permission changes and denied sensitive operations are audited without sensitive fields.
- Initial administrator credentials come from environment variables and always set `forcePasswordChange=true`.

---

### Task 1: RBAC seed migration and authentication configuration

**Files:**
- Modify: `backend/build.gradle`
- Create: `backend/src/main/resources/db/migration/V5__rbac_defaults_and_auth_hardening.sql`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/config/AuthProperties.java`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `.env.example`
- Modify: `docker-compose.yml`
- Modify: `docker-compose.dev.yml`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/migration/FlywayMigrationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/config/AuthPropertiesTest.java`

**Interfaces:**
- Consumes: V1 identity tables and environment configuration.
- Produces: `AuthProperties`, 23 permission rows, `SUPER_ADMIN`, `ADMIN`, `CAMPAIGN_MANAGER`, `DATA_ANALYST`, `VIEWER`, and their deterministic grants.

- [ ] **Step 1: Extend the migration test with failing RBAC assertions**

Assert exactly 23 prompt permission codes, five system roles, all permissions on `SUPER_ADMIN`, no `contact:read_full` on `VIEWER`, and no plaintext default user password in migration SQL.

- [ ] **Step 2: Run the focused migration test**

Run: `cd backend && ./gradlew test --tests '*FlywayMigrationTest'`

Expected: FAIL because V5 and default grants do not exist.

- [ ] **Step 3: Add OAuth2/Jose dependencies, V5 seed data and validated properties**

Add `spring-boot-starter-oauth2-resource-server`. Create a V5 migration using `INSERT ... ON CONFLICT DO NOTHING`; use role-to-permission `INSERT ... SELECT` statements so UUID values stay database-generated. `AuthProperties` must expose:

```java
public record AuthProperties(
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    int maxLoginFailures,
    Duration loginFailureWindow,
    String issuer,
    String signingKeyBase64,
    String fingerprintHmacKeyBase64,
    RefreshCookie cookie,
    BootstrapAdmin bootstrapAdmin
) {}
```

Validate decoded signing/HMAC keys are each at least 32 bytes. Default `cookie.secure=true`; `docker-compose.dev.yml` sets only `AUTH_COOKIE_SECURE=false`.

- [ ] **Step 4: Verify migrations, properties and Compose secrets wiring**

Run: `cd backend && ./gradlew test --tests '*FlywayMigrationTest' --tests '*AuthPropertiesTest'`

Run: `bash scripts/verify-compose.sh`

Expected: all focused tests and the Compose contract pass.

- [ ] **Step 5: Commit**

```bash
git add backend/build.gradle backend/src/main/resources backend/src/main/java/com/camel_hub/advertisement/identity/config backend/src/test .env.example docker-compose.yml docker-compose.dev.yml scripts/verify-compose.sh
git commit -m "feat: seed RBAC and authentication configuration"
```

### Task 2: Identity persistence, password policy and initial administrator

**Files:**
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/domain/UserAccount.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/domain/UserStatus.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/domain/AuthenticatedUser.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/persistence/IdentityRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/security/PasswordPolicy.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/security/SensitiveValueHasher.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/bootstrap/InitialAdminBootstrap.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/security/PasswordPolicyTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/persistence/IdentityRepositoryTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/bootstrap/InitialAdminBootstrapTest.java`

**Interfaces:**
- Consumes: `AuthProperties`, seeded `SUPER_ADMIN`, `DatabaseClient`, `PasswordEncoder`.
- Produces: case-insensitive user lookup, safe role/permission loading, user mutation methods and one-time initial admin creation.

- [ ] **Step 1: Write failing password, repository and bootstrap tests**

Cover: minimum 12 characters, upper/lower/digit/symbol, rejection of username/email fragments; lookup by mixed-case username/email; disabled status preservation; permissions returned as an immutable set; initial admin created once with BCrypt hash, `SUPER_ADMIN`, and `forcePasswordChange=true`; a second startup is idempotent.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd backend && ./gradlew test --tests '*PasswordPolicyTest' --tests '*IdentityRepositoryTest' --tests '*InitialAdminBootstrapTest'`

Expected: FAIL because the identity services are absent.

- [ ] **Step 3: Implement focused domain and persistence classes**

Use explicit SQL projections with `DatabaseClient`; never map `password_hash` into an outward DTO. Configure:

```java
@Bean
PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
}
```

`InitialAdminBootstrap` skips cleanly when any bootstrap credential is blank, rejects policy-invalid passwords, and never logs the supplied password.

- [ ] **Step 4: Verify tests and database state**

Run: `cd backend && ./gradlew test --tests '*PasswordPolicyTest' --tests '*IdentityRepositoryTest' --tests '*InitialAdminBootstrapTest'`

Expected: PASS; stored hash starts with `$2` and raw password is absent from captured logs.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/camel_hub/advertisement/identity backend/src/test/java/com/camel_hub/advertisement/identity
git commit -m "feat: add identity persistence and admin bootstrap"
```

### Task 3: Audited login and short-lived access JWT

**Files:**
- Create: `backend/src/main/java/com/camel_hub/advertisement/audit/AuditEvent.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/audit/AuditService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/api/AuthController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/api/AuthDtos.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/security/AccessTokenService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/security/LoginRateLimiter.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/service/AuthenticationService.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/api/LoginApiTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/security/LoginRateLimiterTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/audit/AuditServiceTest.java`

**Interfaces:**
- Consumes: `IdentityRepository`, `PasswordEncoder`, `AuthProperties`, `TraceIdWebFilter` exchange attribute.
- Produces: `POST /api/v1/auth/login`, signed JWT claims `sub`, `username`, `roles`, `permissions`, `tokenVersion`, `mustChangePassword`, and sanitized audit records.

- [ ] **Step 1: Write failing login/security tests**

Test username and email login, identical `401` body for unknown/wrong password, disabled/locked denial, fifth failure returning `429`, success clearing the effective failure streak, 10-minute JWT expiry, no password/token in logs or audit JSON, and audit `AUTH_LOGIN_SUCCESS`/`AUTH_LOGIN_FAILURE`.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd backend && ./gradlew test --tests '*LoginApiTest' --tests '*LoginRateLimiterTest' --tests '*AuditServiceTest'`

Expected: FAIL because login is not implemented.

- [ ] **Step 3: Implement login with constant-shape failures**

`LoginRateLimiter` hashes normalized principal and IP with HMAC before storage. Unknown users still execute `PasswordEncoder.matches` against a fixed dummy BCrypt hash. A successful login updates `last_login_at`, inserts a success attempt and audit event, then returns:

```json
{
  "accessToken": "jwt",
  "tokenType": "Bearer",
  "expiresInSeconds": 600,
  "user": {
    "id": "uuid",
    "username": "admin",
    "displayName": "Administrator",
    "roles": ["SUPER_ADMIN"],
    "permissions": ["user:read"],
    "mustChangePassword": true
  }
}
```

- [ ] **Step 4: Verify focused tests and inspect JWT contents**

Run: `cd backend && ./gradlew test --tests '*LoginApiTest' --tests '*LoginRateLimiterTest' --tests '*AuditServiceTest'`

Expected: PASS; JWT contains no email, password hash or Secret values.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/camel_hub/advertisement/audit backend/src/main/java/com/camel_hub/advertisement/identity backend/src/test/java/com/camel_hub/advertisement/audit backend/src/test/java/com/camel_hub/advertisement/identity
git commit -m "feat: add audited password login"
```

### Task 4: Refresh rotation, logout, password change and forced invalidation

**Files:**
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/domain/RefreshSession.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/persistence/RefreshTokenRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/security/RefreshTokenGenerator.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/security/RefreshCookieFactory.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/identity/service/AuthenticationService.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/identity/api/AuthController.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/api/RefreshApiTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/api/ChangePasswordApiTest.java`

**Interfaces:**
- Consumes: successful login, refresh token table, password policy and transaction operator.
- Produces: rotating `refresh_token` cookie, `POST /refresh`, `POST /logout`, `POST /change-password`, family replay revocation and token-version invalidation.

- [ ] **Step 1: Write failing rotation and password tests**

Verify login sets correct cookie attributes; refresh returns a new cookie and marks old row rotated/replaced; old-token replay revokes every family row; logout revokes the current family and expires the cookie; change password requires current password, enforces policy, clears `forcePasswordChange`, increments `tokenVersion`, revokes all refresh families and audits the action.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd backend && ./gradlew test --tests '*RefreshApiTest' --tests '*ChangePasswordApiTest'`

Expected: FAIL because refresh/session endpoints are absent.

- [ ] **Step 3: Implement transactional refresh sessions**

Generate `Base64.getUrlEncoder().withoutPadding()` values from `SecureRandom` 32-byte arrays. Hash before database access. Lock the matching refresh row with `SELECT ... FOR UPDATE`; create the replacement and update `rotated_at/replaced_by` in one transaction. Never return a refresh token in JSON.

- [ ] **Step 4: Verify focused tests**

Run: `cd backend && ./gradlew test --tests '*RefreshApiTest' --tests '*ChangePasswordApiTest'`

Expected: PASS, including concurrent refresh where exactly one request succeeds.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/camel_hub/advertisement/identity backend/src/test/java/com/camel_hub/advertisement/identity
git commit -m "feat: rotate refresh sessions securely"
```

### Task 5: Resource server, live user validation and method authorization

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/common/security/SecurityConfiguration.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/security/LiveUserJwtAuthenticationConverter.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/security/Permission.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/api/CurrentUserController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/contact/security/EmailDisclosurePolicy.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/security/ResourceServerAuthorizationTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/contact/security/EmailDisclosurePolicyTest.java`

**Interfaces:**
- Consumes: JWT from Task 3 and current PostgreSQL user state.
- Produces: authenticated principal authorities, `GET /api/v1/auth/me`, `@EnableReactiveMethodSecurity`, permission constants and full/masked email policy.

- [ ] **Step 1: Write failing resource server tests**

Cover missing/invalid/expired JWT, valid JWT, disabled user after issuance, token-version increment after issuance, permission denial, `/auth/me`, and email rendering `jo***@example.edu` without `contact:read_full`.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd backend && ./gradlew test --tests '*ResourceServerAuthorizationTest' --tests '*EmailDisclosurePolicyTest'`

Expected: FAIL because protected Bearer authentication is absent.

- [ ] **Step 3: Configure JWT decoding and live validation**

Permit only login, refresh, tracking placeholders, health and API docs. The converter loads the current user, rejects non-`ACTIVE` state and compares database/JWT token versions before emitting `UsernamePasswordAuthenticationToken` with exact permission authorities.

- [ ] **Step 4: Verify focused tests**

Run: `cd backend && ./gradlew test --tests '*ResourceServerAuthorizationTest' --tests '*EmailDisclosurePolicyTest'`

Expected: PASS; frontend-hidden routes remain forbidden by backend authorization.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/camel_hub/advertisement/common/security backend/src/main/java/com/camel_hub/advertisement/identity backend/src/main/java/com/camel_hub/advertisement/contact backend/src/test
git commit -m "feat: enforce live JWT permissions"
```

### Task 6: User, role, permission and audit administration APIs

**Files:**
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/api/UserAdminController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/api/RoleAdminController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/audit/AuditLogController.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/service/UserAdministrationService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/identity/service/RoleAdministrationService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/audit/AuditQueryService.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/api/UserAdminApiTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/identity/api/RoleAdminApiTest.java`
- Create: `backend/src/test/java/com/camel_hub/advertisement/audit/AuditLogApiTest.java`

**Interfaces:**
- Consumes: method security, identity repository, password policy and audit service.
- Produces: all `/api/v1/users`, `/roles`, `/permissions`, `/audit-logs` endpoints from the prompt plus `POST /users/{id}/reset-password`.

- [ ] **Step 1: Write failing administration API tests**

Verify required permission on every endpoint; create/update/disable/enable; admin reset sets force-change and invalidates sessions; system role code cannot be renamed/deleted; custom role grants must be known permission codes; the last active `SUPER_ADMIN` cannot be disabled or stripped; audits contain before/after summaries but no hashes/tokens/passwords.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd backend && ./gradlew test --tests '*UserAdminApiTest' --tests '*RoleAdminApiTest' --tests '*AuditLogApiTest'`

Expected: FAIL because administration controllers are absent.

- [ ] **Step 3: Implement paginated, authorized services**

Use `@PreAuthorize("hasAuthority('user:read')")` and the matching prompt codes. All writes execute in transactions, increment affected user token versions when role/status/password changes, and return outward records that omit password/refresh hashes. Audit query supports time, actor, action, resource and result filters.

- [ ] **Step 4: Verify focused tests and OpenAPI**

Run: `cd backend && ./gradlew test --tests '*UserAdminApiTest' --tests '*RoleAdminApiTest' --tests '*AuditLogApiTest'`

Run: `cd backend && ./gradlew check bootJar`

Expected: PASS; OpenAPI contains auth/user/role/permission/audit paths.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/camel_hub/advertisement/identity backend/src/main/java/com/camel_hub/advertisement/audit backend/src/test
git commit -m "feat: add audited identity administration"
```

### Task 7: Vue in-memory authentication and permission-aware shell

**Files:**
- Create: `frontend/src/modules/auth/auth.types.ts`
- Create: `frontend/src/modules/auth/auth.permissions.ts`
- Create: `frontend/src/modules/auth/auth.api.ts`
- Create: `frontend/src/modules/auth/auth.store.ts`
- Create: `frontend/src/modules/auth/LoginView.vue`
- Modify: `frontend/src/api/httpClient.ts`
- Modify: `frontend/src/api/refreshCoordinator.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/AppShell.vue`
- Create: `frontend/src/modules/auth/__tests__/auth.store.spec.ts`
- Create: `frontend/src/modules/auth/__tests__/router.permissions.spec.ts`
- Modify: `frontend/src/layouts/__tests__/AppShell.spec.ts`

**Interfaces:**
- Consumes: login/refresh/logout/me APIs and permission strings.
- Produces: memory-only `accessToken`, bootstrap refresh, one-refresh concurrency lock, `/login`, forced-password-change redirect and permission-filtered navigation.

- [ ] **Step 1: Write failing auth store/router/shell tests**

Test login, initial `/me` bootstrap through refresh cookie, no Web Storage writes, 20 simultaneous 401 responses causing one refresh, failed refresh clearing memory, `mustChangePassword` redirect, route meta denial, and navigation groups containing only authorized items.

- [ ] **Step 2: Run focused frontend tests and verify failure**

Run: `cd frontend && npm test -- --run src/modules/auth src/layouts/__tests__/AppShell.spec.ts`

Expected: FAIL because the auth module does not exist.

- [ ] **Step 3: Implement the memory-only auth flow**

Define route metadata as `requiresAuth` and `permissions: readonly Permission[]`. `httpClient` attaches the current Bearer token, excludes login/refresh from retry, queues concurrent callers behind `refreshCoordinator`, retries each original request once, and never serializes the access token outside memory.

- [ ] **Step 4: Verify tests, types, lint and production build**

Run: `cd frontend && npm test -- --run && npm run typecheck && npm run lint && npm run build`

Expected: all commands pass with zero warnings.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/modules/auth frontend/src/api frontend/src/router frontend/src/layouts
git commit -m "feat: add in-memory Vue authentication"
```

### Task 8: Identity administration UI, documentation and Phase 2 acceptance

**Files:**
- Create: `frontend/src/modules/admin/UsersView.vue`
- Create: `frontend/src/modules/admin/RolesView.vue`
- Create: `frontend/src/modules/admin/AuditLogsView.vue`
- Create: `frontend/src/modules/admin/admin.api.ts`
- Create: `frontend/src/modules/admin/__tests__/admin.views.spec.ts`
- Create: `docs/RBAC.md`
- Modify: `docs/API.md`
- Modify: `docs/OPERATIONS.md`
- Modify: `README.md`
- Modify: `IMPLEMENTATION_PLAN.md`
- Modify: `TASKS.md`

**Interfaces:**
- Consumes: administration APIs and DesignSkill primitives.
- Produces: responsive user/role/audit pages, documented role matrix, test account/bootstrap procedure and Phase 2 evidence.

- [ ] **Step 1: Write failing view authorization and state tests**

Verify Skeleton/Empty/Error states, user disable confirmation, role permission checkbox labeling, no password/hash rendering, audit filters, 403 handling, and buttons hidden without the exact write permission.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `cd frontend && npm test -- --run src/modules/admin`

Expected: FAIL because administration views are absent.

- [ ] **Step 3: Implement DesignSkill-based administration pages and documentation**

Use existing `DsTable`, `DsBadge`, `DsModal`, `DsEmptyState`, `DsSkeleton` and `DsAlert`. Document the complete role-permission matrix, initial admin environment flow, forced password change, refresh replay response, lockout recovery and audit fields.

- [ ] **Step 4: Rebuild images and run Phase 2 end-to-end acceptance**

Run: `cd backend && ./gradlew clean check bootJar`

Run: `cd frontend && npm test -- --run && npm run lint && npm run typecheck && npm run build`

Run: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build`

Run: `bash scripts/verify-compose.sh && bash scripts/verify-container-images.sh`

Verify with HTTP/browser: initial admin login, forced change, refresh rotation, logout, ADMIN allowed user read, VIEWER receives 403, and role-specific navigation.

Expected: all automated commands pass; all nine services remain healthy; no token appears in browser storage/logs; browser console has zero warnings/errors.

- [ ] **Step 5: Commit Phase 2 acceptance**

```bash
git add frontend/src/modules/admin docs README.md IMPLEMENTATION_PLAN.md TASKS.md
git commit -m "docs: complete phase two acceptance"
```

## Self-Review

- Spec coverage: username/email login, hashing, access/refresh, rotation, logout, forced invalidation, rate limiting, password changes/admin reset, initial forced change, five roles, 23 permissions, backend/frontend checks, email disclosure and required audit actions are each assigned above.
- Placeholder scan: implementation steps define concrete endpoints, fields, algorithms, tests and commands; no deferred placeholder is used.
- Type consistency: `AuthProperties`, `AuthenticatedUser`, `Permission`, JWT claims, frontend `Permission`, auth response and route metadata retain the same names across tasks.
