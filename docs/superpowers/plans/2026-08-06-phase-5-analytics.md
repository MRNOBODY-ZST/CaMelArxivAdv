# Phase 5 Analytics Implementation Plan

**Status:** In progress.

**Goal:** Turn the imported paper, Source extraction, author, contact, category, and Job facts already stored in PostgreSQL into permission-protected, source-backed analytics with consistent filters, real ECharts visualizations, aggregate CSV export, freshness/definition metadata, and responsive Chinese UI.

**Controlling source:** PostgreSQL is the only Phase 5 source of truth. `papers`, `paper_imports`, `paper_categories`, `paper_authors`, `extraction_runs`, latest `paper_author_contacts`, `extraction_evidence`, `jobs`, `job_errors`, taxonomy, and users provide the facts. No mock values, external enrichment, full email addresses, or campaign/tracking claims are permitted.

**Metric model:** The global date range defines a UTC platform-import cohort (`papers.imported_at`) so paper, extraction, and contact cards reconcile. Publication/update month charts use arXiv timestamps but remain restricted to that cohort. Operational Job throughput is explicitly labeled as Job-date activity and is not presented as a cohort conversion denominator. Category mode is `ALL`, `PRIMARY`, or `CROSS_LIST`. A contact mapping means the latest mapping per paper/contact, preventing re-extraction history from inflating counts.

**Technology:** Spring Boot WebFlux/R2DBC/PostgreSQL, strict records and validation, audit logging, Vue 3 strict TypeScript, URL query filters, Apache ECharts core modules, DesignSkill adapters, JUnit/Testcontainers, Vitest, Playwright browser QA.

## KPI framework

Primary outcomes:

1. imported paper cohort size;
2. parsed-paper coverage (`latest run status in SUCCEEDED/PARTIALLY_SUCCEEDED ÷ imported papers`);
3. email-discovery coverage (`papers with at least one latest contact mapping ÷ imported papers`).

Drivers:

- Source attempted/available/downloaded/unpacked/TeX discovered stages;
- latest extraction outcome, error code, duration average and P50/P90/P95/P99;
- paper/category/source-format mix;
- confidence, correspondence, document class, extraction rule, domain and cross-paper reuse.

Guardrails and definitions:

- every rate carries numerator, denominator, zero-denominator behavior (`0`, never NaN/Infinity), and a nearby definition;
- complete addresses never enter analytics responses, exports, URLs, logs, chart labels, or tooltips;
- “common provider vs other domain” is labeled **平台推导** and never asserts an institution;
- Source/run metrics use the latest run per paper for funnel/status; operations charts may use all Jobs and are labeled accordingly;
- freshness is derived from the newest relevant fact and the API response generation time.

## Chart map

| View | Question | Family / concrete chart | Data sufficiency and fallback | Palette |
|---|---|---|---|---|
| Overview / ingestion | How far does the cohort progress? | Decomposition / horizontal funnel bars | Always valid with explicit zero stages | single blue root, neutral incomplete context |
| Ingestion | How does daily operational volume/outcome move? | Trend / multi-series line or stacked bar | Day grain; use zero-filled range, prefer 8–90 points; under 8 remains discrete bars | blue + amber hard cap |
| Ingestion | What fails and how long does parsing take? | Ranking bars + percentile scorecard | Top error codes; empty state when none | orange root + neutrals |
| Papers | Which taxonomy areas contain the cohort? | Comparison / sorted horizontal bars (top N) | At least one category; table fallback is unnecessary because exact counts are in tooltips/export | blue root |
| Papers | What is the Primary/Cross-list mix and Source format? | Composition / stacked bar or compact donut | Few bounded categories; explicit denominator | up to five approved roots |
| Papers | How are author/version counts distributed? | Distribution / histogram-style bars | Integer buckets from all cohort papers | olive root |
| Contacts | Where are contacts found and at what confidence? | Comparison + composition / sorted bars and donut | Latest mappings only; empty state when no mappings | blue + gold roots |
| Contacts | Which categories/document classes/rules work? | Ranked bars with rate labels | Top N with numerator and denominator retained | blue root, neutral reference |
| Contacts | How often are contacts reused and authors paired? | Distribution bars + bounded detail table | Aggregate buckets; author pairs limited to top 50 | olive root |

