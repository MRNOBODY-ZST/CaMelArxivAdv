# arXiv Source Job Resilience Design

## Goal

Make a Source extraction batch complete deterministically when one arXiv package is malformed, a command is retried, Kafka partitions are consumed out of order, or a poison command reaches the dead-letter topic. Recover production job `525be8cf-860b-480f-b852-63c27c2860f9` without skipping a paper or creating a replacement job.

## Confirmed production failure

The visible job counter reached 52, but the authoritative `job_items` state contains only 51 successful items. Target 26 (`2603.01042`) produced TeX math-wrapped addresses such as `$<name@example.edu$>$`; the old normalizer retained a trailing `$` in the domain. The backend rejected that extraction result while later progress messages continued to advance the display counter. Targets 27 through 52 were persisted.

Target 53 (`2510.13029`) exposed a second defect. A non-balanced expression removed only part of nested IEEE `\thanks` metadata, then treated author and affiliation prose as a name longer than the 300-character contract. That document validation exception escaped the per-item runner. Each command retry restarted from target zero and deterministic result idempotency caused already accepted results to be ignored. Retry exhaustion copied messages to the dead-letter topic without making the job terminal, so the job remained `RUNNING`.

The safe recovery boundary is therefore the first 25 targets, not the displayed progress of 52.

## Selected architecture

The repair uses cooperating parser, worker, messaging, and backend boundaries.

1. Parse TeX author metadata structurally and normalize addresses against the backend contract.
2. Cross-check ambiguous comma-separated author lists against ordered arXiv metadata instead of guessing people from affiliation prose.
3. Convert only known source-content boundary violations into a failed item and continue the batch; preserve infrastructure and programming failures for retry.
4. Resume from a strictly canonical, monotonic Redis checkpoint written only after the corresponding Kafka result and progress messages are acknowledged.
5. Poll Kafka during long command execution so consumer-group liveness does not depend on a large static timeout.
6. Settle retry exhaustion in the order dead-letter publish, durable terminal failure publish, then source-offset commit.
7. Key all new result records by job ID. Because old random-key results can still arrive from another partition, treat an incomplete completion as a durable intent, accept late item results, and terminalize from database item totals. If the missing results do not arrive within a bounded grace period, a multi-instance-safe reconciler fails the job visibly.
8. Bound optional `metadataAuthors` and the complete command envelope below Kafka's record limit. Over-budget author lists are omitted as whole lists and the parser degrades conservatively.
9. Rebuild Source `job_items` from stored command targets when a user retries a failed or canceled job. Cancellation closes every open item and refreshes job counters from the same item snapshot.

Truncating parsed names was rejected because it would persist false authors. Treating every exception as an item failure was rejected because it would hide Redis, Kafka, disk, and code failures. Trusting worker counters at completion was rejected because progress and item results can arrive on different Kafka partitions. Directly editing the production counters was rejected because it would preserve the missing item.

## Parser and outbound contract

The parser reuses its balanced TeX command scanner to remove complete nested metadata commands, including `thanks`, `affiliation`, `institute`, IEEE membership, `IEEEauthorrefmark`, and related footnotes. Explicit `\and`, TeX line breaks, and semicolon-separated names continue to work.

A cleaned comma-plus-final-`and` segment is split only when its canonical names exactly match the target's ordered `metadataAuthors`. Missing or mismatched metadata produces no author records for that ambiguous segment. Contacts may still be stored at paper level, so this conservative fallback does not lose a syntactically valid address or invent an author relationship.

Email normalization removes only paired TeX math delimiters and validates an ASCII IDNA domain made of legal LDH labels. It preserves a legal dollar sign in the local part and rejects unmatched or illegal domain characters. Before any public author or evidence field is constructed, affiliation tokens and source-path segments containing a normalized at-sign are removed or irreversibly replaced, and evidence context uses `[email redacted]` without retaining a local part or domain. Internal and outbound Pydantic models reject every residual normalized at-sign in author names, affiliations, evidence paths, and contexts. The backend repeats the same fail-closed check before persistence, so an old or foreign producer cannot bypass the encrypted-contact boundary and expose an address through the paper API.

