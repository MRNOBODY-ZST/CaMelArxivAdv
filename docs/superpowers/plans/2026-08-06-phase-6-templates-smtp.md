# Phase 6 Templates and SMTP Implementation Plan

**Status:** Completed and accepted on 2026-08-07.

**Goal:** Deliver a production-shaped, permission-protected email-template workflow and SMTP-account administration slice, ending with a correctly rendered, auditable test email in Mailpit while keeping real SMTP disabled by default. Campaign recipient selection and bulk delivery remain Phase 7.

**Controlling constraints:** Template input is untrusted. HTML is sanitized on write and render; arbitrary code, scripts, event handlers, iframes, unsafe protocols, unknown variables, and context-unsafe URLs are rejected. SMTP passwords are encrypted at the application boundary and are never returned, logged, audited, or placed on RabbitMQ. Production TLS verification cannot be disabled. `ALLOW_LIVE_SMTP=false` permits only the configured local Mailpit destination.

**Technology:** Spring Boot WebFlux/R2DBC, Jakarta Mail with per-account bounded sessions, AES-GCM secret encryption, jsoup allowlist sanitization, MinIO template assets, Vue 3 strict TypeScript, Tiptap maintained editor primitives, Tailwind Plus visual system, JUnit/Testcontainers/GreenMail-style fakes where appropriate, Vitest, and real Mailpit/Browser acceptance.

## Product and security contract

- Template fields: name, description, status, subject, sender-name template, Reply-To, HTML, plain text, size, validation result, current immutable version, audit timestamps.
- Allowed variables: `author_name`, `first_name`, `paper_title`, `arxiv_id`, `primary_category`, `paper_url`, `organization`, `unsubscribe_url`.
- Preview/test values are explicit non-production samples. Text values are escaped; URL values must be absolute `http`/`https` URLs and are safely attribute-encoded. Unknown or malformed placeholders block save/test.
- Every successful content update creates the next immutable version in one transaction. Restore creates a new head version instead of rewriting history. Copy produces an independent template/version pair.
- SMTP API returns `passwordConfigured`, never ciphertext/nonce/password. A missing password on update means preserve; explicit replacement is encrypted with a random nonce. Delete is rejected while referenced, otherwise auditable.
- `PLAIN_LOCAL_ONLY` is accepted only for loopback/Mailpit allowlisted hosts while live SMTP is disabled. `STARTTLS_REQUIRED` and implicit TLS validate hostnames/certificates. Network/connect/read/write timeouts are bounded and failures are reduced to stable, non-secret categories.
- Test-send is a single-message administrative diagnostic, never a batch path. It validates the selected template/version, renders both parts, adds safe standard headers, sends to an explicit test recipient, records only masked/non-sensitive outcome data, and labels a `250` response as SMTP accepted—not delivered.

## Task 1: Lock contracts with failing tests

- Add renderer/sanitizer tests for every variable, HTML/text/URL contexts, unknown variables, malformed braces, script/event/iframe/data/javascript removal, plain-text derivation, unsubscribe warning, and byte-size thresholds.
- Add repository/service/controller tests for list/create/read/update/preview/versions/restore/copy/soft-delete with optimistic conflict handling and `template:read`/`template:manage` boundaries.
- Add SMTP tests for encryption/non-disclosure, password-preserving updates, validation/TLS/local-only gates, timeouts/error taxonomy, CRUD/test permissions, audit redaction, and `ALLOW_LIVE_SMTP=false` host blocking.
- Add frontend tests for editor draft state, variable insertion, desktop/mobile/dark preview, autosave state, version restore confirmation, SMTP secret sentinel, and loading/empty/error behavior.

## Task 2: Complete schema and configuration

- Add forward-only Flyway migrations for template optimistic versions/soft-delete uniqueness, SMTP optimistic version and test metadata, bounded template-asset metadata/indexes, and persisted text-generation mode without modifying V3 or V8/V9.
- Add typed template/SMTP/asset configuration with startup validation for public URL, object limits, Mailpit host allowlist, live-SMTP switch, timeouts, and independent secret-encryption context.
- Wire a private MinIO bucket and non-public signed/authorized asset access; accept only bounded PNG/JPEG/GIF/WebP after signature/content validation and randomized object keys.

## Task 3: Implement deterministic template domain

