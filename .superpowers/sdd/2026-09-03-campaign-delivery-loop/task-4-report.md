# Task 4 Report: Production Campaign Engagement Callbacks

## Status

DONE. The implementation provides
domain-separated production open, click, and unsubscribe capabilities; deterministic
send-time rendering; safe callback routing across test-mail and campaign namespaces;
privacy-minimal event capture; atomic global unsubscribe; and a fail-closed management
read path that never returns durable callback capabilities.

The implementation starts from Task 3 commit `80b6054`. It does not enable public
Nginx callback routing, perform a real external-author send, or deploy production
configuration; those remain Task 8 concerns.

## TDD evidence

### Initial RED

The Task 4 signer, renderer, callback namespace, repository, and unsubscribe tests were
introduced before their production implementations. The brief's focused suite first
failed on the missing campaign tracking types and callback integration.

### Review-driven RED / GREEN cycles

Every security correction below was first reproduced by a focused failing regression:

- Preloaded campaign or test-mail capabilities in draft subjects, body text, HTML text,
  links, or arbitrary attributes survived the original prepare path. The regression
  includes cross-recipient tokens, nested percent encoding, and HTML entities.
- An unsubscribe placeholder nested in an attacker query, URI-like colon context, or
  URL-path parenthesis could exfiltrate the generated token. Standalone punctuation is
  now context-aware; normal `Stop ({{unsubscribe_url}})` rendering and retry remain valid.
- Frozen retries originally trusted any mutually matching origin and counted ordinary
  external `/t/o/`, `/t/c/`, or `/u/` paths as callbacks. They now require the canonical
  configured origin and cryptographically valid campaign capability shapes without
  rejecting ordinary external routes.
- A frozen subject or an unfrozen generated draft containing a raw, percent-encoded, or
  HTML-entity capability could leak through the recipient API. The public redactor now
  scans subject, HTML, and text independently of attempt/token-row state and fails
  closed at its bounded decoding limit.
- Test-mail persisted click targets bypassed the campaign redirect policy. One shared
  final controller policy now rejects unsafe schemes, userinfo, controls, port zero,
  callback self-loops, and normalized or encoded template-asset aliases for every
  namespace. Send-time rewriters use the same safety policy.
- Campaign uppercase-scheme links exposed a mismatch with the V3 lowercase database
  constraint. Test-mail behavior remains case-insensitive, while campaign persistence
  deliberately accepts only canonical lowercase HTTP(S) links.
- SMTP summaries failed to redact valid bare open, click, and unsubscribe capabilities
  followed by common punctuation. Exact token-alphabet boundaries now redact all three
  domains without persisting or reporting the capability.
- Active `CONNECTING` callbacks, stale/orphan callbacks, HEAD open requests, and click
  HEAD requests gained direct controller/repository coverage. Only a current leased
  attempt is accepted during the SMTP handoff window; HEAD never records an event.
- Unsubscribe audit rows claimed a recipient state transition that does not occur for
  accepted mail. Audit output now reports the actual unsubscribe, suppression, and
  unsent-recipient effects and preserves stronger pre-existing suppression reasons.
- A frozen retry delayed beyond token TTL became permanently undeliverable. Rotation is
  now allowed only after the immediately preceding explicit retryable SMTP 4xx reject;
  old artifacts are fully verified, replaced transactionally, and invalidated while
  Message-ID, correlation, content, and link targets stay stable. Mixed expiry,
  corrupted digest, invalid provenance, and final lease-fence rollback all fail closed.
- A token that was valid at prepare start could expire during the active delivery lease.
  Preparation state now carries the locked lease expiry, and every frozen capability
  must remain valid strictly beyond that complete SMTP window; otherwise the same safe
  provenance-gated rotation is required before SMTP.

## Final verification

Focused Task 4 command:

```bash
cd backend
./gradlew --no-daemon test \
  --tests 'com.camel_hub.advertisement.campaign.tracking.*' \
  --tests 'com.camel_hub.advertisement.campaign.delivery.CampaignTrackingDeliveryRetryIntegrationTest' \
  --tests 'com.camel_hub.advertisement.email.tracking.MailTrackingApiIntegrationTest' \
  --tests 'com.camel_hub.advertisement.email.tracking.MailOpenPrivacyTest' \
  --tests 'com.camel_hub.advertisement.email.tracking.MailTrackingPropertiesTest' \
  --tests 'com.camel_hub.advertisement.email.smtp.*Test'
```

Fresh serial result and JUnit XML aggregation:

```text
BUILD SUCCESSFUL in 57s
tests=193
failures=0
errors=0
skipped=0
```

Full backend command:

```bash
cd backend
./gradlew --no-daemon test
```

Fresh serial result and JUnit XML aggregation:

```text
BUILD SUCCESSFUL in 2m
suites=115
tests=602
failures=0
errors=0
skipped=0
```

Static verification:

```bash
git diff --check
# no output
```