All Python string limits that cross into the Java result handler are measured in UTF-16 code units, matching `String.length()`, rather than Python Unicode code points. Astral text therefore cannot be acknowledged by the worker and rejected later by the backend. Pydantic models enforce these backend limits for names, affiliations, contacts, evidence, document classes, and collection sizes. Canonical author merging constructs a new validated model, so merging cannot bypass the 100-affiliation limit.

`SourceExtractionRunner` converts a Pydantic error into `SOURCE_CONTENT_INVALID` only when every error is a known content-boundary type. Missing fields and model-shape errors still propagate as programming failures. No source text or address is copied into a public error.

The Source downloader runs outside the local parsing deadline and retains its own HTTP timeout and retry semantics. Archive extraction, TeX discovery, and contact parsing run in a fresh `spawn` subprocess inside the parse deadline. On deadline expiry or cancellation, the parent starts cleanup in an independent task, keeps it shielded through repeated cancellation, terminates the subprocess, escalates to kill after two seconds if necessary, joins it, and only then allows the temporary directory to be cleaned or returns an item result. A timed-out or canceled parser therefore cannot keep a background thread alive or recreate deleted TeX files. The runner also checks the timeout context's own expired state, so downloader timeouts and unrelated storage/library `TimeoutError` responses propagate for command retry, while only an expired parsing deadline becomes the bounded `SOURCE_PARSE_TIMEOUT` item result.

## Messaging size boundaries

The backend fetches ordered `paper_authors.raw_name` values with each target. It gives all `metadataAuthors` arrays in one command a 256 KiB UTF-8 JSON budget. A paper's list is included completely or omitted completely; lists are never truncated. The serialized command envelope has a 768 KiB hard limit, leaving space below Kafka's default one MiB record boundary for protocol overhead and headers. Size validation happens before the job and outbox row are created.

After the worker builds a complete `SourceExtractionResult`, it serializes that result with the same aliases used on the wire and enforces a 768 KiB UTF-8 limit before returning it to the command processor. A document whose individually legal fields exceed the aggregate budget becomes a small `SOURCE_CONTENT_INVALID` item result, allowing later targets to continue. Kafka producer failures and other outbound programming errors remain outside this content boundary and still retry the command.

## Durable checkpoint and consumer liveness

Redis stores a versioned document at `camel:worker:source-progress:<command-key>` with `nextIndex`, `success`, `skipped`, and `failed`. The only accepted representation is the worker's exact compact JSON encoding. Values must be non-negative integers, outcome counts must sum to `nextIndex`, and the index must not exceed the command target count.

A Lua compare-and-set accepts only a strictly advancing canonical checkpoint. Competing or stale workers cannot overwrite a later index. A failed advance defers the command instead of acknowledging it. The checkpoint expires after 32 days, survives pause and retry, and is cleared only after terminal publication and the processed marker. A crash before the checkpoint write can repeat at most one item; deterministic result idempotency makes the repeat harmless.

While any metadata, OAI, or Source operation runs, the consumer pauses assigned partitions and calls Kafka `getmany` at the configured heartbeat interval. This resets aiokafka's fetcher-idle timer without delivering another command concurrently. Assignment changes are paused as well. A polling failure cancels the operation, leaves the source offset uncommitted, shuts down the worker, and relies on the process supervisor to restart it so Kafka replays the record. The maximum poll interval remains a backstop rather than the primary liveness mechanism.

Retry-topic waiting uses the same active-poll wrapper. A not-before timestamp may delay forwarding only up to the configured retry delay plus five seconds of clock skew; a malformed, past, or farther-future value is forwarded immediately after its scheduling headers are removed. An otherwise syntactically valid year-3000 timestamp therefore cannot suspend the only worker or exceed the consumer group's poll interval.

## Retry exhaustion and poison commands

Settlement has explicit `ACK`, `DEFER`, `RETRY`, and `DEAD` outcomes. Pause is an uncounted deferral. Final retry and immediate-dead paths publish the dead-letter copy, publish an idempotent `ARXIV_JOB_FAILED`, mark the command processed, clear its Source checkpoint when applicable, and only then commit the source offset. Failure in any durable step leaves the source offset uncommitted.

