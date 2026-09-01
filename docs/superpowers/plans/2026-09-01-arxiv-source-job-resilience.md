# arXiv Source Job Resilience Implementation Plan

> **For agentic workers:** execute each task with test-first regressions, obtain an independent review, and require fresh verification before deployment.

**Goal:** Make Source extraction tolerate malformed papers, resume safely, preserve Kafka liveness and ordering semantics, and recover production job `525be8cf-860b-480f-b852-63c27c2860f9` without skipping target 26.

**Architecture:** Balanced TeX parsing plus ordered metadata cross-validation; strict outbound contracts and item-level content failure; canonical monotonic Redis checkpoints; active Kafka poll heartbeats; dead-letter-before-terminal-before-commit settlement; database-derived terminal counts with durable incomplete-completion reconciliation.

**Spec:** `docs/superpowers/specs/2026-09-01-arxiv-source-job-resilience-design.md`

## Global constraints

- Never truncate ambiguous source metadata into apparently valid author records.
- Never turn infrastructure, persistence, or programming errors into successful items.
- Persist a checkpoint only after both Kafka messages for that item are acknowledged.
- Derive extraction terminal counters from `job_items`, not worker cumulative counters.
- Stop the old worker and drain every results partition before changing result-key behavior.
- Do not expose Source text, addresses, credentials, tokens, or exception values in logs or public errors.
- Preserve the original production job ID, command idempotency key, target order, Nginx configuration, and unrelated services.
- Do not include the assistant product name in source, commits, or deployment artifacts.

## Task 1: Repair Source parsing and item isolation

- [x] Add production-shaped tests for nested IEEE footnotes and paired TeX math addresses.
- [x] Remove author metadata using balanced command spans.
- [x] Split comma-separated authors only when canonical names equal ordered `metadataAuthors`.
- [x] Validate legal local parts and strict LDH/IDNA domains.
- [x] Revalidate canonical author merges and all outbound contract limits.
- [x] Remove IEEE author reference marks without promoting them into names or affiliations.
- [x] Irreversibly redact email-like author-adjacent text from affiliations, evidence context, and source paths; reject residual at-signs in worker and backend public fields.
- [x] Match Java UTF-16 length limits for every Source result string boundary.
- [x] Keep downloader timeout/retry semantics outside the bounded parsing deadline.
- [x] Run archive/TeX/contact parsing in a killable subprocess and join it before cleanup on timeout.
- [x] Convert only known Pydantic content-boundary errors to `SOURCE_CONTENT_INVALID`; let shape and infrastructure errors retry.
- [x] Verify a failed paper does not stop the following target.

## Task 2: Bound backend commands

- [x] Query ordered `paper_authors.raw_name` with each extraction target.
- [x] Add `metadataAuthors` to the worker target contract.
- [x] Give author arrays a 256 KiB aggregate UTF-8 JSON budget, including or omitting each paper's list as a whole.
- [x] Reject a serialized command envelope over 768 KiB before creating the job/outbox row.
- [x] Convert a Source result over 768 KiB into a bounded item failure before Kafka publication.
- [x] Cover the 100-paper, 500-author, 300-character boundary and ordinary small lists.

## Task 3: Make long and retried commands resumable

- [x] Store a versioned Source checkpoint with a 32-day TTL.
- [x] Require the exact canonical compact JSON representation and consistent integer counts.
- [x] Use a Lua compare-and-set that rejects stale/equal progress from competing workers.
- [x] Defer rather than acknowledge when checkpoint advancement loses a race.
- [x] Pause all assigned Kafka partitions and call `getmany` periodically while command work continues.
- [x] Bound retry-topic not-before delays and keep polling while a valid retry delay elapses.
- [x] Cancel and retry the operation if the poll-heartbeat task fails.
- [x] Keep `max.poll.interval` as a backstop at least ten heartbeat periods long.

## Task 4: Guarantee terminal settlement

- [x] Separate `ACK`, uncounted `DEFER`, counted `RETRY`, and immediate `DEAD` outcomes.
- [x] On final retry or dead command, publish DLT, publish bounded terminal failure, mark processed, clean Source checkpoint, then commit.
- [x] Recover safe failure context with an iterative top-level JSON scanner that tolerates deeply nested poison payloads.
- [x] Bound future command types, trace/key values, and terminal result idempotency keys.
- [x] Key new job results by job ID.

