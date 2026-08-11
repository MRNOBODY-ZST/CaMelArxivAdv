# Task-Oriented Frontend Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the authenticated home experience around a clear research-to-outreach workflow, with one truthful next action, focused evidence, and a quieter permission-aware navigation shell.

**Architecture:** Keep Vue Router, Pinia, existing APIs, Headless UI, Heroicons, ECharts, and Tailwind 4.3. Move dashboard decision logic into pure typed functions, split presentation into focused components, and adapt the requested target-compliant Tailwind templates without shipping their demo content. The shell owns navigation disclosure state; the dashboard owns operational hierarchy and leaves backend contracts unchanged.

**Tech Stack:** Vue 3.5, TypeScript 6, Vue Router 4, Headless UI 1.7, Heroicons 2.2, ECharts 6, Tailwind CSS 4.3, Vitest 4, Vue Test Utils 2.4, Edge Browser runtime.

## Global Constraints

- Accepted visual concept: `/Users/hades/.codex/generated_images/019fd157-f4df-75c1-bb10-d4bcba8a1b20/exec-83e140af-f29a-4de4-9361-3e5db3e5bbd8.png`.
- Preserve all current routes, permission checks, API payloads, chart PNG export, authentication, and mobile drawer focus restoration.
- Show only API-backed metrics and state; never invent job counts, delivery results, notifications, or search behavior.
- Use cool `#f8fafc`, white surfaces, slate text, brand indigo, and amber/rose only for attention/error states.
- Adapt the requested Vue + Tailwind 4.2 template structures to the repository's Tailwind 4.3 conventions and existing dependencies.
- Keep one `h1`, 44 px touch targets, named landmarks, `aria-current`, `aria-expanded`, keyboard access, and reduced-motion support.
- Do not add backend, database, or dependency changes.
- Do not add a bento grid, nested card grid, marketing hero, decorative pills, gradients, photos, illustrations, or fake content.

---

### Task 1: Isolate truthful dashboard decisions

**Files:**
- Create: `frontend/src/views/dashboard/dashboard.model.ts`
- Create: `frontend/src/views/dashboard/__tests__/dashboard.model.spec.ts`

**Interfaces:**
- Consumes: `OverviewResponse`, `Metric`, and `FunnelStep` from `@/modules/analytics/analytics.types`.
- Produces: `resolveDashboardNextAction(overview, analyticsError): DashboardAction`, `dashboardMetric(metrics, key, fallbackLabel): Metric | undefined`, `workflowStages(overview): DashboardWorkflowStage[]`, and `funnelRows(steps): DashboardFunnelRow[]`.

- [ ] **Step 1: Write failing next-action priority tests**

Create literal fixtures and assert the six ordered outcomes. Each test names the production branch it protects. The key assertions are:

```ts
expect(resolveDashboardNextAction(null, '统计概览暂时不可用')).toMatchObject({
  kind: 'retry', title: '恢复数据概览', ctaLabel: '重新加载',
})
expect(resolveDashboardNextAction(overview({ activeJobs: [{ key: 'RUNNING', label: '运行中', count: 2 }] }), null)).toMatchObject({
  href: '/jobs', title: '查看正在运行的导入任务',
})
expect(resolveDashboardNextAction(overview({ metrics: metrics({ papers: 0, parsed: 0, email: 0 }) }), null)).toMatchObject({
  href: '/arxiv/discovery', title: '从论文发现开始',
})
expect(resolveDashboardNextAction(overview({ metrics: metrics({ papers: 81, parsed: 0.284, email: 0.148 }) }), null)).toMatchObject({
  href: '/papers', title: '继续解析论文来源',
})
expect(resolveDashboardNextAction(overview({ metrics: metrics({ papers: 81, parsed: 0.85, email: 0.148 }) }), null)).toMatchObject({
  href: '/contacts', title: '检查联系人提取结果',
})
expect(resolveDashboardNextAction(overview({ metrics: metrics({ papers: 81, parsed: 0.85, email: 0.4 }) }), null)).toMatchObject({
  href: '/email/campaigns', title: '准备个性化邮件活动',
})
```

