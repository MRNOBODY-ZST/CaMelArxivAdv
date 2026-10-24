# CaMel arXiv Outreach Platform

Paper discovery, evidence-based contacts, and auditable research outreach.

[Quick Start](#quick-start) · [Features](#features) · [Architecture](#architecture) · [Development](#development) · [Documentation](#documentation) · [中文文档](README.zh-CN.md)

CaMel brings arXiv discovery, metadata import, bounded TeX Source extraction, contact evidence, personalized email drafts, campaign delivery, and analytics into one administrative platform. A Vue interface manages the workflow; Spring Boot services own authorization and durable state; Python workers and Ray handle asynchronous research-data and draft-generation jobs.

The platform supports both individually reviewed campaigns and explicitly authorized automated outreach. Finding a public address is not proof of authorship, permission to contact someone, or confirmation that an address can receive mail.

## Features

### Paper discovery and research data

- Search official arXiv metadata by research phrase, category, author, and date; preview results and save reusable searches.
- Import selected papers or bounded search results, synchronize taxonomy and OAI-PMH metadata, and retain paper versions and import provenance.
- Follow asynchronous jobs through progress, events, errors, and worker-heartbeat information.
- Explore the paper library, author relationships, and ingestion, paper, and contact analytics.

### Source extraction and contact evidence

- Inspect supported TeX/source archives from fixed official HTTPS endpoints without compiling TeX or executing archive contents.
- Apply shared request pacing (at least three seconds between official requests), redirect checks, archive/path/size limits, parsing deadlines, and temporary-file cleanup.
- Extract publicly stated contact evidence with confidence and author-mapping information; keep machine findings distinct from human verification.
- Encrypt contact values, deduplicate with an independent HMAC key, mask ordinary listings, and audit authorized single-contact disclosure.
- Do not guess email addresses, crawl unrelated websites, or probe SMTP deliverability.

### Templates and personalized drafts

- Edit rich HTML and plain-text alternatives, preview server-rendered content, manage immutable versions, and use private image assets.
- Generate paper-grounded drafts through OpenAI-compatible Responses or Anthropic-compatible Messages integrations, with validated structured output and a separate Ray-backed worker.
- Configure provider, model, gateway, and credentials through permission-controlled model settings.
- Keep recipient email fields and SMTP credentials out of the model-input contract. Generating a draft does not itself invoke SMTP.
- Use the supplied research-invitation fixtures to emphasize registration and free trial credits for eligible new users, subject to the provider's current terms. Optional research support requires separate agreement; no fixed credit amount is promised.

### Campaigns and mail operations

- Manage recipient segments, selected-paper campaigns, sender accounts, recipient-specific content, preflight checks, review, approval, and delivery.
- Freeze campaign content and recipient selections; use database coordination, bounded retries, quota checks, unsubscribe, suppression, and cooldown rules.
- Configure SMTP and read-only IMAP/POP3 previews, send explicit diagnostic messages, and inspect delivery/preparation diagnostics.
- Keep normal delivery, controlled safety sends, test-mail records, inbound feedback, and tracking outcomes distinguishable.
- Copy eligible unsent selections into independent HTML drafts through the draft-only template-migration workflow, without changing sent history or automatically starting a campaign.

### Automated and weekly outreach

Choose a sender, template, research direction, and target in the automation workspace. Authorized workflows can search, import, extract, generate, review, approve, and start child campaigns on the server.

| Workflow | Scope |
| --- | --- |
| Small-scale run | Target papers or eligible unique contacts within the limits shown by the application. |
| Bulk run | Search for up to 10,000 unique papers and process them through separately gated child campaigns. |
| Weekly schedule | Incremental discovery every Monday at 09:00 in Asia/Shanghai, with up to 5,000 new papers per occurrence. |

Weekly discovery combines configured research directions with available public trend evidence and the configured model, with bounded fallback behavior. It is not a ChatGPT subscription or browser integration.

Paper targets are not email quotas: papers may have no usable contact, share contacts, or produce multiple candidates. Runs can block on insufficient results, changed configuration, permissions, or delivery checks. Automated sending requires the combined permissions and the explicit `AUTO_OUTREACH_SEND` confirmation. Where offered, audited acceptance of historical deliverability warnings is not an override for content, security, unsubscribe, or transport requirements.

Closing the browser does not stop a run. Canceling cannot retract accepted or in-flight mail; pausing a weekly schedule prevents future triggers rather than canceling existing runs.

### Analytics and administration

- Inspect ingestion, papers, contacts, author networks, campaigns, links, and supported aggregate CSV exports.
- Manage users, roles, current permissions, audit events, mail accounts, and runtime settings.
- Use server-side authorization in addition to frontend permission-aware navigation.
- Treat image loads and signed-link requests as estimated engagement signals, not proof of human reading or clicking.

## Architecture

~~~text
Browser
   |
Nginx + Vue frontend
   | HTTP API / job events
Spring Boot API ---------------- PostgreSQL (durable business state)
   |                             Redis (coordination, cache, rate limits)
   |                             MinIO (private assets)
   |
Kafka jobs / results / retry / dead-letter topics
   |                     |                         |
arXiv Python worker      Personalization worker    Spring mail worker
   |                     |                         |
Official arXiv APIs      Ray tasks -> model API    SMTP / inbound IMAP
~~~

| Component | Responsibility |
| --- | --- |
| `backend-api` | Authentication, RBAC, APIs, orchestration, analytics, and Flyway migrations. |
| `mail-worker` | Delivery and inbound processing using the same backend image with a separate runtime profile. It does not expose business controllers. |
| `arxiv-worker` | Metadata, taxonomy, and bounded Source processing. |
| `personalization-worker` | Kafka draft-generation consumer and result aggregation. |
| `ray-head` / `ray-worker` | Distributed, bounded draft-generation tasks. |
| PostgreSQL / Redis / Kafka | Durable facts / temporary coordination / asynchronous transport. |
| MinIO / Mailpit / GreenMail | Private assets / local mail capture / isolated mail-protocol fixtures. |

PostgreSQL is the source of truth. A Redis lease, Kafka acknowledgment, HTTP timeout, or empty mailbox is not proof that a business operation completed.

### Technology stack

| Layer | Technologies |
| --- | --- |
| Frontend | Vue 3, TypeScript, Vite 8, Tailwind CSS 4, Pinia, Vue Router, Headless UI, TipTap, ECharts, vis-network. |
| Backend | Java 25, Spring Boot 4.1, WebFlux, R2DBC PostgreSQL, Flyway, Spring Security, Kafka, Redis, Spring Modulith, MinIO SDK. |
| Worker | Python 3.12, uv, aiokafka, httpx, Pydantic, defusedxml, Redis, Ray Core/Client. |
| Local infrastructure | PostgreSQL 17.5, Redis 8.0.2, Kafka 4.1.2, MinIO, Mailpit, GreenMail, Docker Compose. |

Exact dependencies and image versions are recorded in the build files, lockfiles, and Compose configuration.

## Quick Start

These instructions are for a **fresh, isolated local installation**, not an upgrade or restart of an existing deployment. Do not reuse production configuration, volumes, queues, credentials, or recipient data.

### 1. Prepare the checkout

You need Git, OpenSSL, and a running Docker engine with Compose v2. Docker Desktop is suitable for local development; allow at least 8 GB of available memory as a starting budget, not a capacity guarantee. Container builds provide the application toolchains.

~~~bash
git clone https://github.com/MRNOBODY-ZST/CaMelArxivAdv.git
cd CaMelArxivAdv
cp .env.example .env
~~~

Do not overwrite an existing `.env`. Choose an unused Compose project name and an `APP_VERSION` tag unique to this checkout. The examples below use `camel-arxiv-demo` and `local-demo`; change them if already in use. A project name separates Compose networks and volumes, but does not isolate globally named image tags.

### 2. Configure secrets and the initial administrator

Replace the example PostgreSQL and MinIO passwords. Generate a different 32-byte Base64 value for **each** of these five required keys:

| Variable | Purpose |
| --- | --- |
| `JWT_SIGNING_KEY_BASE64` | Access-token signing. |
| `AUTH_FINGERPRINT_HMAC_KEY_BASE64` | Authentication fingerprints. |
| `APP_ENCRYPTION_KEY_BASE64` | Protected application data and stored credentials. |
| `APP_EMAIL_HMAC_KEY_BASE64` | Contact deduplication. |
| `TEMPLATE_ASSET_SIGNING_KEY_BASE64` | Signed private template assets. |

~~~bash
openssl rand -base64 32
~~~

Run that command separately for every key; never reuse its output. Compose also requires an independent internal configuration token, **even when model generation is disabled**:

~~~bash
openssl rand -hex 32
~~~

Put that value in `PERSONALIZATION_RUNTIME_CONFIG_TOKEN`. Keep all values in the ignored local `.env` or protected runtime secrets, never in frontend code or Git.

Set `INITIAL_ADMIN_USERNAME`, `INITIAL_ADMIN_EMAIL`, `INITIAL_ADMIN_DISPLAY_NAME`, and `INITIAL_ADMIN_PASSWORD`. The temporary password must contain at least 12 characters, uppercase and lowercase letters, a digit, and a symbol; it must not contain the username or email local part. Set `ARXIV_CONTACT_EMAIL` to a monitored contact before making arXiv requests.

The bootstrap creates the initial administrator without overwriting existing accounts. First login requires a password change; remove the bootstrap password from runtime secrets afterward.

### 3. Keep external mail and optional integrations disabled

Set these values explicitly in `.env`:

~~~dotenv
APP_VERSION=local-demo
ALLOW_LIVE_SMTP=false
ALLOW_PUBLIC_MAILBOX=false
PERSONALIZATION_ENABLED=false
TRACKING_ENABLED=false
CAMPAIGN_DELIVERY_ENABLED=false
CAMPAIGN_SAFETY_ENABLED=false
CAMPAIGN_INBOUND_ENABLED=false
PARTNER_INVITE_ENABLED=false
~~~

The current environment example and Compose public-mail protocol fallbacks allow external connections unless you override them. Do not assume every switch defaults to false. The configuration above still permits the allowlisted local mail fixtures, but does not enable campaign delivery.

### 4. Build and start the local stack

Run from the repository root, using the same project name throughout:

~~~bash
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml config --quiet
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml ps
~~~

The development overlay disables Secure cookies for local HTTP and publishes the Mailpit and MinIO consoles. It is not a public deployment configuration. Published ports are not explicitly bound to loopback; use a trusted local network/firewall.

| Entry | Default address |
| --- | --- |
| Application | [http://localhost:8080](http://localhost:8080) |
| Public API health | [http://localhost:8080/api/v1/system/health](http://localhost:8080/api/v1/system/health) |
| Mailpit inbox | [http://localhost:8025](http://localhost:8025) |
| MinIO console | [http://localhost:9001](http://localhost:9001) |
| OpenAPI JSON | [http://localhost:8080/api/openapi.json](http://localhost:8080/api/openapi.json) |

`HTTP_PORT`, `MAILPIT_WEB_PORT`, and `MINIO_CONSOLE_PORT` change the published ports. The configured Swagger UI path is `/api/docs`; the OpenAPI JSON is the direct API reference.

PostgreSQL, Redis, Kafka, backend services, mail-protocol fixture ports, and Ray Client/GCS are not published to the host. For example, `mailpit:1025` is a **container-network** SMTP address, not `localhost:1025`.

### 5. Explore without contacting recipients

1. Sign in with the bootstrap account and complete the password change.
2. Open paper discovery, preview a narrow search, and import a small selection.
3. Follow the import job and inspect paper metadata. Source extraction additionally requires compatible Source-admission configuration; complete that setup before requesting it.
4. Inspect masked contact evidence; do not treat automated mapping as human verification.
5. Create and preview a template. For a local test, configure SMTP to `mailpit:1025` with `PLAIN_LOCAL_ONLY` and an explicit synthetic recipient.
6. Check Mailpit and delivery records. Enable paid model calls, external mail, or automation only after reviewing the relevant configuration and permissions.

To inspect selected service logs without dumping configuration:

~~~bash
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml logs --tail=100 backend-api arxiv-worker
~~~

To stop **this local stack** while preserving named volumes:

~~~bash
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml down
~~~

Do not add `--volumes` if you need the data. These commands are not a safe-stop procedure for an existing sending deployment.

## Configuration

Use [.env.example](.env.example) as the variable inventory and [Deployment](docs/DEPLOYMENT.md) for environment-specific setup.

| Area | Main controls |
| --- | --- |
| Origin and ports | `PUBLIC_BASE_URL`, `HTTP_PORT`, `MAILPIT_WEB_PORT`, `MINIO_CONSOLE_PORT`. |
| Model generation | `PERSONALIZATION_ENABLED`, `PERSONALIZATION_PROVIDER`, `PERSONALIZATION_MODEL`, `PERSONALIZATION_API_BASE_URL`, `PERSONALIZATION_API_KEY`, and the internal configuration token. |
| Mail protocols | `ALLOW_LIVE_SMTP`, `ALLOW_PUBLIC_MAILBOX`, and exact local-host allowlists. |
| Campaign processing | Separate `CAMPAIGN_DELIVERY_ENABLED`, `CAMPAIGN_SAFETY_ENABLED`, and `CAMPAIGN_INBOUND_ENABLED` gates. |
| Tracking | `TRACKING_ENABLED`, reachable callback origin, and a separate `TRACKING_SIGNING_KEY_BASE64`. |
| Partner activation | `PARTNER_INVITE_ENABLED`, a dedicated server-side token, and a separate invitation encryption key. |

Source admission (`app.arxiv.source-admission.enabled`) and draft-only template migration (`app.campaign.template-migrations.enabled`) are separately disabled by default. They require compatible API/worker execution paths and the checks in the [Source publication](docs/operations/source-publication-runbook.md) and [template-migration](docs/operations/template-migration-runbook.md) runbooks; they are not automatically enabled by the local quick start.

Environment model settings seed the administration settings; saved settings govern subsequent generation. Supported provider values are `openai` and `anthropic`; the gateway must support the corresponding API contract. Provider calls can incur separate charges.

Partner activation is optional and distinct from ordinary draft generation. When enabled and used, it sends recipient data, including the email address, to the configured fixed partner service. Keep partner credentials and issued claim links out of logs and public artifacts. Trial-credit eligibility and grants belong to the provider; template copy is not a credit guarantee.

Public mail requires STARTTLS or implicit TLS with certificate checks. Plaintext mode is reserved for exact allowlisted local test endpoints. Public tracking needs reachable HTTPS and upstream log exclusions; configuring an HTTPS URL does not establish reachability. Never open real tracking, unsubscribe, or claim links to manufacture test events.

## Typical workflow

~~~text
Discover -> Import -> Extract evidence -> Select contacts/template/sender
         -> Generate drafts -> Inspect content and preflight
         -> Permissioned review/approval -> Start or schedule -> Observe outcomes
~~~

Ordinary campaigns use separate review, approval, and start transitions. Explicit automated outreach may carry out those transitions under its authorized actor; it does not imply a human reviewed each message.

| Workspace | UI paths |
| --- | --- |
| Discovery and jobs | `/arxiv/discovery`, `/jobs` |
| Papers and contacts | `/papers`, `/contacts` |
| Templates and audiences | `/email/templates`, `/email/segments` |
| Campaigns and delivery | `/email/campaigns`, `/email/deliveries` |
| Automation | `/email/automation` |
| Research analytics | `/analytics/ingestion`, `/analytics/papers`, `/analytics/contacts`, `/analytics/authors` |
| Outreach analytics | `/analytics/campaigns`, `/analytics/links` |
| Administration | `/admin/users`, `/admin/roles`, `/admin/audit`, `/admin/mail-accounts`, `/admin/settings` |

### API overview

Business APIs use `/api/v1` and require authentication plus operation-specific permissions. The platform bootstraps an administrator rather than exposing public self-registration for platform operators.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v1/auth/login` | Username/email login. |
| `GET /api/v1/arxiv/taxonomy` | Current classification snapshot. |
| `POST /api/v1/arxiv/search/preview` | Structured discovery preview. |
| `POST /api/v1/arxiv/imports` | Create an import job. |
| `GET /api/v1/jobs/{id}` | Read durable job status. |
| `GET /api/v1/jobs/{id}/stream` | Job progress over SSE. |
| `GET /api/v1/papers` | Filter the paper library. |
| `GET /api/v1/contacts` | Read masked contact listings. |
| `POST /api/v1/templates/preview` | Render and validate a template preview. |

See [API documentation](docs/API.md) and the generated OpenAPI schema for full request shapes, limits, and state-changing operations.

## Development

### Toolchains

| Component | Local requirement |
| --- | --- |
| Backend | JDK 25; use the included Gradle wrapper. |
| Frontend | Node.js 24.x; the container pins 24.10.0. |
| Worker | Python 3.12, not 3.13; uv and the checked-in lockfile. |
| Integration tests | A working Docker engine and isolated owned fixtures. |

### Frontend

With the isolated Compose API available at port 8080:

~~~bash
cd frontend
npm ci
npm run dev
~~~

Vite serves port 5173 and proxies `/api` and `/t` to `http://localhost:8080`. It does not publish the internal databases.

~~~bash
npm test -- --run
npm run lint
npm run typecheck
npm run build
~~~

### Backend

From `backend/`:

~~~bash
./gradlew --no-daemon check bootJar
~~~

Integration tests use Testcontainers and need Docker; missing dependencies or skipped tests are not passing evidence. Running the backend natively also requires explicitly configured database, Redis, Kafka, and asset connections—the main Compose stack does not publish them for host access.

### Python worker

From `worker/`:

~~~bash
uv sync --frozen --group dev --python 3.12
uv run pytest -q
uv run ruff check .
uv run mypy src tests
~~~

The installed entry points are `uv run arxiv-worker` and `uv run personalization-worker`. They are real consumers, not offline demos; use only with the intended Kafka, Redis, Ray, and runtime configuration. Do not attach a development worker to production queues.

### Browser and container checks

Playwright uses an installed Microsoft Edge and local synthetic fixtures. From `frontend/`, provide a dedicated local test account through `E2E_USER` and `E2E_PASSWORD`, then run:

~~~bash
npm run test:e2e
~~~

These tests can create, archive, and delete test resources; do not run against production or shared operational data. Required permissions and fixture behavior are described in [Operations](docs/OPERATIONS.md).

For the baseline Compose configuration-contract check, install Bash, `jq`, and Docker Compose, then run from the repository root:

~~~bash
bash scripts/verify-compose.sh
~~~

This checks rendered configuration, not the health or correctness of a running stack.

The separate [image-contract checker](scripts/verify-container-images.sh) starts short-lived containers and currently targets literal `camel-arxiv/*:dev` images; it does not read `APP_VERSION`. It cannot validate the `local-demo` images above. Review its fixed targets in a separate validation environment; do not overwrite or retag shared `:dev` images to make it pass. Neither utility proves that an arbitrary checkout or deployment passed its operational gates.

## Project structure

~~~text
CaMelArxivAdv/
├── backend/
│   ├── src/main/java/com/camel_hub/advertisement/
│   │   ├── arxiv/           # Discovery, papers, and Source processing
│   │   ├── campaign/        # Campaign workflow, delivery, tracking, partner
│   │   ├── outreach/        # Single, bulk, and weekly orchestration
│   │   ├── identity/        # Authentication, RBAC, and users
│   │   ├── email/           # Templates, assets, SMTP, and mailboxes
│   │   └── analytics/       # Aggregates and exports
│   └── src/main/resources/db/migration/
├── worker/src/app/          # arXiv, extraction, and personalization workers
├── frontend/src/            # Vue modules, components, layouts, and router
├── infra/                   # Nginx and database initialization
├── scripts/                 # Verification and operational tools
├── docs/                    # Architecture, API, security, and runbooks
├── docker-compose.yml       # Production-shaped baseline
└── docker-compose.dev.yml   # Local development overlay
~~~

## Security and operational boundaries

- Use a true sender identity, an accurate research purpose, an authorized audience, and applicable privacy/anti-spam obligations. Publicly available contact details alone do not establish consent.
- Model input excludes recipient email fields and SMTP credentials, but optional partner activation has its own external-data boundary.
- Keep contact values encrypted and ordinary views masked. Never commit credentials, author contact lists, operational ledgers, frozen message bodies, or issued capability links.
- `SMTP_ACCEPTED` means the SMTP server accepted a message, not that it reached an inbox. Image/click events can come from proxies, prefetchers, and scanners; no event does not prove a message was unread.
- Preserve `UNKNOWN` / `OUTCOME_UNKNOWN` results. Reconcile the original operation instead of treating uncertainty as permission to send again.
- arXiv access uses official hosts and shared pacing; Source contents are not executed or retained as business assets. Source-admission and result-publication protocols have additional deployment compatibility gates.
- Draft-only template migration does not approve, start, resume, or resend campaigns. Preserve historical attempts, feedback, and original reconciliation keys.
- Existing deployments require verified safe admission stop, in-flight completion, backups, and compatible API/worker/result protocols before replacement or rollback. An additive database schema or local green test is not a safe-restart proof. Do not treat experimental deployment-drain code as an operational gate.
- Put public deployments behind TLS and appropriate network controls. The local Compose Kafka setup is not a hardened public broker, and the development overlay must not be exposed as a production service.

## Documentation

Most detailed subsystem guides are currently in Chinese; this repository overview is maintained in both languages.

| Guide | Contents |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) · [ERD](docs/ERD.md) | Components, data paths, and relational model. |
| [API](docs/API.md) · [RBAC](docs/RBAC.md) | Request conventions, authentication, and authorization. |
| [Source extraction](docs/TEX_EXTRACTION.md) | Archive/parser restrictions and contact evidence. |
| [Analytics](docs/ANALYTICS.md) | Metric definitions, filters, freshness, and exports. |
| [Security and privacy](docs/SECURITY_AND_PRIVACY.md) | Data classes, secrets, encryption, and audit boundaries. |
| [Mail tracking](docs/EMAIL_TRACKING.md) | Test-mail, image, and click outcome semantics. |
| [Deployment](docs/DEPLOYMENT.md) · [Operations](docs/OPERATIONS.md) | Environment preparation, runtime checks, and maintenance. |
| [Campaign delivery](docs/operations/campaign-delivery-runbook.md) · [SMTP quotas](docs/operations/smtp-quotas.md) | Delivery gates, limits, and feedback. |
| [Template migration](docs/operations/template-migration-runbook.md) | Draft-only transfer and original-key reconciliation. |
| [Source publication](docs/operations/source-publication-runbook.md) | Admission, result handling, retries, and compatible rollout. |
| [Email fixtures](docs/email/README.md) · [Preparation diagnostics](docs/email/preparation-diagnostics.md) | Synthetic invitation examples and bounded failure evidence. |
| [Component mapping](docs/DESIGN_SKILL_COMPONENT_MAP.md) · [Third-party notices](docs/THIRD_PARTY_NOTICES.md) | UI provenance and licensing boundaries. |

## Contributing

Open an [issue](https://github.com/MRNOBODY-ZST/CaMelArxivAdv/issues) or submit a focused pull request with the relevant tests and documentation updates. Keep schema changes additive, preserve immutable history, and explain any protocol or operational compatibility impact.

Use synthetic fixtures in tests and documentation. Exclude real contacts, credentials, private operational records, and build artifacts. Maintain matching English and Chinese README sections when changing setup instructions or safety requirements.

## License

[MIT](LICENSE) © 2026 Hades.

The project includes application adaptations of commercially licensed Tailwind Plus materials; those assets are not a separately redistributable component/template pack. See [Third-party notices](docs/THIRD_PARTY_NOTICES.md).