- Build strict DTOs, repository transactions, domain errors, validation payloads, audit events, and stable pagination.
- Build sanitizer, placeholder parser, contextual renderer, HTML-to-text draft generator, size estimator, preview model, and final immutable render snapshot contract for Phase 7.
- Implement list/create/get/update/preview/versions/restore/copy/delete and image upload endpoints. Never persist unsanitized HTML or expose deleted templates by default.

## Task 4: Implement guarded SMTP administration

- Build SMTP DTOs/repository/service/controller and AES-GCM secret storage with random nonce and authenticated context.
- Construct a fresh Jakarta Mail session per test from decrypted data, enforce TLS mode and bounded timeouts, close transports deterministically, and erase/release plaintext references as early as practical.
- Implement list/create/update/test/delete. Record test time/status and sanitized category; write success/failure audits without host credentials, recipient address, server transcript, or exception details.
- Hard-block non-Mailpit destinations when live SMTP is disabled, even if an account row is enabled or input tries a local-name/IP variant bypass.

## Task 5: Implement test rendering and Mailpit acceptance path

- Add template test-send endpoint requiring both template management and SMTP management authority.
- Validate template, Reply-To, sender, test recipient and required sample values; create multipart/alternative HTML+text with correct UTF-8 encoding and no tracking.
- Return `SMTP_ACCEPTED` only after Mailpit/SMTP acceptance, with a correlation ID and non-sensitive summary. Never call it delivered.
- Query Mailpit's development API during acceptance to verify subject, sender, recipient, plain text, sanitized HTML, variable rendering, and absence of dangerous content.

## Task 6: Build template and SMTP UI

- Add `/email/templates`, `/email/templates/new`, `/email/templates/:id`, and `/admin/smtp-accounts` with permission-aware navigation/routes.
- Compose an airy editor: metadata/subject/send fields, maintained rich-text toolbar, HTML/text mode, variable and compliant-link insertion, asset upload, byte-size/unsubscribe diagnostics, autosave indicator, desktop/mobile/dark previews, test-send dialog, version timeline/restore, copy/archive actions.
- Build SMTP cards/forms with password sentinel, TLS explanations, rate-limit fields for Phase 7, enabled/test status, test dialog, error guidance, and explicit local-only/live-SMTP banner.
- Keep full addresses/secrets out of list views, URL query, persisted browser storage, telemetry, and error surfaces.

## Task 7: Verify and accept Phase 6

- Run full backend, Worker, frontend tests; lint, strict type checks, builds, Flyway-from-empty, Compose and non-root image contracts.
- Exercise authorized/unauthorized APIs, inspect OpenAPI and database ciphertext/nonces, prove update preserves or rotates secrets correctly, and confirm logs/audits contain no secrets or test recipient address.
- Rebuild the stack, create a local-only SMTP account and sanitized template through the API/UI, test-send to Mailpit, inspect both MIME alternatives, then remove QA artifacts.
- Browser-test desktop 1280×720 and mobile 390×844 for editor interaction, previews, autosave/version restore, SMTP forms, keyboard/focus, no overflow, and no console errors.
- Update API, operations, security/privacy, architecture/ERD, README, implementation plan, tasks, and DesignSkill mapping with observed evidence.

**Final checkpoint:** Phase 6 is complete only when a real Mailpit message matches the deterministic preview in subject, sender, Reply-To, rendered variables, sanitized HTML, and text body; secrets remain encrypted and undisclosed; real SMTP remains disabled; and every quality gate passes.

## Acceptance record

Accepted after two independent review rounds with V10/V11, 201 backend tests, 45 frontend tests, 68 worker tests, all static/build gates, real MinIO and Mailpit API checks, and three self-provisioning Microsoft Edge Playwright scenarios. The review fixes added maintained rich-image nodes, HMAC-signed/absolute asset URLs, immutable-reference and archive guards, rendered-header bounds, masked SMTP cards, route-safe focus, copy-route rebinding, signed-URL access-log suppression, and transactional image deep-copy/re-signing. The Edge scenario archived the source template and still loaded the copied image. The final multipart message preserved rendered paper/unsubscribe URLs and the absolute signed image URL while containing no executable HTML. Anonymous/Viewer boundaries were 401/403, SMTP plaintext was absent from storage/logs/audits, public SMTP was blocked, and all QA artifacts were removed after verification.
