# arXiv Source Job Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make arXiv source extraction tolerate malformed individual papers, resume retried batches, and terminate visibly when unexpected retries are exhausted.

**Architecture:** Parse nested author metadata with balanced command spans and classify document-shape validation as one failed extraction result. Persist a versioned cumulative source checkpoint in Redis after Kafka result acknowledgements. Extend Kafka settlement with a pre-commit dead-letter callback so an idempotent `ARXIV_JOB_FAILED` result is durable before final offset commit.

**Tech Stack:** Python 3.12, asyncio, Pydantic 2, Redis 8, aiokafka, pytest, Spring Boot result consumer, PostgreSQL 17, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-09-01-arxiv-source-job-resilience-design.md`

## Global Constraints

- Never truncate malformed author metadata into apparently valid author records.
- One paper's content validation failure must not requeue the other papers.
- Unexpected infrastructure and programming failures remain retryable until the configured Kafka retry limit.
- Checkpoints are saved only after the corresponding Kafka results are acknowledged.
- Retry exhaustion must persist a terminal backend event before the source offset is committed.
- Do not expose source text, email addresses, credentials, tokens, or exception values in logs or public errors.
- Preserve existing message topics, contract version, result idempotency keys, control semantics, and unrelated services.
- Do not include the assistant product name in source, branches, commits, or deployment artifacts.

---

### Task 1: Parse nested IEEE author metadata safely

**Files:**
- Modify: `worker/src/app/extraction/contact_extractor.py:251-261`
- Modify: `worker/tests/extraction/test_contact_extractor.py`

**Interfaces:**
- Consumes: existing `_commands`, `_plain_tex`, and author command-name sets.
- Produces: `_author_names(argument: str) -> tuple[str, ...]` with balanced nested metadata removed and conservative comma-plus-`and` list splitting.

- [ ] **Step 1: Write the failing production-shaped test**

Add an IEEE fixture whose `\author` contains five comma-separated names followed by a nested `\thanks` affiliation block. Assert the literal five expected names and assert no affiliation prose appears in any name.

- [ ] **Step 2: Run the test and verify RED**

```bash
cd worker
uv run pytest tests/extraction/test_contact_extractor.py::test_nested_thanks_does_not_turn_ieee_affiliations_into_an_author -q
```

Expected: the current parser raises the 300-character Pydantic validation error.

- [ ] **Step 3: Implement balanced metadata removal and conservative list splitting**

Use `_commands(argument)` to collect complete spans for `email`, `ead`, `thanks`, `affiliation`, `affil`, `address`, `institute`, and `corref`; remove non-overlapping outer spans from right to left. After existing TeX separators, split a cleaned segment on commas and the final word `and` only when both forms are present and yield at least two non-empty names.

- [ ] **Step 4: Run the focused extraction tests and verify GREEN**

```bash
cd worker
uv run pytest tests/extraction/test_contact_extractor.py -q
```

Expected: all contact-extractor tests pass.

- [ ] **Step 5: Commit the parser slice**

```bash
git add worker/src/app/extraction/contact_extractor.py worker/tests/extraction/test_contact_extractor.py
git commit -m "fix: parse nested arxiv author metadata"
```

### Task 2: Isolate content validation to one source item

**Files:**
- Modify: `worker/src/app/jobs/source_extraction.py:52-110`
- Modify: `worker/tests/jobs/test_source_extraction.py`
- Modify: `worker/tests/jobs/test_source_command_processor.py`

**Interfaces:**
- Produces: `SourceExtractionResult(status="FAILED", error_code="SOURCE_CONTENT_INVALID")` for a Pydantic validation failure caused by one paper.
- Consumes: the existing per-target command loop, which already persists failed item results and computes `PARTIALLY_SUCCEEDED`.

- [ ] **Step 1: Write failing runner and command-loop tests**

Make the real runner's extractor path raise a Pydantic `ValidationError` and assert the bounded failure result. Add a two-target command test where the first result is `SOURCE_CONTENT_INVALID` and the second succeeds; assert both targets run and the terminal status is `PARTIALLY_SUCCEEDED`.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
cd worker
uv run pytest tests/jobs/test_source_extraction.py tests/jobs/test_source_command_processor.py -q
```

