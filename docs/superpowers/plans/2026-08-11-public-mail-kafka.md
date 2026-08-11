# Public Mail Protocols and Kafka Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable secure public SMTP plus read-only IMAP/POP3 administration and replace RabbitMQ with Kafka in every runtime path.

**Architecture:** PostgreSQL outbox rows publish to versioned Kafka topics through Spring Kafka. Spring and Python consumers manually commit only after durable processing or dead-letter publication. SMTP keeps its campaign-compatible account model; inbound mailbox accounts use a separate encrypted model and bounded read-only header access through Angus Mail.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Kafka 4.1, Angus Mail, R2DBC PostgreSQL, Flyway, Python 3.12, aiokafka, Pydantic, Ray Core, Vue 3, TypeScript, Vitest, Apache Kafka 4.1 KRaft, Docker Compose.

## Global Constraints

- Remove runtime and test dependencies on RabbitMQ and `aio-pika`.
- Public SMTP/IMAP/POP3 must require STARTTLS or implicit TLS with hostname verification.
- Plain protocols remain restricted to exact local allowlisted hostnames.
- Credentials remain AES-GCM encrypted and never appear in API responses, Kafka messages, logs, or audit metadata.
- Inbound access is read-only; do not return bodies, download attachment content, mutate flags, or delete messages.
- Kafka uses explicit topics, manual commits, idempotent producers, bounded retries, and dead-letter topics.
- Campaign personalization remains draft-only and never sends automatically.
- New code, branch metadata, and commit messages must not contain the user-prohibited product name.

---

### Task 1: Kafka backend foundation and outbox publisher

**Files:**
- Modify: `backend/build.gradle`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/KafkaTopics.java`
- Replace: `backend/src/main/java/com/camel_hub/advertisement/messaging/ArxivMessagingConfiguration.java`
- Replace: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationMessagingConfiguration.java`
- Replace: `backend/src/main/java/com/camel_hub/advertisement/messaging/ArxivCommandPublisher.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/messaging/OutboxRepository.java`
- Create: `backend/src/main/resources/db/migration/V13__kafka_and_mailbox_accounts.sql`
- Replace tests: `backend/src/test/java/com/camel_hub/advertisement/messaging/ArxivMessagingConfigurationTest.java`
- Replace tests: `backend/src/test/java/com/camel_hub/advertisement/messaging/PersonalizationMessagingConfigurationTest.java`
- Replace tests: `backend/src/test/java/com/camel_hub/advertisement/messaging/ArxivCommandPublisherTest.java`

**Interfaces:**
- Consumes: pending `OutboxRepository.OutboxMessage` records.
- Produces: `KafkaTopics` constants, `KafkaTemplate<String, String>` publication, and version/header metadata.

- [ ] **Step 1: Write failing Kafka topology and publisher tests**

Assert `NewTopic` beans declare all eight topics with three partitions, and assert the publisher calls:

```java
kafka.send(argThat(record ->
    record.topic().equals(KafkaTopics.ARXIV_JOBS)
        && record.key().equals(message.id().toString())
        && header(record, "contractVersion").equals("1")));
```

- [ ] **Step 2: Run focused tests and verify AMQP production types make them fail**

Run: `./gradlew test --tests '*ArxivMessagingConfigurationTest' --tests '*PersonalizationMessagingConfigurationTest' --tests '*ArxivCommandPublisherTest'`

Expected: compilation failure because Kafka topic beans and `KafkaTemplate` publisher do not exist.

- [ ] **Step 3: Replace dependencies and implement Kafka publication**

Use `implementation 'org.springframework.boot:spring-boot-starter-kafka'` and `testImplementation 'org.springframework.boot:spring-boot-starter-kafka-test'`. Configure producers for string serialization, `acks=all`, and idempotence. Map legacy logical destinations to topic constants only in V13; all new inserts write final topic names.

- [ ] **Step 4: Run focused tests until green**

