# Author Network and Diverse Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a filter-aware Vis Network coauthor graph with live physics controls and give the existing analytics pages distinct funnel, treemap, and wide-feature layouts.

**Architecture:** A dedicated backend analytics response returns bounded, stable-ID graph nodes and edges from the existing filtered-paper cohort. A lazy-loaded Vue route owns the graph request and renders it through a lifecycle-safe Vis Network component; existing ECharts cards gain true funnel/treemap options and explicit wide-card placement.

**Tech Stack:** Java 21, Spring WebFlux, R2DBC PostgreSQL, JUnit/Testcontainers, Vue 3, TypeScript, Vitest, ECharts 6, Vis Network 10.1, Tailwind CSS 4, Edge browser QA.

## Global Constraints

- Reuse every existing `AnalyticsQuery` filter and the UTC `papers.imported_at` cohort definition.
- Return no email address, encrypted contact value, extraction evidence, or inferred institution in graph data.
- Limit the visible graph to 120 authors and 400 coauthor edges and report truncation explicitly.
- Use `forceAtlas2Based` physics, defaulting off only for an OS reduced-motion preference.
- Lazy-load the graph route so Vis Network is absent from non-author route chunks.
- Preserve chart accessibility, PNG export, loading/error/empty states, and existing CSV behavior.

---

### Task 1: Backend author graph contract and query

**Files:**
- Modify: `backend/src/test/java/com/camel_hub/advertisement/analytics/AnalyticsRepositoryIntegrationTest.java`
- Modify: `backend/src/test/java/com/camel_hub/advertisement/analytics/AnalyticsApiTest.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/analytics/AnalyticsDtos.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/analytics/AnalyticsRepository.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/analytics/AnalyticsService.java`
- Modify: `backend/src/main/java/com/camel_hub/advertisement/analytics/AnalyticsController.java`

**Interfaces:**
- Consumes: `AnalyticsQuery.normalize(Clock)` and `AnalyticsRepository.FILTERED_PAPERS` semantics.
- Produces: `AnalyticsService.authors(AnalyticsQuery)` returning `AnalyticsDtos.AuthorsResponse` through `GET /api/v1/analytics/authors`.

- [ ] **Step 1: Write failing PostgreSQL and API tests**

Add a second-paper Alice mapping in the repository fixture and assert literal graph facts:

```java
var graph = service.authors(query(null)).block();
assertThat(graph.summary().totalAuthors()).isEqualTo(3);
assertThat(graph.nodes()).anySatisfy(node -> {
    if (node.id().equals(UUID.fromString("50000000-0000-0000-0000-000000000001"))) {
        assertThat(node.paperCount()).isEqualTo(2);
        assertThat(node.collaboratorCount()).isEqualTo(2);
    }
});
assertThat(graph.edges()).anySatisfy(edge -> {
    if (edge.source().equals(UUID.fromString("50000000-0000-0000-0000-000000000001"))
            && edge.target().equals(UUID.fromString("50000000-0000-0000-0000-000000000002"))) {
        assertThat(edge.sharedPaperCount()).isEqualTo(1);
    }
});
```

Add an API response test for `/api/v1/analytics/authors` with a literal node and edge, while the existing reflection test proves the new endpoint has `analytics:read`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd backend
./gradlew test --tests '*AnalyticsRepositoryIntegrationTest' --tests '*AnalyticsApiTest'
```

Expected: compilation fails because `AuthorsResponse`, `authors`, and graph records do not exist.

- [ ] **Step 3: Implement bounded graph DTOs and repository queries**

Add records with these exact signatures:

```java
public record AuthorGraphSummary(long totalAuthors, long totalCollaborations, long totalPapers,
                                 boolean truncated) { }
public record AuthorNode(UUID id, String label, long paperCount, long collaboratorCount,
                         long contactCount) { }
public record AuthorEdge(UUID source, UUID target, long sharedPaperCount) { }
public record AuthorsResponse(Window window, Freshness freshness, AuthorGraphSummary summary,
                              List<AuthorNode> nodes, List<AuthorEdge> edges) { }