Every chart uses a neutral descriptive title, a subtitle with cohort/date/denominator context, explicit palette, readable axis/tooltips, `aria`, ResizeObserver, loading/empty/error states, and PNG export. CSV export is server-generated from aggregate rows under the same filters and is audited.

## Task 1: Lock API and query contracts with failing tests

- Add filter validation tests: bounded UTC dates, category relation mode, Job/user UUID, normalized domain, confidence and top-N.
- Add controller tests for `analytics:read`, 401/403 boundaries, response shape, zero denominators, CSV headers, safe filenames, and audit invocation.
- Add Testcontainers fixtures with multiple papers, primary/cross-list categories, reruns, contacts reused across papers, correspondence, confidence, verification, rules, Job errors, DOI/journal/source formats and zero-data windows.
- Assert latest-run/latest-mapping semantics and SQL counts before implementation.

## Task 2: Add analytics query indexes and source metadata

- Add Flyway V8 indexes for import date/Job, extraction completion/status/duration, latest mapping, evidence rule and Job actor/type/date paths.
- Extend migration tests without changing earlier migrations.
- Keep existing Phase 4/retention aggregate tables forward compatible for later campaign/tracking refresh work.

## Task 3: Implement backend analytics

- Add `/api/v1/analytics/overview`, `/ingestion`, `/papers`, `/contacts`, and `/filters`, requiring `analytics:read`.
- Return explicit summary numerators/denominators, funnel, daily series, percentiles, taxonomy/month/distribution/source mix, contact domains/confidence/category/document/rules/reuse/collaboration data, definitions and freshness.
- Bind all filters; never construct SQL from user text. Cap breakdowns and date windows.
- Add Redis TTL caching only after correctness; fail open to PostgreSQL if analytics cache is unavailable and keep cache keys free of personal values beyond UUID/HMAC-safe filter values.

## Task 4: Implement audited aggregate export

- Add `/api/v1/analytics/{view}/export?dataset=...` for bounded server-generated UTF-8 CSV.
- Reuse the exact filtered response data, RFC-4180 escape cells, use fixed dataset allowlists and filenames, and set `Content-Disposition`/`nosniff`.
- Audit Actor/view/dataset/window without including email addresses or raw query material.

## Task 5: Build ECharts and filter primitives

- Add a reusable ECharts component using only required core chart modules, ResizeObserver, reduced motion, `aria`, loading/empty/error states, tooltips, legends and PNG export.
- Add a shared analytics filter bar for date/category/relation/Job/domain/confidence/user and synchronize normalized state to Vue Router query parameters.
- Load filter options from the API, avoid sensitive labels, and expose reset/apply behavior with keyboard/focus support.

## Task 6: Build the analytics views

- Replace dashboard placeholder metrics with `/analytics/overview`; retain honest empty states.
- Add `/analytics/ingestion`, `/analytics/papers`, and `/analytics/contacts`, wired into permission-aware navigation.
- Use summary-first layouts, high-signal filters, chart-led explanation and lower-page exact tables/definitions; campaign/link analytics remain visibly out of scope until their fact data exists.
- Add aggregate CSV and chart PNG export actions; never render complete contact addresses.

## Task 7: Verify and accept Phase 5

- Run full backend, Worker, frontend, lint/type/build, Compose and image contracts.
- Rebuild affected containers, query real Phase 3/4 facts, and reconcile API values with direct PostgreSQL reference queries.
- Browser-test desktop 1280×720 and mobile 390×844 for URL restoration, filters, charts, exports, empty/error states, no page overflow and no authenticated-flow console errors.
- Update API, architecture, ERD, operations, security/privacy, README, implementation plan, tasks and DesignSkill mapping.

**Final checkpoint:** mark Phase 5 complete only after source queries, UI values, exports and direct database reconciliations agree and all observed quality gates pass.