Run: `./gradlew test --tests '*ArxivMessagingConfigurationTest' --tests '*PersonalizationMessagingConfigurationTest' --tests '*ArxivCommandPublisherTest'`

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: migrate backend messaging to kafka"
```

### Task 2: Kafka backend result consumers

**Files:**
- Replace: `backend/src/main/java/com/camel_hub/advertisement/messaging/ArxivResultConsumer.java`
- Replace: `backend/src/main/java/com/camel_hub/advertisement/messaging/PersonalizationResultConsumer.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/KafkaDeadLetterPublisher.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/messaging/KafkaConsumerConfiguration.java`
- Replace tests: `backend/src/test/java/com/camel_hub/advertisement/messaging/ArxivResultConsumerTest.java`
- Create test: `backend/src/test/java/com/camel_hub/advertisement/messaging/PersonalizationResultConsumerTest.java`

**Interfaces:**
- Consumes: `ConsumerRecord<String, String>` and `Acknowledgment` from result topics.
- Produces: database side effects, explicit commits, bounded transient retries, and DLT records.

- [ ] **Step 1: Write failing listener tests**

Cover successful acknowledge, invalid-message DLT then acknowledge, persistence-constraint DLT then acknowledge, and unexpected failure propagation without acknowledge.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew test --tests '*ResultConsumerTest'`

Expected: compilation failure because consumers still require Rabbit `Message` and `Channel`.

- [ ] **Step 3: Implement Kafka listeners and error handler**

Listeners use `@KafkaListener`, block only within their isolated listener thread, and call `acknowledgment.acknowledge()` after success or durable DLT publication. `DefaultErrorHandler` retries unexpected failures five times with fixed backoff before publishing to the source topic's DLT.

- [ ] **Step 4: Run focused tests until green**

Run: `./gradlew test --tests '*ResultConsumerTest'`

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: consume kafka results safely"
```

### Task 3: Python Kafka transport and worker runtimes

**Files:**
- Modify: `worker/pyproject.toml`
- Modify: `worker/src/app/config.py`
- Create: `worker/src/app/messaging/kafka.py`
- Delete: `worker/src/app/messaging/rabbit.py`
- Modify: `worker/src/app/main.py`
- Replace: `worker/src/app/personalization/rabbit.py` with `worker/src/app/personalization/kafka.py`
- Modify: `worker/src/app/personalization/main.py`
- Replace tests: `worker/tests/test_rabbit.py` with `worker/tests/test_kafka.py`
- Modify: `worker/tests/test_config.py`
- Modify: `worker/tests/test_logging.py`
- Modify: `worker/tests/personalization/test_consumer.py`

**Interfaces:**
- Consumes: Kafka job records with manual commits.
- Produces: idempotent Kafka result records, retry records with due-time headers, DLT records, and heartbeats.

- [ ] **Step 1: Write failing transport tests**

Test `KafkaDeliverySettlement.settle(record, outcome)` for ACK commit, DEAD publish-to-DLT then commit, REQUEUE publish-to-retry with incremented retry count, and sixth failure DLT. Test result publishers use message IDs as keys and contract version headers.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `uv run pytest tests/test_kafka.py tests/test_config.py tests/personalization/test_consumer.py -q`

Expected: import failure because the Kafka adapter and settings do not exist.

- [ ] **Step 3: Implement aiokafka adapters and runtimes**

Use `AIOKafkaProducer(enable_idempotence=True)` and `AIOKafkaConsumer(enable_auto_commit=False, isolation_level='read_committed')`. Commit `{TopicPartition: offset + 1}` only after settlement. Use explicit group IDs and `auto_offset_reset='earliest'`.

- [ ] **Step 4: Run worker unit tests and static checks**

Run: `uv run pytest -q && uv run ruff check . && uv run mypy src tests`

- [ ] **Step 5: Commit**

```bash
git add worker
git commit -m "feat: migrate workers to kafka"
```

### Task 4: Public SMTP policy and inbound mailbox backend

**Files:**
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/smtp/SmtpPolicy.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxModels.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxProperties.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxPolicy.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxRepository.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxTransport.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxService.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxDtos.java`
- Create: `backend/src/main/java/com/camel_hub/advertisement/email/mailbox/MailboxController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/email/EmailConfiguration.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/common/api/GlobalExceptionHandler.java`
- Create tests under: `backend/src/test/java/com/camel_hub/advertisement/email/mailbox/`
- Modify test: `backend/src/test/java/com/camel_hub/advertisement/email/smtp/SmtpPolicyTest.java`

