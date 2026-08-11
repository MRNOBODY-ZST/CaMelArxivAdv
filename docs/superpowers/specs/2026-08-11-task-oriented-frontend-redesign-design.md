# Task-Oriented Frontend Redesign

## Status

Approved automatically by the user on 2026-08-11. This document narrows the redesign to the shared application shell and the authenticated home dashboard so that the highest-frequency entry experience improves without changing backend contracts or unrelated feature pages.

## Problem

The current dashboard exposes many facts with nearly equal visual weight: business metrics, job status, freshness, three charts, future mail messaging, metric definitions, and system health. Users can inspect the page but cannot quickly answer three operational questions:

1. What needs attention now?
2. What should I do next?
3. Where can I verify the result?

The sidebar also displays more than twenty destinations at once. Its domain grouping is correct, but the density makes the product feel like a collection of modules instead of one workflow.

## Audience and Success Criteria

The primary audience is a research-outreach operator who may not know the platform architecture. Administrators and analysts remain supported, but the home screen prioritizes the operator's end-to-end workflow.

The redesign succeeds when:

- a first-time authenticated user can identify the primary action within five seconds;
- the home screen visibly communicates the sequence “discover → import and parse → review contacts → prepare outreach”;
- real metrics explain progress without competing with the next action;
- exceptional states appear before charts and methodology;
- the sidebar remains permission-aware but groups secondary destinations behind clear expandable sections;
- no fake search, notification, task count, or mail-delivery data is shown;
- desktop and 390 px mobile layouts retain the same content priority;
- existing routes, permissions, API payloads, chart exports, and accessibility behavior continue to work.

## Considered Approaches

### 1. Visual polish only

Keep the current bento layout and improve color, spacing, and typography. This is low risk but leaves the hierarchy and “what next?” problem unchanged.

### 2. Task-oriented command center — selected

Recompose the existing API data around an operational sequence. Use one primary next-step block, a compact workflow rail, shared-border metrics, a focused trend chart, and a narrow attention rail. This uses current data honestly and produces the clearest improvement.

### 3. Role-specific dashboards

Render different home screens for operators, analysts, and administrators. This could be useful later, but the current permission model does not express user intent strongly enough and would multiply maintenance cost.

## Information Architecture

### Application shell

- Keep the fixed desktop sidebar and mobile drawer from the existing shell.
- Rename the root destination from “数据总览” to “工作台”.
- Keep the four domain groups: research data, email outreach, analytics, and administration.
- Make domain groups collapsible. The group containing the active route opens automatically; the first two workflow groups are open on the home page; secondary analytics and administration start collapsed.
- Remove inert global-search and notification controls from the header.
- Keep the current page title, breadcrumb, and user menu. System health belongs only in the dashboard attention rail, where it is backed by the live health request.
- Replace the static sidebar safety card with a compact “安全发送” link to mail accounts and system settings only when permitted.

### Dashboard priority order

1. **Page heading:** “工作台” with the copy “按流程推进论文采集、联系人整理与个性化邮件。”
2. **Primary next step:** one visually dominant open panel derived from real state.
3. **Workflow rail:** four stages with real counts/rates and direct route actions.
4. **Attention rail:** active jobs, data freshness, API health, and the first actionable warning.
5. **Evidence:** one main daily-import chart plus a compact source-processing funnel.
6. **Secondary links:** category analysis and metric definitions remain available but do not occupy equal-size cards.

## Next-Step Rules

Rules are evaluated in order and never invent data:

1. If analytics failed, show “恢复数据概览” with a retry action.
2. If active jobs are greater than zero, show “查看正在运行的导入任务” and link to `/jobs`.
3. If imported paper count is zero, show “从论文发现开始” and link to `/arxiv/discovery`.
4. If extraction coverage is below 80%, show “继续解析论文来源” and link to `/papers`.
5. If email discovery rate is below 30%, show “检查联系人提取结果” and link to `/contacts`.
6. Otherwise show “准备个性化邮件活动” and link to `/email/campaigns`.

Metric lookup uses stable metric keys when present and label fallbacks only for compatibility with existing fixtures. Missing metrics render as “暂无数据” rather than zero.

## Components

### `DashboardNextAction.vue`

Receives the resolved next-step presentation and renders the dominant action panel. It owns presentation only and emits retry when the action is a reload.

### `DashboardWorkflow.vue`

Renders the four-stage workflow as an open list with a continuous visual rail. Each stage contains a title, a one-line explanation, one real status value, and one route link. Desktop uses a horizontal sequence; mobile uses a vertical sequence.

