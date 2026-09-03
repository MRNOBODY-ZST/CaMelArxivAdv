# Task 3 Report: Production Campaign Delivery Worker

## Status

DONE. The implementation provides a disabled-by-default, profile-isolated production
campaign delivery worker with short transactional claims, combined production/safety
rate accounting, stable message identity, bounded retry, conservative unknown-outcome
handling, privacy-minimal Kafka wake-ups, and stage-aware SMTP results.

Task 3 deliberately does not provide a production `CampaignOutboundPreparer`. Runtime
delivery beans require both `app.campaign-delivery.enabled=true` and a real reactive
preparer bean, so final tracking and unsubscribe rendering cannot be bypassed before
Task 4 supplies that implementation.

## TDD evidence

### Initial RED

The focused Task 3 tests were written before the delivery types and detailed SMTP
contract existed. The brief's focused command failed during test compilation on the
missing worker/repository/listener interfaces and structured SMTP outcome API.

```bash
cd backend
./gradlew test \
  --tests com.camel_hub.advertisement.email.smtp.SmtpTransportTest \
  --tests 'com.camel_hub.advertisement.campaign.delivery.*' \
  --tests com.camel_hub.advertisement.MailWorkerProfileIsolationTest
```

### Review-driven RED / GREEN cycles

Each contract correction was covered by a failing test before its implementation.
Representative observed RED runs included:

- `CampaignDeliveryListenerTest.waitsForDurableSettlement...` rejected the original
  timed blocking path because it could cancel a claimed send before durable settlement.
- Worker isolation exposed an unwanted `PasswordEncoder` in `mail-worker` and the lack
  of worker-side SMTP/contact-crypto/DLT wiring.
- SMTP tests exposed DATA-final-response stage ambiguity, corrupt credential handling,
  nested Jakarta Mail exceptions, authentication/TLS classification, unsafe response
  echo text, protected-header injection, and Message-ID ordering/reuse defects.
- A 47-test repository/executor/scheduler run failed three new assertions: Task 2/3
  latest-evidence drift, unbounded eligibility reconciliation, and an exhausted third
  4xx attempt reporting a retry result despite the recipient being terminal.
- A subsequent 63-test run failed five intended assertions, adding bounded maintenance
  operations and conservative same-address `OUTCOME_UNKNOWN` handling.
- The starvation regression failed while a lower-ordered campaign containing a blocked
  same-HMAC recipient prevented an unrelated eligible campaign from being claimed.

The final fixes made those tests green by aligning send-time evidence with Task 2's
latest matching mapping, returning the persisted recipient outcome from settlement,
bounding every scheduled reconciliation, explicitly acquiring campaign then recipient
locks, and filtering blocked HMACs at both candidate levels while retaining the locked
final guard.

## Final verification

Focused command:

```bash
cd backend
./gradlew test \
  --tests com.camel_hub.advertisement.email.smtp.SmtpTransportTest \
  --tests 'com.camel_hub.advertisement.campaign.delivery.*' \
  --tests com.camel_hub.advertisement.MailWorkerProfileIsolationTest \
  --tests com.camel_hub.advertisement.messaging.DeliveryMessagingConfigurationTest \
  --no-daemon
```

Final serial result:

```text
BUILD SUCCESSFUL in 27s
```

Full backend command:

```bash
cd backend
./gradlew test --no-daemon
```

Final serial result and fresh JUnit XML aggregation:

```text
BUILD SUCCESSFUL in 1m 21s
tests=514
failures=0
errors=0
skipped=0
```

Static verification:

```bash
git diff --check
# no output
```

An independent final audit rated both specification conformance and quality/safety as
PASS, with no P0 or P1 findings.

## Implementation summary

- `SmtpTransport` preserves `send(...)` and the seven-argument `OutboundMessage`
  constructor while adding detailed stage/code/safe-summary outcomes. A tracked Angus
  transport distinguishes connect, EHLO, STARTTLS, authentication, MAIL FROM, RCPT TO,
  DATA, and post-DATA uncertainty; nested `MessagingException` chains are inspected.
- MIME creation uses multipart alternative content, installs the stable RFC Message-ID
  after `saveChanges()`, and accepts only validated `List-Unsubscribe` and
  `List-Unsubscribe-Post` extension headers. Protected/unknown headers and CR/LF input
  are rejected.
- SMTP response summaries are control-free, credential/token/URL/address redacted, and
  capped at 500 characters. Raw responses, addresses, message bodies, credentials, and
  callback tokens are not logged, persisted, or emitted to Kafka.
- `CampaignDeliveryRepository` acquires campaign, recipient, SMTP-account, and cooldown
  locks in a consistent order. It commits attempt and lease state before any network
  I/O and fences acceptance, failure, and stale-lease settlement with the lease digest.
