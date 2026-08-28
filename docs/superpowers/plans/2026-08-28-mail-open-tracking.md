# Mail open tracking implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record real test sends and let users inspect signed image-load callbacks without claiming confirmed human reading.

**Architecture:** A separate mail-send record and event subsystem wraps the two existing SMTP test paths. A signed anonymous pixel endpoint records deduplicated classified events; authenticated management APIs expose only masked records and event summaries. The existing deliveries page gains a test-mail tab and both send dialogs gain opt-in controls.

**Tech Stack:** Spring Boot WebFlux/R2DBC/PostgreSQL/Flyway, Java, Vue 3/TypeScript/Tailwind, JUnit/Testcontainers/Vitest, nginx and Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-28-mail-open-tracking-design.md`

## Global Constraints

- Keep all existing user data and credentials; never commit secrets or put credentials/tokens in logs.
- Do not publish a public web service, create a tunnel, or send to paper authors.
- Keep test-send records separate from campaign delivery and analytics tables.
- Tracking is opt-in per message (`trackOpens`, default false); global tracking defaults disabled.
- UI uses “检测到图片加载 / 估算打开”, never a definitive human-read claim.
- Use existing Vue/Tailwind design-skill components and preserve current permissions/navigation.
- Do not include the assistant product name in source, branch names or commit messages.

---

### Task 1: Signed callback backend and actual send records

**Files:**
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/tracking/{MailTrackingProperties,MailTrackingSigner,MailOpenClassifier,MailTrackingRepository,MailTrackingService,MailTrackingController,MailOpenController,MailTrackingConfiguration}.java` and narrowly scoped model/exception files in that package if needed.
- Create: the next sequential Flyway `V...__mail_open_tracking.sql` (determine next version from existing migrations; never modify an applied migration).
- Modify: `email/smtp/{SmtpService,SmtpDtos,SmtpController}.java`, `email/template/{TemplateMailService,TemplateDtos,TemplateController}.java`, `email/EmailConfiguration.java`, `common/security/SecurityConfiguration.java`, `application.yaml`.
- Modify: `docker-compose.yml`, `.env.example`, `infra/nginx/default.conf`, `docs/DEPLOYMENT.md`; create `docs/EMAIL_TRACKING.md`.
- Test: new `email/tracking/*Test.java` plus existing SMTP/template API/service tests that use changed constructors/signatures and migration fixture counts if applicable.

**Interfaces:**
- Consumes existing `SmtpTransport.OutboundMessage`, `SmtpService.TestResult`, `DatabaseClient`, `PageResponse`, and existing security authorities.
- Request DTOs add `boolean trackOpens`, omission means false. Pass it through both controllers to their services. Keep old responses `{status,errorCategory,correlationId}` unchanged.
- Extend send services with explicit tracking options; both real send entrypoints must use the same recording wrapper. No tracking code in template preview, editor content or version storage.
- Produces exact JSON from the spec: `GET /api/v1/mail-tracking/status`, `GET /api/v1/mail-send-records`, `GET /api/v1/mail-send-records/{id}`, and anonymous `GET /t/o/{token}`.
- `TrackingStatus`: `enabled:boolean`, `callbackBaseUrl:string`, `callbackScope:LOCAL_ONLY|PUBLIC_HTTPS_UNVERIFIED`, `tokenTtlSeconds:number`.
- `MailSendRecord`: `id`, `source:SMTP_DIAGNOSTIC|TEMPLATE_TEST`, `recipientMasked`, `subject`, `smtpAccountName:string|null`, `status:SENDING|SMTP_ACCEPTED|FAILED|UNKNOWN`, `failureCategory:string|null`, `trackingEnabled:boolean`, `createdAt`, `completedAt:string|null`, `trackingExpiresAt:string|null`, `rawOpenCount:number`, `automatedOpenCount:number`, `firstOpenAt:string|null`, `lastOpenAt:string|null`.
- Detail response: `{record:MailSendRecord,events:Array<{id:number,occurredAt:string,classification:UNCLASSIFIED|PREFETCH|IMAGE_PROXY|BOT,reason:string}>}`. Latest 50 events, stable descending order.

- [ ] Step 1: Add failing tests for current missing behavior. Use real PostgreSQL to prove migration, record persistence, event counters and unique dedup; mock only the external SMTP transport. A known accepted send without a pixel must fail an assertion on the actual captured outbound HTML, and anonymous invalid GET must fail because it currently returns 404 instead of GIF. Example behavior skeleton to adapt to the existing fixture:

