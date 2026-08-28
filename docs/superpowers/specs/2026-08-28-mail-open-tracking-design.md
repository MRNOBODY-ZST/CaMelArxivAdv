# Mail open callback design

## Goal and evidence

The SMTP diagnostic and template-test send paths emit no open pixel and do not create delivery rows. Existing campaign reports read campaign-only tables; `/t/o/invalid-token` currently returns 404. An IMAP connection can read mailbox metadata, but cannot prove the recipient read an outbound message. Older messages cannot be retroactively instrumented.

This is an architectural addition spanning the two existing test-send paths, a public callback and an authenticated record view. The user approved automatic execution. It does not authorize a public web deployment or contact-list mailing.

## Approach and alternatives

Implement signed open pixels and dedicated test-send records. This works with both current send paths without inventing campaign recipients or contaminating campaign statistics. Reusing `campaign_recipients` would require fictitious contact/campaign relationships. IMAP Seen flags and requested read receipts are not reliable general outbound tracking mechanisms. Link-click rewriting and formal campaign sending are outside this change.

## Global constraints

- Keep all existing user data and credentials; never commit secrets or put credentials/tokens in logs.
- Do not publish a public web service, create a tunnel, or send to paper authors.
- Keep test-send records separate from campaign delivery and analytics tables.
- Tracking is opt-in per message (`trackOpens`, default false); global tracking defaults disabled.
- UI uses “检测到图片加载 / 估算打开”, never a definitive human-read claim.
- Use existing Vue/Tailwind design-skill components and preserve current permissions/navigation.
- Do not include the assistant product name in source, branch names or commit messages.

## Backend design

Create `email/tracking` with properties, signer, classifier, repository, service, and controllers. Add a Flyway migration for `mail_send_records` and `mail_open_events`; do not change historical migrations. A record ID equals the existing correlation UUID. Store only the masked recipient (first local-part character plus stars and domain), subject, source, timestamps, account reference, actor reference, tracking flag, expiry and token hash. Do not store a second plaintext recipient address or message body.

Both diagnostic and template-test sends always create a record before attempting SMTP. Sources are `SMTP_DIAGNOSTIC` and `TEMPLATE_TEST`. Outcomes are `SENDING`, `SMTP_ACCEPTED`, `FAILED`, `UNKNOWN`; timeouts/disconnects and uncertain outcomes must not be called delivered. No automatic retry is added. SMTP transport success must not be turned into a definite SMTP failure because a subsequent audit or persistence operation failed. Existing test responses remain compatible; correlationId identifies the record.

Only explicit `trackOpens=true` inserts a pixel into the final HTML, after template rendering/sanitization. Text bodies and template preview/version content remain unchanged. Repeated sends receive different tokens. Disabled global tracking rejects an explicit tracking request before SMTP. The signer uses an independent Base64 32-byte-or-longer HMAC-SHA256 key, opaque message ID, random nonce and expiry; comparison is constant-time and database storage is token hash only. Configuration: `TRACKING_ENABLED=false`, `TRACKING_PUBLIC_BASE_URL` falling back to `PUBLIC_BASE_URL`, `TRACKING_SIGNING_KEY_BASE64`, `TRACKING_TOKEN_TTL=PT720H`. When enabled, an absent/invalid key fails configuration. Callback base is a validated absolute origin, no userinfo/query/fragment/path; HTTP is allowed only for local/private hosts, otherwise HTTPS. Never derive callback origin from Host headers.

`GET /t/o/{token}` is anonymous and always returns the same transparent GIF with no-store/no-cache, no-referrer and nosniff headers, including malformed, altered, expired, disabled and unknown tokens. HEAD returns the GIF headers without counting an event. Other methods cannot count. Only valid, unexpired tokens linked to nonfailed records can record. Pending/unknown records may receive a callback because arrival can race SMTP completion. Never mark SMTP accepted from the image callback. Exclude capability paths from access/error logging at nginx and avoid printing caught exceptions containing request URLs. Add a bounded nginx request rate for callback requests.

Store observed events, not confirmed reads. Classify known prefetch headers as `PREFETCH`, recognized image proxies as `IMAGE_PROXY`, known bot/scanner agents as `BOT`, otherwise `UNCLASSIFIED`. Do not label an unknown request likely-human. Store rule reason and a digest of bounded UA/classification for deduplication, not full UA or IP. Atomically deduplicate the same record/fingerprint within a one-minute bucket, enforced by a unique index. Counters count these stored events, not every network retry. Indexed record/time queries provide raw event count, automated/proxy event count, first/last image request time. Event detail is bounded to the latest 50 events. Token expiry is 30 days by default; document record/event retention and provide a narrowly scoped maintenance command rather than introducing a scheduler in this change.

