# Test mail engagement reliability design

## Goal

Complete the production test-mail feedback loop without mixing it into campaign delivery data. A started SMTP attempt must reach a terminal persisted state even if the HTTP client disconnects, stale pending records must not remain pending forever, tracked test mail links must report classified click observations before redirecting, and the UI must describe public callback configuration and observed evidence truthfully.

## Scope

This change covers SMTP diagnostic sends and template test sends only. It does not add campaign sending, change campaign analytics, infer human reading, retry an uncertain SMTP attempt, or send a new external message during deployment validation. Existing open-image tokens and records remain valid.

## Approaches

The selected approach extends the dedicated `mail_send_records` subsystem with dedicated click links and events. This preserves the existing separation from campaign recipients and analytics while following the campaign schema's proven pattern of storing a server-side target URL rather than exposing it in a tracking query string.

Reusing `campaign_links`, `tracking_tokens`, and `tracking_events` was rejected because test sends have no real campaign recipient and would contaminate campaign reports. A stateless `?url=` callback was rejected because personalized or unsubscribe URLs could leak through proxy and infrastructure logs even when the application vhost disables access logs.

## Send outcome reliability

`MailTrackingService.send` continues to insert a `SENDING` record before SMTP begins. The transport and outcome-persistence pipeline becomes hot and cancellation-resistant only after the SMTP attempt is subscribed: once transport starts, a downstream HTTP cancellation cannot cancel the SMTP outcome write. Cancellation before the SMTP attempt starts still prevents sending.

Transport success persists `SMTP_ACCEPTED`. Definite transport failures persist `FAILED`; timeouts, disconnects, and unexpected failures persist `UNKNOWN`. Metadata or audit failures after SMTP remain non-fatal and never cause a resend.

A bounded reconciliation job periodically transitions `SENDING` records older than 15 minutes to `UNKNOWN` with `SEND_OUTCOME_MISSING`. This covers process termination and historical rows where no trustworthy result exists. It never promotes a row to `SMTP_ACCEPTED` based on an image or click callback. The existing production row will therefore become truthful `UNKNOWN`, while mailbox evidence remains separate proof of delivery.

## Click tracking data model

Flyway migration `V15` adds:

- `mail_click_links`: UUID primary key, send-record foreign key with cascade deletion, target URL, SHA-256 target hash, optional bounded label, one-based position, token hash, expiry, and creation time. A unique `(record_id, target_url_hash)` constraint reuses one link record for repeated identical targets in a message.
- `mail_click_events`: identity primary key, click-link foreign key with cascade deletion, occurrence time, the existing `UNCLASSIFIED/PREFETCH/IMAGE_PROXY/BOT` classification and rule reason, fingerprint hash, and minute bucket. A unique `(link_id, fingerprint_hash, minute_bucket)` constraint provides atomic one-minute deduplication.

`mail_send_records` list/detail aggregation gains raw and automated click counts plus first and last click timestamps. Detail responses also contain bounded link summaries and the latest 50 click observations. Management responses never contain callback tokens.

Only absolute `http` or `https` links without userinfo are trackable. Fragments, mailto links, template asset URLs, callback URLs, malformed links, and URLs longer than 2048 characters are left unchanged. The original target is stored server-side because the administrator authored the test message and needs to identify clicked links; callback access/error logs remain disabled. Plain text, saved template content, previews, subjects, and reply metadata remain unchanged.

## Click token and callback

The existing independent tracking HMAC key gains a separate domain-separated click-token format. A click token binds record UUID, link UUID, expiry, and random nonce. The database stores only its SHA-256 digest. Open tokens retain their existing format and behavior.

`GET /t/c/{token}` verifies the signature and expiry, resolves a matching link belonging to a tracked nonfailed record, records a classified observation on a fail-closed best-effort path, and returns `302 Found` to the stored target. Observation insertion failure does not break an already resolved redirect. `HEAD` resolves and redirects without counting. Other methods return `405` with `Allow: GET,HEAD`. Missing, malformed, altered, expired, unknown, and failed-record tokens return the same generic `404` response with no-store/no-referrer/nosniff headers and never reflect the token or target.

Click classification reuses the bounded mail callback classifier. `UNCLASSIFIED` means no known automation signal, not a confirmed person. The same Nginx `/t/` privacy and rate-limit boundary covers image and click callbacks.

## API and frontend

The existing `trackOpens` request field remains for backward compatibility but the UI labels the option “检测图片加载与链接点击（可选）”. When selected, both mechanisms are enabled for eligible HTML links. The status enum changes from the misleading `PUBLIC_HTTPS_UNVERIFIED` label to `PUBLIC_HTTPS_CONFIGURED`; this describes configuration only.

For a configured public HTTPS origin, the option displays “公网 HTTPS 回传已配置” and says actual availability is demonstrated only by recorded callbacks. The record panel displays “已收到公网回传” only when returned records contain an image or click event; otherwise it displays the configured-only explanation.

The list shows a compact click count alongside the existing image-load count. The detail dialog has separate image-load and link-click sections, target link summaries, automated counts, first/last timestamps, and recent click classifications. It never claims opened, read, or human clicked.

## Error handling and security

- No automatic SMTP resend is introduced.
- Stale reconciliation writes only `SENDING -> UNKNOWN` and is idempotent.
- Redirect targets come only from persisted, validated URLs; no caller-supplied redirect parameter is accepted.
- Invalid callback responses do not reveal whether a token, record, or target exists.
- Callback URLs and tokens are excluded from application, Nginx, and deployment-test output.
- Existing open-event retention cascades extend to click links and click events through the send-record foreign key.
- The production migration is additive and leaves existing open tokens and records intact.

## Validation

Backend TDD covers cancellation after SMTP starts, stale reconciliation, link rewriting and exclusions, distinct token domains, valid redirect, HEAD/no-count, method restrictions, malformed/altered/expired/failed tokens, storage-failure redirect continuity, concurrent deduplication, classification, permissions, migration constraints, and unchanged campaign tables.

Frontend TDD covers configured-public messaging, observed-public evidence, combined opt-in copy, click counts, link summaries, classified click events, and refresh behavior. Full backend and frontend suites, lint, typecheck, production builds, migration validation, and repository cleanliness are required before integration.

Production deployment uses the existing Compose project and domain-specific Nginx vhost. It creates a database backup and records the currently running image/commit before migration, changes no other vhost, deploys only affected services, waits for health, verifies the migration and stale-row reconciliation, then tests authenticated APIs, a synthetic tracked link through public HTTPS, privacy/rate-limit behavior, and the Edge record UI. No new external email is sent unless separately requested.
