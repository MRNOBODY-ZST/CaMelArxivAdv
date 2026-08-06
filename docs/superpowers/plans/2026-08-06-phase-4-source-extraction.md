# Phase 4 Source Extraction Implementation Plan

**Status:** In progress.

**Goal:** Let an authorized user enqueue source extraction for imported arXiv papers, safely download and inspect official source packages in the isolated Python Worker, persist authors/contacts/evidence without retaining source files, and review the result through protected APIs and responsive UI.

**Architecture:** Spring Boot owns authorization, audit, job creation, encrypted contact persistence, disclosure, and queries. The Python Worker receives only database paper IDs plus validated arXiv IDs, constructs a configured official Source URL, uses the existing global arXiv lease, parses inside a bounded temporary directory, and publishes versioned structured results. No TeX or source-supplied executable is ever run. Every command/result path is idempotent and the result write is transactional.

**Technology:** Spring Boot WebFlux/R2DBC/Flyway/RabbitMQ, PostgreSQL AES-GCM ciphertext plus HMAC lookup, Python 3.12/httpx/aio-pika/Pydantic, Vue 3 strict TypeScript/Tailwind, JUnit/Testcontainers, pytest, Vitest, browser QA.

## Task 1: Lock the persistence and configuration contract

- Add Flyway V7 constraints/indexes for extraction-run idempotency, archive metrics, cleanup evidence, and contact query performance without deleting historical extraction mappings.
- Add backend data-protection configuration backed only by `APP_ENCRYPTION_KEY_BASE64` and `APP_EMAIL_HMAC_KEY_BASE64`; require independent 32-byte keys when persistence is enabled.
- Pass required keys through Compose without defaults and document all Source limits.
- Add migration/configuration tests before implementation.

## Task 2: Define versioned extraction message contracts

- Add a bounded `SourceExtractionCommand` containing one or more `{paperId, arxivId}` targets and a parser version.
- Add an explicit extraction-result event containing source status/format, archive and extracted sizes, inspected-file count, document class, ordered authors, normalized contacts, confidence, correspondence flags, and truncated masked evidence.
- Reject unknown fields, invalid IDs, excessive collections/text, unsafe paths, unexpected status combinations, and plaintext context containing a returned email.
- Keep complete source content and unrelated metadata out of RabbitMQ.

## Task 3: Implement the official-host Source downloader

- Validate modern and legacy arXiv IDs and build `https://export.arxiv.org/e-print/{id}` internally; never accept a user-provided URL.
- Use the shared Redis global arXiv lease, streaming response-size enforcement, timeouts, bounded redirects with host/scheme revalidation, MIME plus magic-byte inspection, and bounded retry classification.
- Treat 404/410 as `SOURCE_UNAVAILABLE`, security/policy violations as permanent item failures, and 429/5xx/network timeouts as retryable.
- Test host allowlisting, redirect escape, content-length/chunk overflow, MIME/magic mismatch, retry behavior, and temporary-file cleanup.

## Task 4: Implement safe archive inspection

- Detect tar, tar.gz/gzip, zip, or plain TeX without trusting the filename.
- Preflight and stream extraction while enforcing maximum archive size, total expanded bytes, single-file bytes, file count, directory depth, compression ratio, filename validity, and parsing deadline.
- Reject absolute/traversal paths, devices/FIFOs, symbolic links, hard links, and any target that resolves outside the job directory.
- Never invoke TeX, shell escape, scripts, or binaries; extraction APIs only create regular files and directories.
- Cover corrupted packages, Zip Slip, tar traversal, links, bombs, oversized files/count/depth, and valid formats with fixtures.

## Task 5: Discover TeX roots and bounded includes

- Decode only bounded `.tex`/`.ltx`/necessary text files, normalize line endings, and strip comments without corrupting escaped percent signs.
- Identify document class, root candidates, `\\input`/`\\include` dependencies, nested files, missing includes, cycles, and deterministic traversal order.
- Enforce include depth/file-count/deadline limits and record only safe relative paths.
- Test multi-file, cyclic, missing, excessive-depth, and alternate-extension fixtures.

## Task 6: Extract authors, affiliations, and explicit email addresses

- Parse author order and common `author(s)`, `email`, `ead`, `thanks`, `affiliation`, `address`, `institute`, `corref`, authblk, revtex, IEEEtran, elsarticle, and simple custom-macro patterns from title/author/preamble/footnote regions.
- Ignore comments, bibliography, ordinary body text, and unrelated addresses; never infer addresses from author names or institution domains and never query an external site/SMTP server.
- Normalize Unicode/TeX escapes, casing for comparison, punctuation, IDN domains, syntax, hashes, domains, deduplication, and obvious example addresses while retaining a safe display value.
- Test single/multiple/corresponding authors, all requested document families, custom macros, no email, unrelated body email, malformed/example addresses, ordering, and deduplication.