- Capacity is serialized on the SMTP account and counts only `CONNECTING` and
  `SMTP_ACCEPTED` attempts across production and safety tables for minute, hour, day,
  and destination-domain-hour windows. A denial creates no attempt and defers to the
  latest release boundary among every saturated window.
- Explicit pre-acceptance SMTP 4xx responses are the only automatic retry source, at
  +1 minute and +5 minutes, with attempt three terminal. Explicit 5xx, authentication,
  TLS, configuration, rendering, crypto, and other deterministic pre-DATA failures are
  permanent. Any uncertain result after DATA is terminal `OUTCOME_UNKNOWN`.
- RFC Message-ID and correlation values remain stable across retries. Cooldown rows use
  a non-active conflict-safe sentinel and only SMTP acceptance updates the 180-day
  timestamp. An unresolved outcome conservatively blocks the same HMAC until an
  administrator resolves it without starving unrelated work.
- Eligibility is rechecked under the claim transaction, including campaign/safety
  state, current SMTP and IMAP readiness, active nondeleted syntax-valid contact,
  recipient HIGH confidence, exact paper-author relation, latest matching successful
  human-confirmed HIGH evidence, suppression, unsubscribe, exclusion, content, and
  cooldown state. First send requires approved unsubscribe placeholders; a Task 4
  finalized snapshot remains retry-compatible.
- Scheduled activation, canceled-row settlement, expired leases, ineligible recipients,
  and campaign completion are each bounded by the configured batch size. Paused work is
  not claimed; cancellation does not overwrite work already handed to SMTP.
- The delivery Kafka contract carries only version, opaque message/campaign identifiers,
  action, trace identifier, and creation time. Unknown fields and malformed commands
  are rejected; DLT publication rebuilds a fixed redacted envelope. Manual acknowledge
  occurs only after durable settlement/no-work or successful safe DLT publication.
- The `mail-worker` profile explicitly wires database transaction support, contact and
  SMTP cryptography/policy/transport, Kafka DLT infrastructure, and readiness health,
  while excluding identity/business/controller configuration. Executor, listener, and
  scheduler beans are absent unless both delivery enablement and a real preparer exist.

## Changed files

Production:

- `backend/src/main/java/com/camel_hub/advertisement/email/smtp/SmtpTransport.java`
- `backend/src/main/java/com/camel_hub/advertisement/email/smtp/SmtpTransportException.java`
- `backend/src/main/java/com/camel_hub/advertisement/messaging/KafkaTopics.java`
- `backend/src/main/java/com/camel_hub/advertisement/messaging/OutboxRepository.java`
- `backend/src/main/java/com/camel_hub/advertisement/messaging/DeliveryMessagingConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryRepository.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignOutboundPreparer.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryExecutor.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryScheduler.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryListener.java`
- `backend/src/main/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryWorkerConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/arxiv/config/ArxivConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/common/security/SecurityConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/identity/config/IdentityConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/job/config/JobConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/messaging/ArxivMessagingConfiguration.java`
- `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationMessagingConfiguration.java`
- `backend/src/main/resources/application-mail-worker.yaml`

Tests:

- `backend/src/test/java/com/camel_hub/advertisement/email/smtp/SmtpTransportTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryRepositoryIntegrationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryConcurrencyIntegrationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryExecutorTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliverySchedulerTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryListenerTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/campaign/delivery/CampaignDeliveryWorkerConfigurationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/messaging/DeliveryMessagingConfigurationTest.java`
- `backend/src/test/java/com/camel_hub/advertisement/MailWorkerProfileIsolationTest.java`

## Remaining non-blocking risks

- Large installations should add compound/partial indexes tailored to the same-HMAC
  `CONNECTING`/`OUTCOME_UNKNOWN` guard and the rolling 24-hour attempt scan. This is a
  scale optimization; it does not affect the bounded 20-message safety run or
  transactional correctness.
- Global eligibility reconciliation intentionally stores a conservative
  `EVIDENCE_INVALID` reason for several non-deliverable categories. The direct claim
  path has more precise internal categories; a later reporting task may improve the
  persisted diagnostic reason without changing eligibility.
- Campaign selection is deterministic by UUID and a capacity-denied claim ends one pump
  with no work. Under very large multi-campaign load this can reduce fairness, although
  the one-second scheduler and current small-batch rollout remain correct and safe.

No V16 migration, Task 2 workflow behavior, production tracking callbacks, safety-run
sender, inbound mailbox processing, frontend, or deployment files were changed in this
task.