Authenticated APIs, requiring `smtp:read` (records/detail) and either `smtp:read` or `template:read` (configuration):

```ts
type TrackingStatus = {
  enabled: boolean; callbackBaseUrl: string;
  callbackScope: 'LOCAL_ONLY' | 'PUBLIC_HTTPS_UNVERIFIED';
  tokenTtlSeconds: number;
}
type MailSendRecord = {
  id: string; source: 'SMTP_DIAGNOSTIC' | 'TEMPLATE_TEST';
  recipientMasked: string; subject: string; smtpAccountName: string | null;
  status: 'SENDING' | 'SMTP_ACCEPTED' | 'FAILED' | 'UNKNOWN';
  failureCategory: string | null; trackingEnabled: boolean;
  createdAt: string; completedAt: string | null; trackingExpiresAt: string | null;
  rawOpenCount: number; automatedOpenCount: number;
  firstOpenAt: string | null; lastOpenAt: string | null;
}
type MailOpenEvent = {
  id: number; occurredAt: string;
  classification: 'UNCLASSIFIED' | 'PREFETCH' | 'IMAGE_PROXY' | 'BOT';
  reason: string;
}
// GET /api/v1/mail-tracking/status -> TrackingStatus
// GET /api/v1/mail-send-records?page=1&pageSize=20 -> existing PageResponse<MailSendRecord>
// GET /api/v1/mail-send-records/{id} -> { record: MailSendRecord; events: MailOpenEvent[] }
// Existing SMTP/template test-send bodies gain trackOpens?: boolean.
```

No token or pixel URL is returned in management APIs: viewing records must never itself generate opens. Callback scope describes configuration, not verified public reachability. Local/private IPs, loopback, single-label names and `.local` remain LOCAL_ONLY even with HTTPS.

## Frontend design

Extend `/email/deliveries` with an initially selected “测试邮件与回传” tab and preserve the existing “活动发送” view. Use existing Ds* table/card/button/badge/alert/pagination components. Show callback origin and its local-only/unverified warning, a refresh action, masked recipient/subject, SMTP outcome, tracking state, raw events and first/last callback. A detail dialog shows the correlation ID, expiry and recent classified events. Distinguish disabled, expired, pending, failed, unknown and observed-image states. No events never means unread. Historical empty state explains that pre-feature emails cannot be tracked. Do not insert tokens as visible images or links.

Add a reusable opt-in “记录估算打开（图片回传）” control to both SMTP diagnostic and template test dialogs. It starts unchecked and resets per dialog. Load server configuration; unavailable/disabled configuration disables tracking, not untracked sends. Show local callback warning and privacy caveat next to the option. Successful sends link directly to the record via `/email/deliveries?record=<correlationId>`; retain no-default-recipient and explicit SMTP account safeguards. Records refresh manually and when the user returns to the page; no fabricated synthetic “already opened” state.

## Validation and operations

Backend tests exercise signer alteration/expiry/randomness, local URL validation, real PostgreSQL send/event storage, failure/pending/disabled behavior, concurrent deduplication, permission boundaries, identical invalid pixel responses, HEAD and no-store behavior. The SMTP and template paths each prove the transmitted HTML contains a pixel only on opt-in, without changing text, subject or rendering preview. Existing test fixtures are adapted to additive request parameters.

Frontend tests cover payload opt-in, reset/disabled config, separate campaign tab, list/detail/refresh/pagination/error/empty states and truthful labels. Local Mailpit delivery provides actual received MIME; request its actual pixel URL and confirm the related record changes while unrelated records/campaign metrics do not. Exercise malformed tokens, HEAD, prefetched and repeated requests. Use Edge for desktop/mobile, dialog, navigation, console and screenshot checks. Do not claim a synthetic QA callback came from the user opening the prior 163 message.

Document that external mailbox image proxies cannot reach localhost, a configured HTTPS origin still needs external reachability verification, blocked/cached images can cause missed events, and privacy prefetching can create false positives. Apple describes background remote-content downloads independent of engagement: https://www.apple.com/legal/privacy/data/en/mail-privacy-protection/ .

Before handoff run covering and full regression tests, build/deploy the local API/frontend, verify health, review changes, merge into main and push under the standing user request. Report any SMTP authentication or public callback limitation separately from a successful local callback test.
