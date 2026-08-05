# Dashboard Visual Specification

## Accepted concepts

- Desktop, 1536×1024: `docs/design/dashboard-concept-desktop.png`
- Mobile responsive continuation, 853×1844: `docs/design/dashboard-concept-mobile.png`
- Approval: automatic approval granted by the user on 2026-08-05.

These images are composition references. All application text, icons, charts, controls, and state remain code-native; the concept images are not shipped as UI assets.

Implemented browser baselines:

- Desktop first viewport: `docs/design/dashboard-implementation-desktop.png`
- Mobile first viewport at 390×844 CSS pixels: `docs/design/dashboard-implementation-mobile.png`
- Fidelity review: `docs/design/DASHBOARD_FIDELITY_LEDGER.md`

## DesignSkill anchors

- Application shell: `Application UI / Application Shells / Sidebar Layouts / Sidebar with header`.
- Dashboard composition: `Marketing / Page Sections / Bento Grids / Two row bento grid with three column second row`.
- Supporting families: Page Headings, Stats, Cards, Tables, Empty States, Breadcrumbs, Buttons, Dropdowns and Notifications.

## First viewport and copy lock

The desktop first viewport contains the persistent navigation sidebar, 64px header, breadcrumb, title, date control, metric rail, task/sync strip, three-column analytical row and lower activity/health row. The mobile viewport replaces the sidebar with a hamburger header and orders the same content vertically.

Allowed first-viewport copy:

- `CaMel Arxiv`
- `概览 / 数据总览`
- `数据总览`
- `全局搜索`
- `运行中任务`
- `管理员`
- `最近 7 天`
- `已收录论文`
- `已解析论文`
- `唯一作者`
- `唯一邮箱`
- `高置信度邮箱`
- `邮箱发现率`
- `当前运行任务`
- `最近一次同步`
- `最近七天论文趋势`
- `分类分布`
- `Source 解析漏斗`
- `最近邮件活动`
- `SMTP 接受率`
- `估算打开率`
- `点击率`
- `系统健康状态`
- `暂无数据`

Sidebar copy follows the navigation list in the accepted project prompt without additions.

## Tokens

- Background: true white `#ffffff` for cards and navigation; cool gray `#f8fafc` for the application canvas.
- Text: `#0f172a` primary, `#475569` secondary, `#64748b` muted.
- Border: `#e2e8f0`; stronger dividers `#cbd5e1`.
- Accent: indigo/blue `#4f6ef7`, hover `#4058d8`, soft selected background `#eef2ff`.
- Positive: green `#059669` with text/icon together; warning amber `#d97706`; destructive red `#dc2626`.
- Radius: 6px controls, 8px panels, 12px only for larger overlays; no pill containers except compact status controls where the reference uses them.
- Shadow: subtle Tailwind `shadow-xs`/`shadow-sm`; no glow or glass effects.
- Spacing: 4px base, with 8/12/16/24/32px working intervals; desktop content gutters 20–24px.
- Motion: 150ms color/border transitions; 200ms drawer/sidebar movement; honor `prefers-reduced-motion`.

## Typography

- Family: system sans stack used by Tailwind (`ui-sans-serif`, `system-ui`, Chinese platform sans fallbacks).
- Page title: 28px/34px, weight 650–700.
- Panel title: 14–16px/22px, weight 600.
- Navigation and controls: 13–14px/20px, weight 450–550.
- Metric value: 22–28px/32px, weight 600; empty metric uses an em dash.
- Captions and data notes: 12px/18px, muted.

## Component and container model

- App shell owns sidebar, mobile drawer, header, breadcrumb and the main scroll container.
- Dashboard owns semantic sections and real API state; it does not duplicate shell controls.
- Metric summaries form a horizontal rail on mobile and a six-column grid on desktop.
- Task and sync use one shared-border strip, not two nested cards.
- Trend, category and funnel panels form the principal three-column bento row; trend spans more width.
- Activity, engagement and health form the second row with unequal spans.
- Tables remain tables at desktop. On mobile, columns scroll within the table container rather than converting all data into cards.
- Loading uses skeleton shapes matching final geometry. Empty and error states keep the same panel height to prevent layout shift.

## Icons

Use Heroicons 24px outline, 1.5px stroke, with 16–20px rendered size in navigation/controls and 24px in empty states. Required metaphors include home, magnifier, document, inbox/download, users, envelope, paper airplane, chart bars, link, server/database, shield, cog, bell, calendar, refresh, chevrons and close. Selected navigation icons use the accent color without changing stroke weight.

## Responsive behavior

- `< 1024px`: persistent sidebar becomes an accessible modal drawer; header keeps hamburger, running-task status, notification and user control.
- `< 768px`: metric summaries become a horizontally scrollable snap rail; analytical panels stack in priority order.
- Touch targets are at least 44×44px. No document-level horizontal overflow is permitted.
- Dialogs use near-full-width gutters on mobile; drawers occupy at most 90vw.
- Charts resize through `ResizeObserver`; labels reduce density rather than becoming unreadable.

## Interaction states

- Every control has visible hover, focus-visible, disabled and busy states.
- Sidebar groups expose `aria-expanded`; active routes expose `aria-current="page"`.
- Modal and drawer trap focus, restore focus on close and close on Escape.
- Toasts use `aria-live`; status never relies on color alone.
- Dashboard health is loaded from `/api/v1/system/health`; an em dash or skeleton is shown before real data arrives. No invented values are displayed.