- [ ] **Step 2: Run the model tests and verify RED**

Run: `npm test -- src/views/dashboard/__tests__/dashboard.model.spec.ts --run`

Expected: FAIL because `dashboard.model.ts` and its exports do not exist.

- [ ] **Step 3: Implement the minimal typed decision model**

Define these public types and ordered rules:

```ts
export interface DashboardAction {
  kind: 'route' | 'retry'
  eyebrow: '下一步'
  title: string
  description: string
  ctaLabel: string
  href?: string
  tone: 'brand' | 'warning'
}

export function dashboardMetric(metrics: Metric[], key: string, fallbackLabel: string): Metric | undefined {
  return metrics.find((metric) => metric.key === key)
    ?? metrics.find((metric) => metric.label === fallbackLabel)
}
```

Implement priority exactly as specified in the design document. Use `cohortPapers`, `parsedCoverage`, and `emailDiscovery`. Missing metrics must not be coerced to zero.

- [ ] **Step 4: Add workflow and funnel tests, then implement them**

Assert four stable workflow stages, direct routes, and `valueLabel: '暂无数据'` for missing metrics. Assert `funnelRows` preserves API order, uses each step count, and calculates bar width against the maximum count without summing counts.

```ts
expect(workflowStages(overview({ metrics: [] }))).toHaveLength(4)
expect(workflowStages(overview({ metrics: [] })).every((stage) => stage.valueLabel === '暂无数据')).toBe(true)
expect(funnelRows([{ key: 'imported', label: '已导入', count: 81, previousCount: 0, rateFromPrevious: 1 }]))
  .toEqual([{ key: 'imported', label: '已导入', count: 81, widthPercent: 100 }])
```

- [ ] **Step 5: Run tests and commit**

Run: `npm test -- src/views/dashboard/__tests__/dashboard.model.spec.ts --run`

Expected: PASS.

Commit:

```bash
git add frontend/src/views/dashboard/dashboard.model.ts frontend/src/views/dashboard/__tests__/dashboard.model.spec.ts
git commit -m "feat: derive dashboard priorities from live data"
```

### Task 2: Make sidebar navigation focused and expandable

**Files:**
- Create: `frontend/src/layouts/NavigationGroup.vue`
- Create: `frontend/src/layouts/__tests__/NavigationGroup.spec.ts`
- Modify: `frontend/src/layouts/AppShell.vue`
- Modify: `frontend/src/layouts/__tests__/AppShell.spec.ts`

**Interfaces:**
- Consumes: group label, permission-filtered navigation items, current path, and `defaultOpen`.
- Produces: accessible disclosure markup with route links and `update:open` only from user interaction.

- [ ] **Step 1: Write failing disclosure behavior tests**

Test the real component with Vue Router. Protect these behaviors:

```ts
expect(wrapper.get('button').attributes('aria-expanded')).toBe('false')
await wrapper.get('button').trigger('click')
expect(wrapper.get('button').attributes('aria-expanded')).toBe('true')
expect(wrapper.get('a[aria-current="page"]').text()).toBe('论文库')
```

Also assert that an active descendant opens the group even when `defaultOpen` is false, and that links remain absolute registered paths.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `npm test -- src/layouts/__tests__/NavigationGroup.spec.ts --run`

Expected: FAIL because `NavigationGroup.vue` does not exist.

- [ ] **Step 3: Implement `NavigationGroup.vue` from the sidebar template**

Use a native button, `ChevronDownIcon`, `RouterLink`, and a short grid-row disclosure transition. The component must expose:

```ts
interface NavigationItem {
  label: string
  href: string
  icon: Component
}
const props = defineProps<{
  id: string
  label: string
  items: readonly NavigationItem[]
  currentPath: string
  defaultOpen?: boolean
}>()
```

Active-route changes set `open` to true. They never force an inactive group closed, so user choices are not lost during the current session.