**Interfaces:**
- Consumes: encrypted IMAP/POP3 account configuration.
- Produces: mailbox CRUD, audited connection tests, and bounded `MailboxMessageHeader` previews.

- [ ] **Step 1: Write failing policy, service, transport, and controller tests**

Test public TLS acceptance, public plain rejection, IP-literal rejection, POP3 folder enforcement, password preservation on update, password redaction, permissions, connection categories, maximum 50 headers, masked sender, and no body/attachment bytes.

- [ ] **Step 2: Run mailbox tests and verify failure**

Run: `./gradlew test --tests '*SmtpPolicyTest' --tests '*Mailbox*Test'`

Expected: mailbox types are absent and public SMTP is still disabled by default configuration.

- [ ] **Step 3: Implement policy, persistence, transport, service, and API**

Use Angus `Store` with protocol-specific properties. Always open folders with `Folder.READ_ONLY`, inspect only the newest bounded range, and close folder/store in `finally`. Reuse `SmtpSecretCrypto` for encrypted secrets.

- [ ] **Step 4: Run focused tests until green**

Run: `./gradlew test --tests '*SmtpPolicyTest' --tests '*Mailbox*Test' --tests '*FlywayMigrationTest'`

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add secure public mail protocols"
```

### Task 5: Compose Kafka runtime and protocol test server

**Files:**
- Modify: `docker-compose.yml`
- Modify: `docker-compose.dev.yml`
- Modify: `.env.example`
- Modify: `scripts/verify-compose.sh`
- Modify: `scripts/verify-container-images.sh`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/system/RuntimeStatusProperties.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/system/RuntimeStatusController.java`
- Modify tests: `backend/src/test/java/com/camel_hub/advertisement/system/RuntimeStatusControllerTest.java`

**Interfaces:**
- Consumes: Kafka and mail protocol environment configuration.
- Produces: internal KRaft Kafka, topic initialization, local GreenMail, and non-secret runtime readiness.

- [ ] **Step 1: Update contract tests first and verify failure**

Require `kafka`, `kafka-init`, and `mail-test-server`; reject `rabbitmq`; require Kafka bootstrap variables in all three workers; assert runtime JSON exposes `kafkaConfigured` and `publicMailboxAllowed` without the old field.

Run: `bash scripts/verify-compose.sh && ./backend/gradlew -p backend test --tests '*RuntimeStatusControllerTest'`

Expected: failure because Compose and runtime still expose RabbitMQ.

- [ ] **Step 2: Implement official Kafka KRaft service and configuration**

Use `apache/kafka:4.1.2`, internal listener `PLAINTEXT://kafka:9092`, explicit KRaft controller listener, persistent `kafka-data`, and an initializer that creates all eight topics. Add an internal GreenMail service with SMTP 3025, IMAP 3143, IMAPS 3993, POP3 3110, and POP3S 3995.

- [ ] **Step 3: Run Compose and image contracts until green**