Expected: validation escapes instead of becoming an item result.

- [ ] **Step 3: Add the narrow validation boundary**

Catch only `pydantic.ValidationError` in `SourceExtractionRunner.run` and return code `SOURCE_CONTENT_INVALID` with summary `Source metadata exceeded supported parsing boundaries`. Do not catch `Exception`, Redis errors, Kafka errors, or arbitrary runtime errors.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass.

- [ ] **Step 5: Commit the item-isolation slice**

```bash
git add worker/src/app/jobs/source_extraction.py worker/tests/jobs/test_source_extraction.py worker/tests/jobs/test_source_command_processor.py
git commit -m "fix: isolate invalid arxiv source items"
```

### Task 3: Resume source batches from a validated Redis checkpoint

**Files:**
- Modify: `worker/src/app/jobs/job_control.py`
- Modify: `worker/src/app/jobs/arxiv_consumer.py:58-73,241-290`
- Modify: `worker/tests/jobs/test_job_control.py`
- Modify: `worker/tests/jobs/test_source_command_processor.py`
- Modify: `worker/tests/jobs/test_arxiv_consumer.py`

**Interfaces:**
- Produces: frozen `SourceProgress(next_index: int, success: int, skipped: int, failed: int)`.
- Produces: `JobStore.source_progress_for`, `save_source_progress`, and `clear_source_progress`.
- Consumes: source command idempotency key and target count.

- [ ] **Step 1: Write failing checkpoint storage and resume tests**

Assert Redis JSON round-trips under a source-specific key with a seven-day TTL. Assert malformed JSON returns no checkpoint. In the processor, seed `SourceProgress(1, 1, 0, 0)` for two targets and assert only target two runs, cumulative counts reach two, and save occurs after both result messages. Assert inconsistent counts or an index beyond the command length restart at zero.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
cd worker
uv run pytest tests/jobs/test_job_control.py tests/jobs/test_source_command_processor.py tests/jobs/test_arxiv_consumer.py -q
```

Expected: source checkpoint interfaces and resume behavior are absent.

- [ ] **Step 3: Implement versioned source progress storage**

Serialize literal JSON fields `version`, `nextIndex`, `success`, `skipped`, and `failed` under `camel:worker:source-progress:<idempotency-key>`. Parse strictly, reject booleans/non-integers/negative values, and return `None` on invalid data.

- [ ] **Step 4: Implement publish-then-checkpoint ordering**

Initialize the loop from a checkpoint only when counts sum to `nextIndex` and the index is within the target list. Save cumulative progress after both per-item publishes. Preserve it on pause/requeue; clear it after the terminal result is published and before marking the command processed.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass.

- [ ] **Step 6: Commit the checkpoint slice**

```bash
git add worker/src/app/jobs/job_control.py worker/src/app/jobs/arxiv_consumer.py worker/tests/jobs/test_job_control.py worker/tests/jobs/test_source_command_processor.py worker/tests/jobs/test_arxiv_consumer.py
git commit -m "fix: resume retried arxiv source batches"
```

### Task 4: Publish a terminal failure before retry-exhausted dead-letter commit

**Files:**
- Modify: `worker/src/app/messaging/kafka.py`
- Modify: `worker/src/app/jobs/arxiv_consumer.py`
- Modify: `worker/src/app/main.py`
- Modify: `worker/tests/test_kafka.py`
- Modify: `worker/tests/jobs/test_arxiv_consumer.py`
- Modify: `worker/tests/test_contracts.py`

**Interfaces:**
- Produces: `DeliverySettlement` values `ACKED`, `REQUEUED`, and `DEAD_LETTERED`.
- Produces: optional async `before_dead_letter` callback on `settle_delivery`.
- Produces: `ArxivCommandProcessor.publish_retry_exhausted_failure(body: bytes)` with deterministic result idempotency.

- [ ] **Step 1: Write failing settlement-order and failure-envelope tests**

Assert final retry event order is DLT publish, terminal callback, then source offset commit. Assert callback failure prevents commit. Assert the processor publishes `ARXIV_JOB_FAILED`, status `FAILED`, code `WORKER_RETRY_EXHAUSTED`, and no exception or source text. Assert non-final retries never call the callback.

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
cd worker
uv run pytest tests/test_kafka.py tests/jobs/test_arxiv_consumer.py tests/test_contracts.py -q
```