```java
var result = service.sendDiagnostic(actor, account.id(), "qa@example.invalid", "QA", "body", true, context).block();
var html = capturedOutboundMessage.html();
assertThat(html).contains("/t/o/").doesNotContain("qa@example.invalid/t/o");
assertThat(detail(result.correlationId()).record().rawOpenCount()).isZero();
anonymousClient.get().uri(pixelFrom(html)).exchange().expectStatus().isOk()
    .expectHeader().contentType("image/gif").expectHeader().valueContains("Cache-Control", "no-store");
assertThat(detail(result.correlationId()).record().rawOpenCount()).isEqualTo(1);
assertThat(detail(result.correlationId()).record().status()).isEqualTo("SMTP_ACCEPTED");
```

- [ ] Step 2: Run `./gradlew test --tests '*MailTracking*Test' --tests '*MailOpen*Test'` from backend and capture expected RED output. Existing API tests also need failure on opt-in forwarding, not just source-string assertions.
- [ ] Step 3: Implement the schema, properties, signer and callback boundary. Use an opaque UUID + secure random nonce + expiry signed by HMAC-SHA256, require at least 32 decoded key bytes, store SHA-256 token digest only, and constant-time verification. Validate origin without fetching it. Store bounded UA/classification digests and no IP/raw-UA; use `(record_id,fingerprint_hash,minute_bucket)` uniqueness with `ON CONFLICT DO NOTHING`. Index record sorting, token lookup, event record/time and FK columns. Validate TTL bounds, pagination, invalid IDs and config at boundaries. Core ordering is:

```text
validate/render -> insert SENDING + token hash -> SMTP attempt
  accepted -> store SMTP_ACCEPTED -> existing account test/audit -> response
  definitive rejection -> store FAILED -> existing error response
  uncertain network/timeout -> store UNKNOWN -> existing error response
```

Errors after SMTP acceptance cannot set FAILED or trigger retry. Record insertion failure prevents SMTP. Callback never mutates SMTP outcomestatus. Explicit tracking with global-disabled configuration rejects before send. Protect management APIs with the spec's authorities. GET returns a fixed transparent GIF for valid/invalid tokens; HEAD does not count. Retention maintenance deletes only this new subsystem's records/events by an explicit age cutoff; no new scheduler.
- [ ] Step 4: Add concrete security/behavior regression cases: two sends generate different tokens; altered/expired/unknown/failed/disabled tokens do not change counts; concurrent same-minute callbacks store one event; next-minute callback stores a second; prefetch/proxy/bot retain separate classifications; unknown requests stay UNCLASSIFIED; no tokens in management payloads; list pagination and latest-50 detail bounds; unauthorized management requests denied; HEAD ignored; untracked send stores a record without pixel; template text/subject/preview unchanged. Run focused tests until green.
- [ ] Step 5: Wire configuration into Compose and document defaults, local-only limitations, misleading read signals, event dedup, token expiry and retention. In nginx `/t/` disable access and error logs for capability URLs and apply a bounded rate limiter; do not expose new ports. Use existing deployed PUBLIC_BASE_URL as fallback. No editing real `.env` by implementer.
- [ ] Step 6: Run `./gradlew test` once, inspect test results, run `git diff --check`, self-review all touched paths, and commit with `feat: record test mail and signed open callbacks`. Full report includes RED/GREEN commands/output, exact tests and any known warnings; no secrets.

### Task 2: Opt-in tracking and truthful frontend records

**Files:**
- Create: `frontend/src/modules/email/{mail-tracking.types,mail-tracking.api}.ts`, `MailTrackingOption.vue`, `MailSendRecordsPanel.vue`, `MailSendRecordDialog.vue`.
- Modify: `frontend/src/modules/campaigns/DeliveriesView.vue`, `frontend/src/modules/email/{EmailTemplateEditorView,SmtpAccountsView}.vue`, `email.api.ts` and relevant navigation permission declaration only if needed to expose records to smtp readers without widening campaign permissions.
- Test: `frontend/src/modules/email/__tests__/*` or existing equivalent test paths; extend tests for SMTP/template dialogs and create record panel/detail/option tests.
- Docs: update `README.md` and `docs/EMAIL_TRACKING.md` with actual navigation and actions.

