# Selection, Worker, and Contact Reliability Design

**Date:** 2026-08-07

## Problem statement

The arXiv discovery category checkboxes can shrink when their label wraps, preview results require repetitive paper selection, a healthy Worker can be reported as timed out, one source extraction result was rejected for duplicate normalized author names, and one unreadable legacy encrypted contact makes the entire contact page return HTTP 500.

## Decisions

### Fixed-size selection controls

Every checkbox in the affected discovery flow remains a fixed `1rem × 1rem` flex item by adding `shrink-0`. The shared `DsCheckbox` receives the same constraint so other wrapped labels cannot reproduce the defect.

### Page-scoped bulk import

The discovery preview explicitly uses page scope. A header checkbox and “全选本页 / 清空选择” controls update the current selection. “一键导入本页 N 篇” submits exactly the arXiv IDs rendered in the current preview; the existing selected-import and bounded criteria-import actions remain available. This avoids implying that an unloaded official result set has been selected.

### Effective Worker heartbeat

Job reads use the newest timestamp from the job result heartbeat and the live Worker heartbeat associated with that job. A nonterminal job is stale only when this effective heartbeat is absent or outside the stale window. The job state machine and action rules do not change.

### Canonical extraction authors

The Python extractor canonicalizes authors across the discovered TeX corpus by Unicode-normalized, collapsed, case-folded name. Duplicate occurrences merge affiliations and the corresponding-author flag while preserving first-seen order. Candidate email mappings are remapped from original author orders to canonical orders before confidence calculation. This makes Worker output satisfy the backend’s existing uniqueness invariant without weakening backend validation.

When Source ordering differs from the already imported metadata ordering, persistence first matches a source author to an existing paper author by normalized name and only then falls back to positional matching. The returned mapping remains keyed by Source order so extracted contacts link to the intended existing paper-author row without violating either paper-author uniqueness constraint.

### Safe masked contact fallback

Masked views do not need plaintext. If an encrypted display value cannot be authenticated with the active key, masked list/detail responses return the conservative `***@domain` form instead of failing the whole request. Explicit full disclosure continues to require successful decryption and permission. Existing legacy rows are retained so history is not destructively removed.

## Validation

Unit/integration tests cover fixed checkbox classes and page import payloads, newest-heartbeat selection, author deduplication and mapping remap, and masked fallback with invalid ciphertext. After rebuilding local containers, API checks, an actual source extraction job, RabbitMQ/Worker health, contact list responses, and Edge UI interaction validate the complete flow up to—but not including—email delivery.