Expected: settlement has no callback/result and the processor has no terminal-failure method.

- [ ] **Step 3: Implement terminal publication before commit**

Return a settlement enum from `settle_delivery`. On retry exhaustion, durably publish to the DLT, await `before_dead_letter` when provided, then commit. In the arXiv worker, capture only the exception class for logging and pass a callback that publishes the bounded terminal result through the existing result publisher.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the Step 2 command. Expected: all selected tests pass, including personalization-worker callers that ignore the return value.

- [ ] **Step 5: Commit the terminal-failure slice**

```bash
git add worker/src/app/messaging/kafka.py worker/src/app/jobs/arxiv_consumer.py worker/src/app/main.py worker/tests/test_kafka.py worker/tests/jobs/test_arxiv_consumer.py worker/tests/test_contracts.py
git commit -m "fix: terminate exhausted arxiv jobs"
```

### Task 5: Verify, deploy, and recover the production job

**Files:**
- Modify only if contracts require it: `scripts/verify-container-images.sh`
- Runtime-only: production Compose state, Redis checkpoint, Kafka message, and database evidence.

**Interfaces:**
- Consumes: Tasks 1-4 and the existing production deployment wrapper.
- Produces: healthy worker services and terminal job `525be8cf-860b-480f-b852-63c27c2860f9` with 100 terminal items.

- [ ] **Step 1: Run complete local verification**

```bash
cd worker && uv run pytest -q && uv run ruff check . && uv run mypy src
cd .. && bash scripts/verify-container-images.sh && bash scripts/verify-compose.sh
cd backend && ./gradlew test
```

Expected: every command exits zero.

- [ ] **Step 2: Obtain an independent code review**

Review the complete branch for data loss, skipped targets, false-success states, callback ordering, leaked exception data, and compatibility with personalization-worker settlement. Resolve all Critical and Important findings and rerun Step 1.

- [ ] **Step 3: Merge and push `main`**

Fast-forward the reviewed branch into local `main`, verify a clean tree, and push the exact commit to GitHub without force.

- [ ] **Step 4: Back up and inspect production state**

Record commit/image/container IDs, dump PostgreSQL, verify the dump, capture the exact job/item/event state, inspect the matching Kafka location, and confirm whether personalization has active work before restarting shared-image services.

- [ ] **Step 5: Build and deploy the worker image**

Pull the exact `main` commit, set the protected runtime image tag, build the arXiv worker image, recreate only services using that image that are safe to restart, and wait for health. Preserve Nginx, backend, frontend, database, mail, and unrelated container identities.

- [ ] **Step 6: Recover the exact job once**

If Kafka redelivers the interrupted record, let the fixed worker resume it. If the record is already dead-lettered, verify one exact match, seed `SourceProgress(next_index=52, success=52, skipped=0, failed=0)`, and replay that command once with retry timing headers removed. Never synthesize a second job or process an ambiguous message.

- [ ] **Step 7: Verify production completion**

Require the job API and database to agree on a terminal status, 100 processed items, no `PENDING`/`RUNNING` items, a visible event for item 53, no residual source checkpoint, healthy/idle arXiv worker, and no new retry or DLT copy. Confirm the production job page shows the terminal state without console errors.

- [ ] **Step 8: Clean up**

Remove only temporary diagnostic artifacts and the merged worktree/branch, close SSH/browser sessions, and retain the database backup plus bounded deployment evidence.