- [ ] **Step 4: Update `AppShell.vue` tests for the intended simplification**

Change the first shell test to expect “工作台”, disclosure buttons, and no controls named “打开全局搜索” or “查看通知”. Preserve permission filtering, author graph route, absolute route, route metadata, and focus-restoration tests.

Add a test that “研究数据” and “邮件触达” start open on `/`, while “分析洞察” and “系统管理” start closed.

- [ ] **Step 5: Run AppShell tests and verify RED**

Run: `npm test -- src/layouts/__tests__/NavigationGroup.spec.ts src/layouts/__tests__/AppShell.spec.ts --run`

Expected: FAIL because the shell still renders all groups and inert header controls.

- [ ] **Step 6: Refactor `AppShell.vue`**

Replace the render-function sidebar group markup with `NavigationGroup`. Use labels “研究数据”, “邮件触达”, “分析洞察”, and “系统管理”. Keep permission filtering before passing items. Remove inert search, notification, fake task-count, and static health controls. Keep page metadata and the user menu. Live health remains in the dashboard attention rail, where it is backed by `getSystemHealth()`.

- [ ] **Step 7: Run tests and commit**

Run: `npm test -- src/layouts/__tests__/NavigationGroup.spec.ts src/layouts/__tests__/AppShell.spec.ts --run`

Expected: PASS.

Commit:

```bash
git add frontend/src/layouts/NavigationGroup.vue frontend/src/layouts/AppShell.vue frontend/src/layouts/__tests__/NavigationGroup.spec.ts frontend/src/layouts/__tests__/AppShell.spec.ts
git commit -m "feat: focus the application navigation"
```

### Task 3: Build the dashboard's reusable visual hierarchy

**Files:**
- Create: `frontend/src/views/dashboard/DashboardNextAction.vue`
- Create: `frontend/src/views/dashboard/DashboardWorkflow.vue`
- Create: `frontend/src/views/dashboard/DashboardAttention.vue`
- Create: `frontend/src/views/dashboard/DashboardFunnel.vue`
- Create: `frontend/src/views/dashboard/__tests__/dashboard.components.spec.ts`

**Interfaces:**
- Consumes: the public types from `dashboard.model.ts`, `Metric[]`, system health state, freshness, and active job count.
- Produces: presentation-only components with real route links and a retry event.

- [ ] **Step 1: Write failing component tests for visible hierarchy**

Mount real components with literal props. Assert:

```ts
expect(nextAction.get('h2').text()).toBe('检查联系人提取结果')
expect(nextAction.get('a').attributes('href')).toBe('/contacts')
expect(workflow.findAll('li')).toHaveLength(4)
expect(attention.get('[aria-label="系统状态"]').text()).toContain('运行正常')
expect(funnel.findAll('[data-testid="funnel-row"]')).toHaveLength(2)
```

Also assert a retry action emits `retry`, funnel widths use inline percentages, and missing metrics show “暂无数据”.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `npm test -- src/views/dashboard/__tests__/dashboard.components.spec.ts --run`

Expected: FAIL because the dashboard components do not exist.

- [ ] **Step 3: Implement the next action and shared-border workflow statistics**

Adapt Page Heading with Actions and Stats with Shared Borders. The primary action uses one restrained brand-tinted surface. Integrate the three available API metrics into the four workflow stages so each value appears exactly once.

- [ ] **Step 4: Implement workflow, attention, and funnel components**

Adapt Feed with Icons into a four-stage rail. Use an ordered list and real links. Implement attention as a divided list, not cards. Implement the funnel with semantic labels, counts, and CSS width bars; set `aria-label` to include label and count.

- [ ] **Step 5: Run tests, lint the new files, and commit**

Run:

```bash
npm test -- src/views/dashboard/__tests__/dashboard.components.spec.ts --run
npm run lint
```

Expected: PASS with no warnings.

Commit:

```bash
git add frontend/src/views/dashboard
git commit -m "feat: add task-oriented dashboard components"
```

### Task 4: Recompose the authenticated home screen