**Interfaces:**
- Consume the exact Task 1 JSON contract copied here: `GET /mail-tracking/status` gives `{enabled,callbackBaseUrl,callbackScope:LOCAL_ONLY|PUBLIC_HTTPS_UNVERIFIED,tokenTtlSeconds}`; `GET /mail-send-records?page&pageSize` gives existing PageResponse of `{id,source:SMTP_DIAGNOSTIC|TEMPLATE_TEST,recipientMasked,subject,smtpAccountName,status:SENDING|SMTP_ACCEPTED|FAILED|UNKNOWN,failureCategory,trackingEnabled,createdAt,completedAt,trackingExpiresAt,rawOpenCount,automatedOpenCount,firstOpenAt,lastOpenAt}`; `GET /mail-send-records/{id}` gives `{record,events:[{id,occurredAt,classification:UNCLASSIFIED|PREFETCH|IMAGE_PROXY|BOT,reason}]}`. Nullable fields match the spec. Management record APIs require `smtp:read`, config allows `smtp:read` or `template:read`. UI must respect those requirements.
- Add optional final `trackOpens = false` to `emailApi.testSendTemplate(...)` and `sendSmtpDiagnostic(...)`; send `trackOpens` in the request body.
- Reusable opt-in component consumes `modelValue:boolean` and emits `update:modelValue`. It fetches config, defaults unchecked, resets per parent dialog, disables only tracking when config unavailable/disabled, displays callback URL and local-only/unverified warning.
- Deliveries route stays `/email/deliveries`; deep link is `/email/deliveries?record=<correlationId>`. Records tab default; campaign view preserved behind its existing permission. Task 1 correlation IDs are record IDs. Do not display or fetch a tracking pixel from any UI.

- [ ] Step 1: Read the existing Ds* primitives and use the frontend-tailwind-css library to select table, alert, tabs and checkbox structures. Add failing tests proving the outgoing API body carries the user's opt-in, dialog reset removes opt-in, disabled/error config blocks opt-in while leaving untracked send usable, and initial record render displays the masked recipient and truthful image-load state. Behavioral sketch:

```ts
const panel = mount(MailSendRecordsPanel, { global: { plugins: [router, pinia] } })
await flushPromises()
expect(panel.text()).toContain('q***@example.invalid')
expect(panel.text()).toContain('尚无回传')
await panel.get('button[aria-label="刷新测试邮件记录"]').trigger('click')
await flushPromises()
expect(panel.text()).toContain('检测到图片加载')
```

Use explicit full mock API fixtures and assert outgoing payloads only at the external HTTP boundary, keeping real Vue components.
- [ ] Step 2: Run `npm test -- --run <covering-test-paths>` from frontend; preserve the expected RED output for the report.
- [ ] Step 3: Implement API types/helpers and the shared option, then both existing send dialogs. Preserve blank recipients, explicit SMTP account selection, confirmation and failure feedback. The sent result links to the specific record. Configuration failure must never silently turn requested tracking on or treat unknown state as public-ready.
- [ ] Step 4: Add the records tab and detail dialog with loading/error/empty/pagination/manual refresh states. Show callback origin + scope warning, subject/masked-recipient/account/time/outcome and tracking state. Untracked, expired, failed and unknown messages need distinct states; absent callbacks are not “unread”. Detail shows ID, expiry, counts and latest classified events, not tokens. Query-string changes open the right detail, closing the dialog clears its query, and record refresh reflects new callbacks. Preserve campaign deliveries and their error/pagination behavior in the other tab.
- [ ] Step 5: Add tests for detail navigation/error, expired/failed/untracked/proxy/prefetch classifications, campaign tab preservation, disabled config and pagination/refresh. Run covering tests to green, then `npm test -- --run`, `npm run typecheck`, `npm run lint`, `npm run build`. Document actual UI actions. Parent agent owns deployed Edge/Mailpit QA.
- [ ] Step 6: Self-review, run `git diff --check`, commit with `feat: show mail callback status and tracking controls`, and write the report with RED/GREEN results, changed files, warnings and concerns.

## Final controller validation

Enable the new subsystem only in ignored local `.env` with an independent generated key, deploy updated API/frontend, and verify health. Run a new opt-in diagnostic and template test through local Mailpit, inspect actual MIME, request each actual pixel URL, and verify the correct record and classification. Test no-tracking, repeated, HEAD and malformed requests. Do not send to public recipients during this validation. Check Edge desktop/mobile, dialogs, deep links and browser console; save screenshots outside source. Run final branch review, full relevant regressions and compose contracts, merge/push to main under standing authorization, and report that prior emails cannot be tracked and local callbacks are not externally reachable.