```

Implement repository methods `authorGraphSummary(filter)`, `authorNodes(filter, 120)`, and `authorEdges(filter, selectedIds, 400)`. Rank nodes by collaborator count descending, paper count descending, lower-cased display name, then UUID. Canonicalize each edge with the lesser UUID as `source`, aggregate distinct shared papers, and use stable ordering.

- [ ] **Step 4: Wire service and controller**

`AnalyticsService.authors` normalizes once, queries summary/nodes/freshness sequentially, then queries edges for the selected node IDs. It builds `truncated` when either all-author or all-edge counts exceed returned counts. Add:

```java
@GetMapping("/authors")
@PreAuthorize("hasAuthority('analytics:read')")
Mono<AnalyticsDtos.AuthorsResponse> authors(@ModelAttribute AnalyticsQuery query) {
    return service.authors(query);
}
```

- [ ] **Step 5: Run tests and verify GREEN**

Run the Task 1 Gradle command. Expected: both analytics test classes pass.

### Task 2: Diverse ECharts options and layout

**Files:**
- Modify: `frontend/src/modules/analytics/__tests__/AnalyticsChart.spec.ts`
- Modify: `frontend/src/modules/analytics/__tests__/AnalyticsView.spec.ts`
- Modify: `frontend/src/modules/analytics/AnalyticsChart.vue`
- Modify: `frontend/src/modules/analytics/chartOptions.ts`
- Modify: `frontend/src/modules/analytics/AnalyticsView.vue`

**Interfaces:**
- Consumes: existing `FunnelStep[]`, `NamedCount[]`, and `AnalyticsChart` props.
- Produces: `funnelOption`, `treemapOption`, and chart definitions with `wide: boolean`.

- [ ] **Step 1: Write failing chart and view tests**

Assert hand-derived chart series types:

```ts
expect((funnelOption(funnel).series as Array<{ type: string }>)[0]?.type).toBe('funnel')
expect((treemapOption(categories).series as Array<{ type: string }>)[0]?.type).toBe('treemap')
```

Mount paper and ingestion views with `AnalyticsChart` stubbed to expose props. Assert “Source 采集漏斗” receives a funnel series, “全部 Category 构成” receives a treemap series, and both designated feature cards receive `wide=true`.

- [ ] **Step 2: Run frontend tests and verify RED**

Run:

```bash
cd frontend
npm test -- --run src/modules/analytics/__tests__/AnalyticsChart.spec.ts src/modules/analytics/__tests__/AnalyticsView.spec.ts
```

Expected: imports or assertions fail because the new option builders and wide prop do not exist.

- [ ] **Step 3: Add real funnel and treemap options**

Register `FunnelChart` and `TreemapChart` in `AnalyticsChart.vue`. Build a descending funnel with labels and conversion tooltips, and a treemap with breadcrumb disabled, visible labels, and value-scaled rectangles. Do not change current bar/donut/time-series behavior.

- [ ] **Step 4: Apply feature-card layout**

Extend `ChartDefinition` with `wide: boolean`, use `lg:col-span-2` on feature charts, replace ingestion `funnelBars` with `funnelOption`, and replace paper all-category bars with `treemapOption`. Promote contact discovery-rate comparison to a full-width card and change the old coauthor card copy to identify it as a ranked preview.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Task 2 Vitest command. Expected: all selected tests pass.

### Task 3: Vis Network graph component

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `frontend/src/modules/analytics/AuthorNetworkGraph.vue`
- Create: `frontend/src/modules/analytics/__tests__/AuthorNetworkGraph.spec.ts`
- Modify: `frontend/src/modules/analytics/analytics.types.ts`

**Interfaces:**
- Consumes: `AuthorNode[]`, `AuthorEdge[]`, loading/error state.
- Produces: a canvas network, physics/search/fit/recompute controls, stabilization state, and selected-author detail.

- [ ] **Step 1: Install the declared graph dependency**

Run:

```bash
cd frontend
npm install vis-network@10.1.0
```

This changes only npm dependency manifests before production component code exists.

- [ ] **Step 2: Write the failing component test**

Mock only Vis Network’s canvas boundary with a fake `Network` that records `setOptions`, `fit`, `stabilize`, `startSimulation`, `stopSimulation`, `destroy`, and event handlers. Mount two nodes and one edge, then assert:

```ts
expect(networkOptions.physics.solver).toBe('forceAtlas2Based')
await wrapper.get('[aria-label="暂停物理计算"]').trigger('click')
expect(stopSimulation).toHaveBeenCalled()
await wrapper.get('select[aria-label="搜索作者"]').setValue('author-a')
expect(wrapper.text()).toContain('共同论文 2')
wrapper.unmount()
expect(destroy).toHaveBeenCalled()
```

Add separate empty-state and reduced-motion cases.

- [ ] **Step 3: Run component test and verify RED**

Run:

```bash
cd frontend
npm test -- --run src/modules/analytics/__tests__/AuthorNetworkGraph.spec.ts
```

Expected: import fails because `AuthorNetworkGraph.vue` does not exist.

- [ ] **Step 4: Implement the lifecycle-safe graph**

Use `Network` and `DataSet` from the Vis Network standalone ESM build. Configure stable IDs, scaled circular nodes, shared-paper edge widths, hover/selection, and:

```ts
physics: {
  enabled: physicsEnabled.value,
  solver: 'forceAtlas2Based',
  stabilization: { enabled: true, iterations: 500, updateInterval: 25, fit: true },
  forceAtlas2Based: {
    gravitationalConstant: -55, centralGravity: 0.015,
    springLength: 115, springConstant: 0.075, damping: 0.42, avoidOverlap: 0.65,
  },
}
```

Map search to `selectNodes([id])` and `focus(id)`. Update selected detail from real props, show strongest neighbors, respond to `startStabilizing`, `stabilizationProgress`, `stabilized`, and destroy the instance on unmount.

- [ ] **Step 5: Run component test and verify GREEN**

Run the Task 3 Vitest command. Expected: all component tests pass.

### Task 4: Dedicated author analytics route and API integration

**Files:**
- Create: `frontend/src/modules/analytics/AuthorsAnalyticsView.vue`
- Create: `frontend/src/modules/analytics/__tests__/AuthorsAnalyticsView.spec.ts`
- Modify: `frontend/src/modules/analytics/analytics.api.ts`
- Modify: `frontend/src/modules/analytics/analytics.types.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/AppShell.vue`
- Modify: `frontend/src/layouts/__tests__/AppShell.spec.ts`

**Interfaces:**
- Consumes: `analyticsApi.authors(query)` and the shared `AnalyticsFilterBar`/`useAnalyticsFilters` contract.
- Produces: `/analytics/authors`, navigation entry “作者关系”, and a lazy graph chunk.

- [ ] **Step 1: Write failing view/navigation tests**

Mock a complete authors response, navigate to `/analytics/authors`, and assert the API receives URL filters, summary values render, `AuthorNetworkGraph` receives nodes/edges, and applying a filter reloads the response. Grant `analytics:read` in the AppShell test and assert the “作者关系” link targets `/analytics/authors`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
cd frontend
npm test -- --run src/modules/analytics/__tests__/AuthorsAnalyticsView.spec.ts src/layouts/__tests__/AppShell.spec.ts
```