Independent final review returned specification PASS and quality/safety PASS with no
P0, P1, or P2 findings. The reviewer independently ran a 219-test focused suite and
the full 602-test backend suite; both passed, and `git diff --check` was clean. The
expiry-rotation safety sub-audit also returned PASS with no findings.

## Implementation summary

- `CampaignTrackingSigner` uses independent HMAC domains for production open, click,
  and unsubscribe tokens. Verification returns only canonical IDs and expiry, rejects
  namespace crossover, malformed values, altered signatures, and expired tokens, and
  offers an internal signature-preserving expiry inspection only for safe retry repair.
- `CampaignTrackingService` implements the Task 3 `CampaignOutboundPreparer`: it renders
  the single unsubscribe capability into both bodies, persists digest-only token rows,
  creates deterministic campaign links, rewrites eligible links in stable target order,
  appends at most one open pixel, installs RFC 8058 headers, and persists all final
  content before SMTP under the active lease transaction.
- Valid frozen retries are byte-stable. Expired or lease-horizon-insufficient artifacts
  rotate only with an immediate retryable `SMTP_REJECTED` 4xx provenance. The original
  signed structure, digest rows, expiry agreement, canonical origin, recipient/link
  binding, and redirect targets are validated before exact replacement. Delete, insert,
  content persistence, and a fresh-clock lease fence share one transaction.
- `CampaignRedirectTargetPolicy` centralizes callback redirect validation. Both the
  legacy test-mail path and campaign namespaces validate the final resolved target
  before building `Location`; send-time rewriters reject targets that callbacks would
  reject, including encoded controls, self-loops, and template-asset paths.
- Existing `/t/o/{token}` and `/t/c/{token}` controllers resolve the test-mail namespace
  first and then each campaign namespace without distinguishable failure output. Open
  responses always return the generic pixel; click failures are generic no-store 404s.
  HEAD resolves safely where needed but never records engagement.
- Production events validate token type/link semantics against stored digests, accept
  delivered/unknown/bounced recipients plus the narrow active SMTP lease window, store
  privacy-reduced fingerprints/classification, deduplicate the same fingerprint per
  minute, and update first-open/first-click timestamps only on valid observations.
- `/u/{token}` provides no-store/referrer-protected confirmation and one-click POST
  handling. The atomic transaction serializes by email HMAC, revalidates the token,
  records unsubscribe evidence, creates or reactivates global suppression without
  weakening stronger reasons, marks only unsent production recipients, leaves safety
  rows untouched, and records truthful aggregate effects in the audit log.
- Campaign recipient responses redact frozen bodies regardless of lost artifact rows.
  They also detect capabilities in unfrozen generated drafts, subjects, arbitrary HTML
  attributes, encoded text, and configured-origin callback paths. Bounded normalization
  fails closed instead of returning deeply encoded content.
- Configuration remains disabled by default and validates canonical public origin,
  key material, token TTL, retry/lease compatibility, and profile wiring. API and worker
  configurations both receive the production preparer only when explicitly enabled.

## Changed files

Production additions:

- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignCallbackNamespace.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignLinkRewriter.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignPublicContentRedactor.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignRedirectTargetPolicy.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingRepository.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingService.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingSigner.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/tracking/CampaignUnsubscribeController.java`

Production modifications:

- `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignRepository.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/CampaignService.java`
- `backend/src/main/java/com/camel_hub/advertisement/common/api/RequestContextSupport.java`
- `backend/src/main/java/com/camel_hub/advertisement/common/security/SecurityConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/email/smtp/SmtpTransportException.java`
- `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailClickController.java`
- `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailLinkRewriter.java`
- `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailOpenController.java`
- `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingProperties.java`
- `backend/src/main/java/com/camel_hub/advertisement/email/tracking/MailTrackingService.java`

Tests:

- `backend/src/test/java/com/camel_hub/advertisement/campaign/CampaignApiTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignTrackingDeliveryRetryIntegrationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignLinkRewriterTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingApiIntegrationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingConfigurationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingDatabaseTestSupport.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignTrackingSignerTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/tracking/CampaignUnsubscribeIntegrationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/email/smtp/SmtpTransportTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailOpenPrivacyTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailTrackingApiIntegrationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/email/tracking/MailTrackingPropertiesTest.java`

## Remaining non-blocking risks

- A continuously paused campaign can defer a retry without a finite bound. The safe
  explicit-4xx rotation path prevents expiry from making that recipient permanently
  undeliverable while never rotating accepted, unknown, bounced, or ambiguous outcomes.
- Callback observation intentionally uses privacy-reduced fingerprints and a one-minute
  dedupe window. It is operational engagement telemetry, not proof that a human read a
  message; proxy/image-prefetch classification is retained for reporting.
- Production proxy no-log behavior, TLS/domain routing, and external callback smoke tests
  remain part of the deployment task. Application responses already set the required
  no-store and referrer protections.

No V16 migration, Task 2 workflow transitions, safety-run sender, inbound mailbox
processing, frontend reporting, public Nginx configuration, or deployment scripts were
changed in this task.
