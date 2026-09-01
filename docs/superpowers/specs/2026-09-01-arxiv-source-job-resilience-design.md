# arXiv Source Job Resilience Design

## Goal

Ensure one malformed arXiv source package cannot restart or strand an entire batch extraction job. The production case for arXiv `2510.13029` must parse its IEEE author block correctly, item-level validation failures must be persisted and skipped, retried commands must resume from a durable worker checkpoint, and retry exhaustion must produce a visible terminal job failure before dead-letter settlement.

## Confirmed failure

Job `525be8cf-860b-480f-b852-63c27c2860f9` completed 52 of 100 targets. The next paper downloaded successfully, but `_author_names` removed nested `\thanks{...}` content with a non-balanced regular expression. It therefore interpreted author and affiliation prose as one 400-character name, violating the 300-character `ExtractedAuthor.name` boundary.

The validation error escaped the per-paper runner. The worker converted the unexpected exception into `REQUEUE`, replayed the command from target zero, and emitted deterministic result idempotency keys that the backend correctly ignored for the already persisted first 52 items. After retry exhaustion, the generic dead-letter path had no corresponding `ARXIV_JOB_FAILED` result, so the database could remain `RUNNING` indefinitely.

## Selected approach

The fix has four cooperating boundaries:

1. Parse nested author metadata structurally. Remove balanced nested metadata commands such as `thanks`, `affiliation`, and `institute`, then conservatively split common comma-plus-final-`and` author lists. Never truncate an oversized parsed name into apparently valid data.
2. Convert Pydantic validation caused by one source document into a `SourceExtractionResult` with status `FAILED` and code `SOURCE_CONTENT_INVALID`. The command loop persists that item and continues with the remaining targets. Unexpected infrastructure or programming exceptions still escape for retry.
3. Persist a validated source-batch checkpoint in Redis after each result and progress pair is durably published. It contains the next target index and cumulative success, skipped, and failed counts. A replay resumes only when the checkpoint is internally consistent with the command; corrupt or incompatible data fails safe by restarting at zero.
4. When an unexpected exception reaches the final retry, publish an idempotent `ARXIV_JOB_FAILED` result before committing the dead-letter settlement. The public error is bounded and generic; logs include a safe exception type without source content or credentials.

Simple string truncation was rejected because it would store affiliation prose as a false author. Treating every exception as an item failure was rejected because it would hide Kafka, Redis, disk, and programming failures. Directly editing the production job was rejected because it would not prevent recurrence.

## Parser and item semantics

Balanced command removal reuses the existing TeX command scanner, so nested braces do not leak into names. The author-list split applies only when a cleaned segment contains a comma-separated sequence with a final `and`; ordinary single names and existing `\and`, TeX line-break, and semicolon behavior remain unchanged.

`SourceExtractionRunner.run` catches Pydantic `ValidationError` around extraction/model construction and returns a cleaned-up failure result:

- status: `FAILED`
- error code: `SOURCE_CONTENT_INVALID`
- summary: `Source metadata exceeded supported parsing boundaries`

No raw author text is returned or logged. Other existing security, availability, discovery, timeout, and HTTP classifications remain unchanged.

## Durable source checkpoint

Redis stores a versioned JSON document under a source-specific key derived from the command idempotency key. The document includes `nextIndex`, `success`, `skipped`, and `failed`; all values are non-negative, their sum equals `nextIndex`, and `nextIndex` cannot exceed the command target count. The key expires after seven days.

The worker saves the checkpoint only after both messages for an item have been acknowledged by Kafka. At command completion it publishes the terminal result, clears the source checkpoint, and marks the command processed. Crashes before checkpoint save can repeat at most one item; deterministic result idempotency keeps that repeat harmless. Pause, cancel, and transient retry preserve the checkpoint.

## Retry exhaustion and observability

Kafka settlement reports whether it committed, requeued, or dead-lettered a record. For an unexpected processing exception, the final dead-letter path invokes a callback that publishes `ARXIV_JOB_FAILED` before the source offset commit. If that publication fails, settlement does not commit, preserving at-least-once recovery.

The terminal result uses a deterministic idempotency key and contains:

- status: `FAILED`
- stage: `FAILED`
- error code: `WORKER_RETRY_EXHAUSTED`
- summary: `Worker exhausted retries after an unexpected processing failure`

The worker log records the exception class and job ID, but not the exception value or source text. The backend already persists `ARXIV_JOB_FAILED` and exposes it through the job detail API and event timeline.

## Production recovery

Deployment rebuilds the shared worker image and restarts affected worker/Ray services only after checking for active personalization work. Before deployment it captures the current job, Kafka, Redis, container, and database state. If the original command is still pending or in progress, restarting the arXiv worker allows Kafka to redeliver it to the fixed code. If it has already reached the dead-letter topic, one exact matching command is replayed after seeding a checkpoint from the verified 52 terminal job items.

The recovered job must reach `SUCCEEDED` or `PARTIALLY_SUCCEEDED`, have 100 terminal items, and leave no worker checkpoint. The expected result for this corpus is one item-level failure only if the parser still cannot safely interpret the paper; a successful parse is preferred and verified against the author fixture.

## Validation

TDD covers the production-shaped IEEE author block, balanced nested-command removal, per-item validation isolation, continued processing after a failed item, checkpoint validation and resumption, checkpoint write ordering, retry-exhausted terminal publication ordering, duplicate safety, and unchanged transient retry behavior. The complete Python worker suite, backend result-handler suite, container import checks, Compose contract, and production API/UI state must pass before completion.