Expected: the view and route-facing API do not exist and the navigation assertion fails.

- [ ] **Step 3: Implement API, view, route, and navigation**

Add `analyticsApi.authors(query)`. Build the view with standard heading/freshness/filter/error states, four summary tiles, full-width `AuthorNetworkGraph`, and truncation notice. Add the lazy route with `analytics:read` and a `ShareIcon` navigation item.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 4 Vitest command. Expected: both test files pass.

### Task 5: Full verification, deployment, and Edge QA

**Files:**
- Modify only if verification discovers a tested defect.

**Interfaces:**
- Consumes: production Docker Compose deployment and local admin credentials.
- Produces: verified local author graph and diversified analytics pages at `http://127.0.0.1:8080`.

- [ ] **Step 1: Run all automated checks**

```bash
cd backend && ./gradlew test --rerun-tasks
cd ../worker && uv run pytest -q && uv run ruff check src tests && uv run mypy
cd ../frontend && npm test -- --run && npm run typecheck && npm run lint && npm run build
```

Expected: all commands exit 0.

- [ ] **Step 2: Rebuild affected services without rotating runtime keys**

Reuse the running backend encryption/signing/JWT environment values, rebuild `backend-api` and `frontend`, and confirm all nine project containers are healthy.

- [ ] **Step 3: Verify APIs**

Log in as local admin and assert `/api/v1/analytics/authors` returns HTTP 200, stable unique node IDs, valid endpoints for every edge, node count at most 120, and edge count at most 400. Re-smoke the existing ingestion, papers, contacts, and filters endpoints.

- [ ] **Step 4: Run Edge interaction QA**

The flow under test is: sign in -> open each analytics route -> verify distinct funnel/treemap/wide layouts -> open 作者关系 -> wait for physics stabilization -> select/search an author -> pause/resume -> recompute -> fit -> change a filter -> verify graph reloads without console errors.

Capture desktop and narrow-viewport screenshots outside the repository.

- [ ] **Step 5: Review and commit**

Run `git diff --check`, review the complete staged diff, confirm no secrets or generated QA artifacts are staged, and commit with:

```bash
git commit -m "feat: add physical author relationship analytics"
```
