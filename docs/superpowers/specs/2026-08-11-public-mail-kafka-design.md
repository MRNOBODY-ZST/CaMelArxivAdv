# Public Mail Protocols and Kafka Migration Design

## Goal

Enable configurable public SMTP, IMAP, and POP3 connections with mandatory transport security, and replace RabbitMQ with Kafka across the Spring API, arXiv worker, personalization worker, local Compose runtime, health reporting, tests, and operations documentation.

## Decisions

The migration is a clean broker replacement rather than a compatibility bridge. PostgreSQL remains the source of truth and the existing outbox remains the publication boundary. The stopped local RabbitMQ instance cannot provide a trustworthy drain checkpoint, so historical broker data is not replayed automatically. Unpublished outbox rows remain eligible for Kafka publication; database idempotency keys continue to protect result persistence from duplicate delivery.

SMTP remains the outbound account model referenced by campaigns. Public SMTP is enabled through configuration, but public destinations must use `STARTTLS_REQUIRED` or `TLS_IMPLICIT`; `PLAIN_LOCAL_ONLY` remains restricted to exact local allowlisted hosts. Existing AES-GCM password encryption, hostname verification, timeouts, audit events, optimistic locking, and rate limits remain mandatory.

Inbound mail uses a separate mailbox account model because SMTP and inbound hosts, ports, credentials, and folder behavior are independent. The supported protocols are `IMAP` and `POP3`, with `STARTTLS_REQUIRED` and `TLS_IMPLICIT`. Plain inbound connections are allowed only for exact local allowlisted hosts. POP3 is restricted to `INBOX`. The application opens mailboxes read-only and returns bounded message headers only; it does not download attachment bytes, mutate flags, delete remote messages, or persist message bodies.

## Kafka Topology

Kafka runs in single-node KRaft mode for local deployment using the official JVM image. It is internal-only and has no host-published broker port. Production can replace the bootstrap address with a managed multi-broker cluster without changing topic contracts.

| Topic | Producer | Consumer group | Purpose |
|---|---|---|---|
| `camel.arxiv.jobs.v1` | Spring outbox publisher | `camel-arxiv-workers-v1` | arXiv import, sync, and source extraction commands |
| `camel.arxiv.results.v1` | Python arXiv worker | `camel-backend-arxiv-results-v1` | progress, result, terminal, and heartbeat messages |
| `camel.arxiv.retry.v1` | Python arXiv worker | `camel-arxiv-retry-workers-v1` | bounded delayed retries |
| `camel.arxiv.dlt.v1` | Spring/Python consumers | operations only | permanently rejected arXiv records |
| `camel.mail.personalization.jobs.v1` | Spring outbox publisher | `camel-personalization-workers-v1` | per-campaign generation commands |
| `camel.mail.personalization.results.v1` | Python personalization worker | `camel-backend-personalization-results-v1` | structured generated drafts and failures |
| `camel.mail.personalization.retry.v1` | Python personalization worker | `camel-personalization-retry-workers-v1` | bounded delayed retries |
| `camel.mail.personalization.dlt.v1` | Spring/Python consumers | operations only | permanently rejected personalization records |

Topics have three partitions locally, replication factor one locally, explicit retention, and automatic creation disabled. A Kafka initializer creates and validates every topic. Producers use acknowledgments from all in-sync replicas and idempotent production. Consumers disable auto-commit and commit only after a successful side effect or durable dead-letter publication.

The Python retry topics store `camelRetryCount` and `camelNotBeforeEpochMs` headers. A retry consumer pauses until the record is due, republishes it to the corresponding job topic, and then commits the retry offset. Five attempts remain the upper bound. Permanent validation failures are copied to the matching dead-letter topic before the source offset is committed. This preserves the prior bounded retry behavior without pretending Kafka topics have RabbitMQ TTL semantics.