## Task 7: Produce explainable confidence and privacy-safe evidence

- Assign `HIGH`, `MEDIUM`, `LOW`, or `UNMAPPED` using deterministic direct-macro, correspondence, single-author, positional, label, and paper-level rules.
- Default machine-discovered ambiguous contacts to unverified; no low/unmapped result becomes eligible for later campaigns.
- Store only rule name, logical region, safe relative path, line number, and a short context with every address masked.
- Unit-test every confidence boundary and verify no evidence/log serialization leaks a complete address or source body.

## Task 8: Run extraction jobs with cancellation, cleanup, and bounded retry

- Extend the Worker processor for fetch/re-extract commands, sequential bounded targets, pause/cancel checkpoints, deterministic result message IDs, progress, partial success, and the existing capped retry/dead-letter policy.
- Wrap each target in a temporary directory context and timeout; publish cleanup confirmation only after the directory is gone.
- Track BUSY/current job in heartbeat and never mark the command processed until terminal publication succeeds.
- Test duplicate delivery, transient requeue, permanent item failures, partial terminal status, pause/cancel, cleanup after every exception, and heartbeat state.

## Task 9: Create authorized and audited extraction jobs

- Add `POST /api/v1/papers/{id}/extract` and bounded `POST /api/v1/papers/batch-extract`, requiring `paper:import` and rejecting missing/deleted/duplicate IDs.
- Insert Job, Job Items, CREATED event, and Outbox command in one transaction with a stable idempotency key; avoid concurrent nonterminal extraction for the same paper/version.
- Extend retry routing for both extraction job types and audit create/retry actions without sensitive payloads.
- Integration-test authorization, atomic Outbox creation, conflicts, bounds, and retry reconstruction.

## Task 10: Persist extraction results securely and atomically

- Validate result payloads again at the trust boundary.
- Encrypt every address/display value with AES-256-GCM and a random nonce, use a separate HMAC-SHA-256 for deduplication, and store only normalized domain in plaintext.
- Upsert authors/affiliations and global contacts, append immutable run/mapping/evidence history, update paper Source status/format/last extraction, job item counters/events, and processed-message marker in one transaction.
- Make duplicate result delivery a no-op and reject poison/integrity failures to the dead-letter route.
- Integration-test round trips, uniqueness, reruns/history, terminal state, security rejection/unavailable outcomes, rollback, and plaintext absence in persisted byte/text fields.

## Task 11: Expose paper extraction records and protected contacts

- Extend paper detail with corresponding author flags, latest contacts, extraction runs, and masked evidence.
- Add paginated/filterable `GET /api/v1/contacts` and `GET /api/v1/contacts/{id}` requiring `contact:read_masked`.
- Return masked email by default; disclose a complete decrypted address only for an explicit request with `contact:read_full`, and audit every successful disclosure.
- Add `PATCH /api/v1/contacts/{id}/verification` requiring `contact:verify`, optimistic status validation, and before/after audit.
- Test masking, full disclosure, 403 boundaries, audit, filters, pagination, missing records, and verification transitions.

## Task 12: Build the extraction and contact UI

- Add extraction actions to paper list/detail and show resulting jobs via the existing SSE/polling job UI.
- Expand paper detail to the specified tabs: base information, authors, contacts, categories, version history, extraction records, and raw metadata.
- Add the 作者与联系人 page with server-side filters, masked addresses, confidence/corresponding/verification/source rule, permitted full disclosure, and verification controls.
- Add loading, skeleton, empty, error, success, focus, keyboard, permission, desktop, and mobile states using the existing DesignSkill adapter components.
- Add Vitest coverage and browser QA with no console errors or page-level overflow.

## Task 13: Document, run a real paper, and accept Phase 4

- Add `docs/TEX_EXTRACTION.md` and update API, architecture, ERD, deployment, operations, security/privacy, README, plan, and task status.
- Run the complete backend, Worker, and frontend quality gates plus Compose/image contract checks.
- Rebuild/recreate affected services, parse a small imported arXiv paper from the official host, verify encrypted persistence and masked UI, and prove the job temporary directory is empty afterward.
- Review authorization, SSRF, archive traversal/bomb, parser execution, privacy/log, crypto, idempotency, transaction, and secret-leak boundaries before marking Phase 4 complete.

**Final checkpoint:** update Phase 4 to complete only after all observed evidence passes, then commit `feat: complete phase four source extraction`.