**Files:**
- Modify: `frontend/src/views/DashboardView.vue`
- Modify: `frontend/src/views/__tests__/DashboardView.spec.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/__tests__/AppShell.spec.ts`

**Interfaces:**
- Consumes: existing `getSystemHealth()`, `analyticsApi.overview()`, chart options, and all Task 1–3 dashboard components.
- Produces: the final `/` workbench with one dominant next action and one primary chart.

- [ ] **Step 1: Replace the bento assertion with failing task-hierarchy tests**

Protect behavior rather than source classes:

```ts
expect(wrapper.get('h1').text()).toBe('工作台')
expect(wrapper.findAll('h1')).toHaveLength(1)
expect(wrapper.get('[aria-label="下一步行动"]').text()).toContain('继续解析论文来源')
expect(wrapper.get('[aria-label="工作流程"]').text()).toContain('个性化触达')
expect(wrapper.get('[aria-label="需要关注"]').text()).toContain('运行任务')
expect(wrapper.text()).not.toContain('邮件模板、审批、活动发送及追踪统计将在后续')
expect(wrapper.find('[data-design-skill="bento-grid"]').exists()).toBe(false)
```

Add an analytics rejection test that clicks “重新加载” and verifies the real API function is called again.

- [ ] **Step 2: Run dashboard tests and verify RED**

Run: `npm test -- src/views/__tests__/DashboardView.spec.ts --run`

Expected: FAIL because the current page title and bento layout remain.

- [ ] **Step 3: Implement the concept composition**

Use this desktop structure:

```vue
<div class="mx-auto max-w-[1520px]">
  <header><!-- 工作台 copy and secondary discovery link --></header>
  <div class="mt-6 grid gap-6 xl:grid-cols-[minmax(0,1fr)_20rem]">
    <div class="min-w-0 space-y-6">
      <DashboardNextAction />
      <DashboardWorkflow />
    </div>
    <DashboardAttention />
  </div>
  <section class="mt-6 grid gap-6 xl:grid-cols-[minmax(0,1.35fr)_minmax(22rem,.85fr)]">
    <AnalyticsChart title="每日论文导入" />
    <DashboardFunnel />
  </section>
  <nav aria-label="深入了解"><!-- six real analytics routes --></nav>
</div>
```

Remove category and funnel ECharts from the home page, methodology cards, future mail copy, and duplicate health card. Keep the daily chart PNG action. Keep successful health and analytics results independent when one request fails.

- [ ] **Step 4: Rename route metadata and shell root label**

Change `/` metadata and navigation label to “工作台”. Do not rename the route name or path.

- [ ] **Step 5: Run focused and related tests, then commit**

Run:

```bash
npm test -- src/views/dashboard src/views/__tests__/DashboardView.spec.ts src/layouts/__tests__/AppShell.spec.ts --run
```

Expected: PASS.

Commit:

```bash
git add frontend/src/views frontend/src/router/index.ts frontend/src/layouts/AppShell.vue frontend/src/layouts/__tests__/AppShell.spec.ts
git commit -m "feat: turn the dashboard into a guided workbench"
```

### Task 5: Polish responsive behavior and global tokens

**Files:**
- Modify: `frontend/src/styles.css`
- Modify: `frontend/src/views/DashboardView.vue`
- Modify: `frontend/src/views/dashboard/DashboardNextAction.vue`
- Modify: `frontend/src/views/dashboard/DashboardWorkflow.vue`
- Modify: `frontend/src/views/dashboard/DashboardAttention.vue`
- Modify: `frontend/src/views/dashboard/DashboardFunnel.vue`
- Modify: `frontend/src/layouts/AppShell.vue`
- Modify: `frontend/src/layouts/NavigationGroup.vue`

**Interfaces:**
- Consumes: accepted concept palette, spacing, and type inventory.
- Produces: consistent desktop/mobile presentation without changing component contracts.

- [ ] **Step 1: Start the frontend through the existing Compose deployment**

Run:

