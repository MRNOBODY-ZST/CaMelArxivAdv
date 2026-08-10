# Library Selection, Discovery Pagination, and Contact Batch Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add current-page selection to the paper library, make arXiv preview pages navigable, and add atomic batch confirmation/rejection for contacts.

**Architecture:** The discovery view will separate a new search from a page request while reusing the existing paginated preview API. Paper selection remains frontend state bounded by the existing 100-paper extraction contract. Contact batch review adds a typed, transaction-wrapped backend endpoint using the same optimistic version checks and audit path as single verification.

**Tech Stack:** Java 21, Spring WebFlux, R2DBC transactions, Jakarta Validation, JUnit 5, Vue 3, TypeScript, Vitest, Tailwind CSS, Edge browser QA.

## Global Constraints

- Paper and contact select-all actions operate on the visible page only.
- Paper extraction selection remains limited to 100 IDs.
- Contact batches contain 1–100 unique contact and mapping IDs.
- Contact batch writes are all-or-nothing and retain optimistic version checks.
- Full contact email values are never added to list or batch payloads.
- Every new checkbox uses fixed width, height, and shrink behavior.

---

### Task 1: Restore arXiv discovery pagination

**Files:**
- Modify: `frontend/src/modules/arxiv/__tests__/phase3.views.spec.ts`
- Modify: `frontend/src/modules/arxiv/ArxivDiscoveryView.vue`

**Interfaces:**
- Consumes: `arxivApi.preview(SearchCriteriaRequest)` and `DsPagination`.
- Produces: `previewFirstPage()` for new searches and `previewPage(page)` for navigation.

- [ ] **Step 1: Write the failing pagination test**

Mock page 1 and page 2 responses, search once, select a page-1 paper, click `下一页`, and assert the second call contains `page: 2`, page-2 content is visible, and the selected count is preserved.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd frontend
npm test -- --run src/modules/arxiv/__tests__/phase3.views.spec.ts
```

Expected: the pagination control is absent, so `下一页` cannot be found.

- [ ] **Step 3: Implement separate search and page flows**

Import `DsPagination`, compute `previewTotalPages` as `Math.ceil(officialTotal / pageSize)`, make the search button set page 1 and clear selection, and make a page request preserve selection and the previous result if the request fails.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Task 1 test command and confirm all phase-3 tests pass.

### Task 2: Add current-page selection to the paper library

**Files:**
- Modify: `frontend/src/modules/contacts/__tests__/phase4.views.spec.ts`
- Modify: `frontend/src/modules/papers/PapersView.vue`

**Interfaces:**
- Consumes: visible `PaperSummary[]` and existing `papersApi.batchExtract(ids)`.
- Produces: `toggleCurrentPage(checked)` and the existing bounded selected-ID array.

- [ ] **Step 1: Write the failing paper selection test**

Return two papers, click `全选本页`, assert both fixed-size row checkboxes are checked, click `批量解析`, and assert both IDs are submitted in one request. Add a clear-current-page assertion.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd frontend
npm test -- --run src/modules/contacts/__tests__/phase4.views.spec.ts
```

Expected: `全选本页` is absent.

- [ ] **Step 3: Implement header and button selection controls**

Add computed current IDs, all-selected and some-selected state, a bounded page toggle, a fixed-size select-all checkbox, and explicit `全选本页` / `清空本页` controls beside `批量解析`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Task 2 test command and confirm all phase-4 tests pass.

### Task 3: Add an atomic contact batch verification API

**Files:**
- Modify: `backend/src/test/java/com/camel_hub/advertisement/contact/ContactApiTest.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/contact/ContactServiceTest.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/contact/ContactDtos.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/contact/ContactController.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/contact/ContactService.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/contact/config/ContactConfiguration.java`

**Interfaces:**
- Consumes: existing `verify(contactId, VerificationCommand, user, context)` and `TransactionalOperator`.
- Produces: `PATCH /api/v1/contacts/batch-verification` and `BatchVerificationResponse(updatedCount, status)`.

- [ ] **Step 1: Write failing API and service tests**

The API test posts two item commands and asserts the typed response. Service tests assert two optimistic updates and audits execute under `transactions.transactional(...)`; duplicate IDs and batches over 100 fail before repository access.

- [ ] **Step 2: Run focused backend tests and verify RED**

Run:

```bash
cd backend
./gradlew test --tests '*ContactApiTest' --tests '*ContactServiceTest'
```

Expected: batch request/response types and `batchVerify` do not exist.

- [ ] **Step 3: Implement validated transactional batch review**

Add Jakarta-validated request records, unique-ID validation, sequential calls to the existing verify path, and wrap the complete sequence with the injected `TransactionalOperator`. Return the input size and final status only after commit.

- [ ] **Step 4: Run focused backend tests and verify GREEN**

Run the Task 3 Gradle command and confirm both classes pass.

### Task 4: Add contact bulk selection and actions

**Files:**
- Modify: `frontend/src/modules/contacts/__tests__/phase4.views.spec.ts`
- Modify: `frontend/src/modules/contacts/contacts.api.ts`
- Modify: `frontend/src/modules/contacts/ContactListView.vue`

**Interfaces:**
- Consumes: visible `ContactSummary[]` and `contactsApi.batchVerify(items, status)`.
- Produces: current-page selection, batch confirmation, batch rejection, feedback, and refreshed versions.

- [ ] **Step 1: Write the failing contact workflow tests**

Select two current-page contacts, assert `批量标记有效` sends contact/mapping/version tuples with `CONFIRMED`, then cover `REJECTED`. Assert a successful call reloads the current page and clears selection.

- [ ] **Step 2: Run the focused frontend test and verify RED**

Run the Task 2 Vitest command. Expected: batch API mock and controls are absent.

- [ ] **Step 3: Implement the contact batch toolbar**

Add row and header checkboxes, current-page select/clear buttons, confirmed/rejected actions gated by `contact:verify`, a busy state shared by batch buttons, success feedback, and an error path that reloads versions after a conflict.

- [ ] **Step 4: Run the focused frontend test and verify GREEN**

Run the Task 2 Vitest command and confirm the whole phase-4 file passes.

### Task 5: Full verification and local QA

**Files:**
- Modify only if verification exposes a tested defect.

**Interfaces:**
- Consumes: the local Docker deployment and `admin / 123456`.
- Produces: verified pagination, selection, and batch review at `http://127.0.0.1:8080`.

- [ ] **Step 1: Run all automated checks**

Run backend tests, Worker pytest/Ruff/mypy, frontend tests/typecheck/lint/build, and `git diff --check`.

- [ ] **Step 2: Rebuild affected containers**

Reuse existing runtime secrets, rebuild `backend-api` and `frontend`, and verify all nine containers remain healthy.

- [ ] **Step 3: Verify APIs**

Log in locally, smoke the paper/contact/preview endpoints, and verify one batch contact request changes both visible statuses. Restore test contacts only through the opposite audited batch action if needed.

- [ ] **Step 4: Run Edge QA**

Verify discovery next/previous page behavior, paper select-all and clear, contact select-all and both batch buttons, fixed checkbox sizing, pagination, and no console errors.

- [ ] **Step 5: Review and commit**

Review the complete diff, ensure no runtime secrets or screenshots are staged, and commit with `feat: add bulk review workflows`.