Spring listeners use manual immediate acknowledgments. Invalid contracts and persistence constraint failures are published to the matching dead-letter topic and acknowledged. Unexpected persistence failures are thrown so the configured error handler retries with bounded backoff before dead-lettering. Result handlers remain idempotent and unchanged at their domain boundary.

## Persistence and API

Flyway migration V13 renames `outbox_messages.exchange_name` to `topic_name`, converts existing logical destinations to versioned Kafka topic names, and adds `mailbox_accounts`. Mailbox accounts store protocol, host, port, TLS mode, username, encrypted password and nonce, folder, enabled state, last test metadata, optimistic lock version, and audit timestamps. Passwords and ciphertext are never returned by APIs.

New permissions are `mailbox:read` and `mailbox:manage`. They are granted to the super administrator, and mailbox read/manage are granted to the operations administrator alongside SMTP permissions.

Endpoints:

- `GET/POST /api/v1/mailbox-accounts`
- `GET/PUT/DELETE /api/v1/mailbox-accounts/{id}`
- `POST /api/v1/mailbox-accounts/{id}/test-connection`
- `GET /api/v1/mailbox-accounts/{id}/messages?limit=20`

The message endpoint returns remote UID, message-id, masked sender, normalized subject, sent date, received date when the protocol provides it, size, and attachment-presence metadata. Limits are 1 to 50 messages and 64 KiB of inspected headers per message. Header control characters are stripped. No body or attachment content is returned.

The SMTP account endpoints remain stable. The runtime status response replaces `rabbitConfigured` with `kafkaConfigured` and adds `publicMailboxAllowed`. The frontend updates labels, status cards, and campaign readiness checks accordingly.

## Frontend

The existing SMTP administration page becomes a mail protocol workspace with two tabs:

- Outbound SMTP: current CRUD, public TLS modes, connection test, and explicit diagnostic send.
- Inbound IMAP/POP3: mailbox CRUD, connection test, and on-demand latest-header preview.

The sidebar label becomes “邮件账户” while keeping the existing SMTP route as a redirect for bookmarks. The new canonical route is `/admin/mail-accounts`. UI copy must clearly distinguish connection testing from sending and must warn that viewing headers contacts the configured public mailbox.

## Security Boundaries

- Public protocols require certificate and hostname verification; trust-all and TLS downgrade are unsupported.
- Plaintext is local allowlist only.
- Credentials remain AES-GCM encrypted and are never logged, audited, returned, or placed on Kafka.
- Mailbox access is read-only and bounded; no attachment payload, body, or remote mutation is in scope.
- SSRF policy rejects empty hosts, IP literals for public accounts, URL syntax, trailing dots, control characters, and non-TLS public modes.
- Diagnostic SMTP sends remain explicit administrator actions. Campaign draft generation still never sends mail automatically.
- Kafka is internal-only. Production authentication and TLS are configurable through standard client properties without embedding secrets in source control.

## Error Handling and Observability

Mail transport failures use protocol-neutral categories for authentication, timeout, DNS, TLS, connection rejection, protocol rejection, and unexpected failure. Connection tests record only categories. Kafka record logs contain topic, partition, offset, message ID, job ID, and trace ID but never payloads or credentials. Runtime status reports only booleans and non-secret topic/provider identifiers.

## Verification

Backend tests cover SMTP public policy, mailbox policy, password rotation, API permissions, connection and header mapping, Flyway V13, Kafka topic declarations, outbox publication, manual acknowledgement, bounded retry, dead-letter behavior, and runtime status. Python tests cover Kafka producer headers, manual offset commits, retry routing, DLT routing, graceful shutdown, and settings validation. Frontend tests cover the two protocol tabs, CRUD payloads, preview behavior, Kafka readiness copy, and route compatibility.

Local acceptance requires all containers healthy, RabbitMQ absent, Kafka topic descriptions correct, Spring and both Python workers connected, an outbox command traversing Kafka end to end, and SMTP/IMAP/POP3 protocol tests against local test services. Public credentials are not required for acceptance and no public diagnostic message is sent automatically.