Run: `bash scripts/verify-compose.sh && bash scripts/verify-container-images.sh`

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml docker-compose.dev.yml .env.example scripts backend/src/main/resources backend/src/main/java/com/camel_hub/advertisement/system backend/src/test/java/com/camel_hub/advertisement/system
git commit -m "ops: run kafka and public mail protocols"
```

### Task 6: Frontend mail protocol workspace and Kafka readiness

**Files:**
- Modify: `frontend/src/modules/email/email.types.ts`
- Modify: `frontend/src/modules/email/email.api.ts`
- Create: `frontend/src/modules/email/MailAccountsView.vue`
- Modify: `frontend/src/modules/email/SmtpAccountsView.vue`
- Modify: `frontend/src/modules/email/__tests__/email.views.spec.ts`
- Modify: `frontend/src/modules/campaigns/campaigns.types.ts`
- Modify: `frontend/src/modules/campaigns/CampaignDetailView.vue`
- Modify: `frontend/src/modules/campaigns/__tests__/campaign.views.spec.ts`
- Modify: `frontend/src/modules/admin/SystemSettingsView.vue`
- Modify: `frontend/src/layouts/AppShell.vue`
- Modify: `frontend/src/router/index.ts`

**Interfaces:**
- Consumes: SMTP/mailbox APIs and Kafka runtime status.
- Produces: `/admin/mail-accounts`, compatibility redirect, protocol tabs, connection actions, and latest header preview.

- [ ] **Step 1: Write failing view and route tests**

Assert the sidebar targets `/admin/mail-accounts`, the old route redirects, SMTP banner reports public TLS enabled, mailbox forms emit protocol/TLS/folder payloads, preview renders masked senders only, and campaign readiness reads `kafkaConfigured`.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `npm test -- --run src/modules/email/__tests__/email.views.spec.ts src/modules/campaigns/__tests__/campaign.views.spec.ts src/layouts/__tests__/AppShell.spec.ts`

- [ ] **Step 3: Implement the mail workspace and readiness copy**

Keep both protocol panels lazy-loaded within one route, preserve current SMTP editor behavior, and never place passwords into reactive account views after a save.

- [ ] **Step 4: Run frontend tests, lint, typecheck, and build**

Run: `npm test -- --run && npm run lint && npm run typecheck && npm run build`

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat: add mail protocol workspace"
```

### Task 7: Documentation, deployment, and end-to-end acceptance

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/API.md`
- Modify: `docs/DEPLOYMENT.md`
- Modify: `docs/OPERATIONS.md`
- Modify: `docs/SECURITY_AND_PRIVACY.md`

**Interfaces:**
- Consumes: completed runtime and APIs.
- Produces: deployable instructions and acceptance evidence.

- [ ] **Step 1: Update current documentation**

Document Kafka topics, consumer groups, DLT inspection/replay, public mail TLS requirements, provider examples, inbound read-only scope, environment variables, and rollback through the PostgreSQL outbox. Historical design files remain historical and are not rewritten.

- [ ] **Step 2: Run the full automated suite**

Run in parallel:

```bash
./backend/gradlew -p backend clean test
cd worker && uv run pytest -q && uv run ruff check . && uv run mypy src tests
cd frontend && npm test -- --run && npm run lint && npm run typecheck && npm run build
bash scripts/verify-compose.sh && bash scripts/verify-container-images.sh && git diff --check
```

- [ ] **Step 3: Rebuild and deploy locally**

Render Compose with the existing private environment file, stop the old application stack, build changed images, start all services, and wait for health. Verify no RabbitMQ container is part of the project.

- [ ] **Step 4: Verify Kafka and protocol paths**

Describe all topics, submit an arXiv test command through the authenticated API, observe outbox publication and result consumption, test SMTP/IMAP/POP3 against the local protocol server, and verify no public mail is sent.

- [ ] **Step 5: Browser acceptance**

Use Edge to sign in, open the mail account workspace and system settings, exercise protocol tabs and safe connection tests, verify responsive navigation, and confirm no unexpected console errors.

- [ ] **Step 6: Compliance check, commit, and push**

Confirm the final tree, new commit messages, and branch metadata do not contain the prohibited name or API-key-shaped secrets. Commit documentation and push `main` only after all verification succeeds.