```bash
docker compose -p camel-arxiv -f docker-compose.yml -f docker-compose.dev.yml up -d --build frontend
```

Expected: frontend becomes healthy at `http://127.0.0.1:8080`.

- [ ] **Step 2: Capture desktop evidence in Edge and record the first mismatch ledger**

Use the Browser runtime on `/`. Compare against the accepted concept at the current desktop viewport. Record at least: page hierarchy, sidebar density, primary action, workflow rail, right attention rail, chart/funnel balance, typography, palette, and whitespace.

- [ ] **Step 3: Fix concrete desktop mismatches**

Use existing Tailwind classes and shared CSS tokens. Do not add decorative assets. Ensure the first viewport reveals the primary action, full workflow rail, and start of the evidence section on a typical laptop height.

- [ ] **Step 4: Capture 390 × 844 mobile evidence and fix overflow**

Verify: full-width primary action, two-column metric strip, vertical workflow, attention below workflow, chart controls within bounds, drawer focus, and zero horizontal scrolling.

- [ ] **Step 5: Commit visual polish**

```bash
git add frontend/src/styles.css frontend/src/views/DashboardView.vue frontend/src/views/dashboard frontend/src/layouts/AppShell.vue frontend/src/layouts/NavigationGroup.vue
git commit -m "style: align the workbench with the approved concept"
```

### Task 6: Run the complete verification matrix

**Files:**
- Modify: only files required by failures reproduced in this task

**Interfaces:**
- Consumes: the complete merged frontend redesign.
- Produces: a green automated and rendered QA result.

- [ ] **Step 1: Run all frontend quality gates**

Run:

```bash
npm run lint
npm run typecheck
npm test -- --run
npm run build
npm run test:e2e
```

Expected: all commands exit 0 with no application warnings or errors. The existing Vite chunk-size advisory is acceptable if unchanged.

- [ ] **Step 2: Run the complete Edge target flow**

The flow under test is: `/` → expand/collapse “分析洞察” → click “论文发现” → `/arxiv/discovery` → navigate through `/jobs`, `/papers`, and `/contacts` → return to `/` → primary next action opens the expected route.

Verify page identity, meaningful DOM, no framework overlay, no relevant console warnings/errors, and visible interaction state after every navigation.

- [ ] **Step 3: Perform final visual fidelity comparison**

Use `view_image` on the accepted concept and the latest browser screenshot in the same QA pass. Inspect at least five concrete points: copy hierarchy, layout geometry, typography, palette, container model, icon treatment, responsive behavior, and visible interactions. Fix every material mismatch that is feasible in code.

- [ ] **Step 4: Verify repository and deployment state**

Run:

```bash
git diff --check
git status --short
./scripts/verify-compose.sh
curl -fsS http://127.0.0.1:8080/ >/dev/null
```

Expected: clean diff checks, Compose contract success, and reachable application.

- [ ] **Step 5: Commit any verification fixes**

If verification required changes, stage only those files and commit:

```bash
git commit -m "fix: complete workbench visual verification"
```

If there were no changes, do not create an empty commit.

### Task 7: Integrate and publish

**Files:**
- No source changes expected.

**Interfaces:**
- Consumes: verified feature branch.
- Produces: updated local and remote `main` with the deployment still reachable.

- [ ] **Step 1: Confirm branch history and clean worktree**

Run: `git status --short && git log --oneline -8`

Expected: no unstaged changes and all planned commits visible.

- [ ] **Step 2: Merge to `main` and rerun focused smoke tests**

Fast-forward the verified feature branch into `main`, then run `npm test -- src/views/dashboard src/views/__tests__/DashboardView.spec.ts src/layouts --run` from `frontend/`.

- [ ] **Step 3: Push `main`**

Run: `git push origin main`

Expected: remote `main` advances without force push.

- [ ] **Step 4: Leave the local deployment usable**

Ensure the active Edge tab remains on `http://127.0.0.1:8080/`, root `.env` remains mode `600`, and all resident Compose services remain healthy.