Malformed envelopes use a bounded, iterative top-level JSON scanner to recover only a valid job ID, command type, bounded idempotency key, and safe trace ID. It skips deeply nested values without recursive decoding, rejects duplicate top-level context fields, never treats result messages as commands, and recognizes future bounded `ARXIV_*` command names. Long result idempotency keys are truncated with a digest so the terminal message remains contract-compatible.

## Backend terminal reconciliation

Every handler transaction locks the job row. Extraction item counts are always derived from `job_items`, not from untrusted cumulative message counters.

When a completion arrives with pending items, the backend keeps the job nonterminal, stores exact authoritative counts, records `ARXIV_JOB_COMPLETION_DEFERRED`, and moves the stage to `AWAITING_ITEM_RESULTS`. Later cumulative progress cannot overwrite this state. Each late extraction result is persisted and then immediately refreshes exact counters from the database; the transaction that persists the last item observes the durable completion intent and terminalizes the job as `SUCCEEDED`, `PARTIALLY_SUCCEEDED`, or `FAILED`.

A scheduled reconciler selects stale deferred completions with `FOR UPDATE SKIP LOCKED`, including defensive zero-item records, updates at most a bounded batch, and records `ARXIV_JOB_FAILED` with `SOURCE_RESULTS_INCOMPLETE`. The grace period is configurable and bounded from five minutes to one day. A single-flight guard prevents overlap in one process; row locks make multiple backend instances safe. Explicit worker failure and user cancellation still take precedence immediately, and late nonterminal messages cannot change a terminal job.

An OAI progress event with an empty checkpoint still advances visible progress but does not overwrite the last durable opaque resumption token. Retry, pause, and failure messages without a newer cursor therefore preserve database observability and disaster-recovery state.

Source retry creation derives the new total from the original stored `parameters.targets`, copies those identities into new `PENDING job_items`, and verifies the inserted batch against that stored target count before publishing the retry outbox record. It never trusts a terminal job counter that earlier reconciliation may have corrected to zero. Retry idempotency keys are rebuilt from the stable root job ID and new job ID instead of recursively appending to the parent key, so an unlimited retry lineage remains unique and within the database limit. Both API cancellation and worker cancellation close open items and replace displayed counters with exact succeeded, skipped, failed, attempted, and total counts; canceled items are terminal but are not reported as attempted work.

## Production recovery

Before deployment, stop the old arXiv worker and require zero lag on every results partition. This is an additional rolling-upgrade barrier for old random-key result records. Back up PostgreSQL and record the exact commit, image, job, Kafka, and Redis state before mutation.

The replay uses the original command identity and target order, but intentionally enriches each target with the current ordered database `metadataAuthors` so the new ambiguity rule can verify the five authors in `2510.13029`. The enriched envelope is checked against the size boundary. Seed the exact canonical checkpoint `{version:1,nextIndex:25,success:25,skipped:0,failed:0}`, ensure the processed marker is absent, and publish one clean replay through the normal outbox path. Targets 26 through 100 run; deterministic result keys make the already persisted targets 27 through 52 no-ops.

Completion requires 100 terminal items, no pending item, no Source checkpoint, zero retry/results lag, no new dead-letter copy, and agreement among database, API, and job UI. Target 26 must contain the four repaired valid addresses observed in its Source package. Target 53 must contain exactly the five metadata-verified authors and its paper-level contact.

## Validation

Tests cover production-shaped TeX, IEEE reference marks, ambiguous author rejection, metadata cross-validation, strict email domains, fail-closed redaction of ASCII/quoted/Unicode email-like text across every public field, Java-compatible UTF-16 boundaries, download-versus-parse timeout classification, repeated cancellation during subprocess cleanup, merged-model revalidation, item isolation, aggregate result size, canonical Redis parsing and Lua monotonicity against real Redis, checkpoint publication order, poll heartbeats during long work, deep poison envelopes, dead-letter callback ordering, command-size boundaries, Source retry item reconstruction, cross-partition completion-before-item ordering, inflated late progress, zero-item timeout reconciliation, API/worker cancellation, exact item-derived terminal counts, and late-message guards. Full worker static checks/tests, backend checks, frontend checks, container/Compose contracts, independent review, and production API/UI verification are required before completion.