## Task 5: Reconcile cross-partition completion

- [x] Lock the job row in each result-handler transaction.
- [x] Ignore nonterminal messages after a terminal state while preserving canceled-item cleanup.
- [x] When completion arrives early, persist `ARXIV_JOB_COMPLETION_DEFERRED`, exact item totals, and `AWAITING_ITEM_RESULTS` without terminalizing.
- [x] Let the transaction that persists the last late item complete the job from authoritative totals.
- [x] Add a single-flight, multi-instance-safe reconciler using `FOR UPDATE SKIP LOCKED` to fail stale incomplete completions after a bounded grace.
- [x] Ignore cumulative progress after a deferred completion and refresh exact database totals after every late extraction result.
- [x] Preserve the last OAI cursor when a retry or pause progress event has no checkpoint.
- [x] Include zero-item historical anomalies in timeout reconciliation.
- [x] Derive retry totals and new `PENDING job_items` from stored Source targets rather than corrected terminal counters.
- [x] Keep repeated retry idempotency keys fixed-length and unique across the full lineage.
- [x] Close open Source items and synchronize exact counters for both API and worker cancellation paths.
- [x] Pass grace/reconcile settings through Compose and document defaults.
- [x] Test completion-before-item, inflated late progress, zero-item timeout, retry, duplicate, explicit failure, cancellation, and late-message paths.

## Task 6: Complete local verification and integration

- [ ] Run worker Ruff, Mypy, and the complete Pytest suite.
- [ ] Run `./gradlew check` for the backend.
- [ ] Run frontend unit tests, typecheck, lint, and production build.
- [ ] Run Compose and container-image verification scripts.
- [ ] Run `git diff --check`, secret/name scans, and verify a clean committed branch.
- [ ] Resolve every Critical or Important independent-review finding and rerun affected suites.
- [ ] Fast-forward local `main`, rerun a final smoke check, and push `main` without force.

## Task 7: Back up and deploy production safely

- [ ] Record the exact production commit, image IDs, container health, job/item state, Redis keys, DLT offsets, and all results-partition lags.
- [ ] Create and verify a PostgreSQL custom-format backup before any mutation.
- [ ] Stop only the old arXiv worker; require results-consumer lag zero on every partition.
- [ ] Pull the exact pushed `main` commit.
- [ ] Build the backend and worker images with the exact commit tag.
- [ ] Recreate the backend first while the worker remains stopped; wait for health.
- [ ] Recreate the arXiv worker with the new image; do not restart Nginx, frontend, mail, database, or unrelated services.
- [ ] Recheck health and zero results lag before replay.

## Task 8: Recover the original job once

- [ ] Verify targets 1–25 are terminal and target 26 is the first missing item; do not trust the displayed count of 52.
- [ ] Build one replay envelope from the original outbox command, preserving job ID, message ID, command idempotency key, trace, parser version, and target order.
- [ ] Intentionally enrich every target with ordered current `paper_authors.raw_name`; require the five expected target-53 names and an envelope safely below 768 KiB.
- [ ] Confirm the processed marker is absent and seed exact canonical Redis progress `{"version":1,"nextIndex":25,"success":25,"skipped":0,"failed":0}`.
- [ ] Insert one uniquely identified replay row through the normal outbox path, with no retry headers.
- [ ] Monitor worker, Kafka, backend events, database item totals, and API until terminal. Do not create a second job or replay twice.

## Task 9: Production acceptance

- [ ] Require 100 terminal `job_items`, zero pending/running items, and exact agreement between job counters and item totals.
- [ ] Require the API and job UI to show the same terminal status and event timeline without console errors.
- [ ] Verify target 26 persists the four valid Source addresses and target 53 persists exactly five metadata-verified authors plus its contact.
- [ ] Verify contacts are visible through the contacts API and `/contacts` UI.
- [ ] Verify no Source checkpoint/processed anomaly, no new retry or DLT copy, and zero jobs/results lag.
- [ ] Verify domain HTTPS, backend health, worker health/idle state, and unchanged Nginx routing.
- [ ] Retain the verified backup and remove only temporary diagnostic artifacts.