### `DashboardAttention.vue`

Renders a compact list of operational signals. Positive state is quiet; warnings use amber; errors use rose. It does not become another card grid.

### `DashboardMetricStrip.vue`

Adapts the template library's “Stats with shared borders” component. It retains real metric definitions via accessible help text and avoids horizontal scrolling on ordinary mobile widths by using a two-column grid.

### `NavigationGroup.vue`

Encapsulates expandable sidebar groups, permission-filtered items, active state, keyboard semantics, and active-route auto-expansion. It uses native buttons and `aria-expanded`.

## Template Library Reuse

The implementation adapts these target-compliant Vue + Tailwind 4.2 templates from the requested library:

- `application-ui/page-examples/home-screens/01__sidebar.vue`: main/attention split and open list rhythm;
- `application-ui/headings/page-headings/01__with-actions.vue`: page heading and primary action alignment;
- `application-ui/data-display/stats/05__with-shared-borders.vue`: compact metric strip;
- `application-ui/lists/feeds/01__simple-with-icons.vue`: workflow rail structure;
- `application-ui/feedback/empty-states/03__with-starting-points.vue`: actionable no-data state;
- `application-ui/application-shells/sidebar-layouts/03__sidebar-with-header.vue`: responsive shell and mobile drawer.

Demo copy, external images, fake links, and sample data are prohibited. Existing Headless UI and Heroicons dependencies are reused.

## Visual System

- Background remains cool slate, not cream: `#f8fafc` outside content and white for primary working surfaces.
- Accent remains brand indigo. Amber and rose are reserved for attention and error states.
- Use open sections, borders, and rails instead of nested cards or a bento grid.
- Page title: 28–30 px, semibold, tight tracking. Section title: 14–16 px, semibold. Operational labels: 12 px, medium. Body: 14 px with 20–22 px line height.
- Primary action has one restrained indigo surface; all other actions are links or secondary buttons.
- Radius stays 8–12 px. Shadows are limited to the primary action and overlays.
- Icons remain Heroicons outline at 20–24 px; status nodes may use solid check/dot symbols for contrast.
- Motion is limited to sidebar disclosure and hover/focus transitions and respects reduced motion.

## Responsive Behavior

- At 1280 px and above, the dashboard uses a main column and a 320 px attention rail.
- From 768–1279 px, the attention rail moves below the primary workflow while metrics remain a four-column strip where possible.
- At 390 px, actions become full width, metrics become two columns, the workflow becomes vertical, chart controls do not overlap, and the sidebar remains a modal drawer.
- No horizontal page scrolling is allowed.

## Data and Error Flow

The dashboard keeps the existing parallel requests to system health and analytics overview. Derived presentation is pure and testable:

- `resolveDashboardNextAction(overview, analyticsError)` selects the next action;
- `dashboardMetric(metrics, key, label)` returns a metric or `undefined`;
- `workflowStages(overview)` maps real analytics values to the four presentation stages.

Request failures do not erase successful data from the other request. Analytics failure promotes the retry action to the top. Health failure appears only in the attention rail and does not block workflow actions.

## Accessibility

- Exactly one `h1` identifies the dashboard.
- Workflow and attention sections have named landmarks.
- Expandable navigation uses real buttons with `aria-expanded` and `aria-controls`.
- Active routes keep `aria-current="page"`.
- Status never relies on color alone.
- Focus order follows visual order; mobile drawer focus restoration remains intact.
- Buttons retain at least 44 px touch targets.

## Testing

- Unit-test next-step priority for analytics failure, active jobs, empty library, low extraction, low email discovery, and outreach-ready states.
- Unit-test workflow stages with missing metrics to ensure “暂无数据” is shown instead of fabricated zeroes.
- Component-test collapsed navigation, active-group auto-expansion, permissions, absolute registered routes, and mobile focus restoration.
- Component-test dashboard hierarchy, one primary action, real API metrics, retry state, and absence of fake controls/data.
- Run lint, typecheck, all Vitest tests, production build, and existing Playwright tests.
- In Edge, verify the workflow `/ → /arxiv/discovery → /jobs → /papers → /contacts`, desktop and 390 px mobile screenshots, no framework overlay, and no relevant warning/error logs.

## Intentional Scope Limits

- Backend APIs and database schemas do not change.
- Feature pages keep their existing content in this iteration; they inherit the simplified shell automatically.
- Role-specific dashboards, saved navigation preferences, global search, notifications, and live job-count polling are deferred until truthful backing data and product requirements exist.
