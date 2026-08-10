# DesignSkill Component Map

## Provenance and licence boundary

The source catalogue was audited from `MRNOBODY-ZST/TailwindCSS-DesignSkill` at commit `b76e370f`. The catalogue downloader and its metadata are MIT-licensed (copyright 2026 Hades). The UI examples originate from Tailwind Plus; the project owner confirmed an active Pro licence on 2026-08-05. Adapted code is used only inside this application and must not be redistributed as a standalone component library.

Catalogue root: `tailwindui_template/templates/`. Source pages use `https://tailwindui.starxg.com/plus/ui-blocks/`. The audited export is Vue/Tailwind 4 and marked `target_compliant` for automatic theme and dark-mode support. CaMel Arxiv intentionally ships the accepted light operations theme, while preserving the source component semantics, focus management, responsive breakpoints and class proportions.

## Page-level composition

| Project page/area | Project component | DesignSkill component | Catalogue source | Vue adapter | Fidelity notes |
| --- | --- | --- | --- | --- | --- |
| Global application shell | Sidebar, mobile drawer, sticky header, search, notification and user menu | **Sidebar with header** | `application-ui/application-shells/sidebar-layouts/03__sidebar-with-header.vue` ([source page](https://tailwindui.starxg.com/plus/ui-blocks/application-ui/application-shells/sidebar)) | `frontend/src/layouts/AppShell.vue` | Preserves `lg:fixed`, `lg:w-*`, `lg:pl-*`, 64px sticky header, modal mobile navigation, Headless UI transitions, navigation row rhythm and subtle borders/shadows. Brand, navigation taxonomy and colors are product-specific. |
| Data overview | Unequal two-row analytical composition | **Two row bento grid with three column second row** | `marketing/page-sections/bento-grids/03__two-row-bento-grid-with-three-column-second-row.vue` ([source page](https://tailwindui.starxg.com/plus/ui-blocks/marketing/sections/bento-grids)) | `frontend/src/views/DashboardView.vue` | Preserves the six-column responsive grid and unequal `col-span-3/2/1` hierarchy. Marketing imagery was semantically replaced by real-data panels and stable empty states; spacing, borders and visual hierarchy remain aligned. |
| Data overview | Shared-border metric rail | **With shared borders** | `application-ui/data-display/stats/05__with-shared-borders.vue` | `frontend/src/views/DashboardView.vue` | Preserves one container with internal dividers instead of equal detached cards. On mobile it becomes a horizontal, contained rail. |
| Data overview | Page title and date control | **With actions** page heading family | `application-ui/headings/page-headings/` | `frontend/src/views/DashboardView.vue` | Keeps left-aligned title/supporting copy and a compact right action. |
| Author contacts | Filter rail, protected data table, evidence dialog | **Simple table**, **Simple modal with dismiss button**, native form controls | `application-ui/lists/tables/01__simple.vue`, `application-ui/overlays/modal-dialogs/04__simple-with-dismiss-button.vue`, `application-ui/forms/` | `frontend/src/modules/contacts/ContactListView.vue` | Keeps compact evidence operations on desktop, contained horizontal table overflow on mobile, explicit masked/full-disclosure states, focus-trapped evidence and status badges. |
| Paper detail | Seven-part record navigation and extraction metrics | **Tabs with underline**, **Basic card**, **Flat pill with dot** | `application-ui/navigation/tabs/01__tabs-with-underline.vue`, `application-ui/layout/cards/01__basic-card.vue`, `application-ui/elements/badges/09__flat-pill-with-dot.vue` | `frontend/src/modules/papers/PaperDetailView.vue` | Uses keyboard tabs for metadata, authors, contacts, categories, versions, extraction runs and raw metadata; extraction size/cleanup evidence remains readable from mobile through desktop. |
| Analytics views | Filter/action rail, metric rail and responsive chart grid | **With actions**, **With shared borders**, **Two row bento grid with three column second row**, **Basic card** | `application-ui/headings/page-headings/`, `application-ui/data-display/stats/05__with-shared-borders.vue`, `marketing/page-sections/bento-grids/03__two-row-bento-grid-with-three-column-second-row.vue`, `application-ui/layout/cards/01__basic-card.vue` | `frontend/src/modules/analytics/AnalyticsView.vue`, `AnalyticsFilterBar.vue`, `AnalyticsMetricGrid.vue`, `AnalyticsChart.vue` | Uses the established compact action hierarchy and white/ring surfaces around real ECharts canvases. Desktop uses two columns; mobile uses one column and contained metric overflow. Loading, empty, per-chart error, PNG and reduced-motion behavior are product-specific adaptations. |
| Email template editor | Metadata cards, mode tabs, rich-text toolbar, safe preview, version/test dialogs | **Basic card**, **Tabs with underline**, **With actions**, **Simple modal with dismiss button** | `application-ui/layout/cards/01__basic-card.vue`, `application-ui/navigation/tabs/01__tabs-with-underline.vue`, `application-ui/headings/page-headings/`, `application-ui/overlays/modal-dialogs/04__simple-with-dismiss-button.vue` | `frontend/src/modules/email/EmailTemplateEditorView.vue`, `TemplateRichTextEditor.vue` | Preserves the operations-console card/action hierarchy while adding Tiptap editing, immutable versions, sample variables and a sandboxed server-rendered desktop/mobile preview. Grid children explicitly allow shrinking at 390px. |
| SMTP administration | Local-only banner, account cards, status badges and secret-preserving modal form | **Basic card**, **Alert with description**, **Flat pill with dot**, **Simple modal with dismiss button** | `application-ui/layout/cards/01__basic-card.vue`, `application-ui/feedback/alerts/01__with-description.vue`, `application-ui/elements/badges/09__flat-pill-with-dot.vue`, `application-ui/overlays/modal-dialogs/04__simple-with-dismiss-button.vue` | `frontend/src/modules/email/SmtpAccountsView.vue` | Passwords are represented only by a sentinel; TLS mode, limits, last test state and local-only policy remain scannable on desktop and mobile. |

## Reusable adapter inventory

| Adapter | DesignSkill source component | Catalogue source | Preserved/adapted characteristics |
| --- | --- | --- | --- |
| `DsButton.vue` | Primary, secondary and icon buttons | `application-ui/elements/buttons/01__primary-buttons.vue`, `02__secondary-buttons.vue`, `04__buttons-with-leading-icon.vue` | Original compact type scale, rounded-md controls, shadow-xs, focus outline and disabled state; adds a busy state. |
| `DsInput.vue` | Input with label/help and validation error | `application-ui/forms/input-groups/02__input-with-label-and-help-text.vue`, `03__input-with-validation-error.vue` | Label/help/error hierarchy, outline treatment, invalid ARIA and disabled state. |
| `DsSelect.vue` | Simple native select | `application-ui/forms/select-menus/01__simple-native.vue` | Native keyboard semantics and source field styling. |
| `DsCheckbox.vue` | List with description | `application-ui/forms/checkboxes/01__list-with-description.vue` | Label/description alignment, native focus and disabled behavior. |
| `DsRadio.vue` | List with description | `application-ui/forms/radio-groups/03__list-with-description.vue` | Native grouped radio semantics and source spacing. |
| `DsSwitch.vue` | Left label and description toggle | `application-ui/forms/toggles/04__with-left-label-and-description.vue` | Headless UI switch semantics, translated thumb and focus-visible ring. |
| `DsBadge.vue` | Flat pill with dot | `application-ui/elements/badges/09__flat-pill-with-dot.vue` | Compact pill is retained only for status; text and dot communicate state together. |
| `DsAlert.vue` | Alert with description | `application-ui/feedback/alerts/01__with-description.vue` | Icon/text layout, semantic alert role, tone-specific ring/background. |
| `DsModal.vue` | Simple modal with dismiss button | `application-ui/overlays/modal-dialogs/04__simple-with-dismiss-button.vue` | Headless UI focus trap/restore, Escape/overlay close and scale/fade transitions. |
| `DsDrawer.vue` | Empty drawer with background overlay | `application-ui/overlays/drawers/03__with-background-overlay.vue` | Fixed overlay, right slide transition, max width, close control and focus containment. |
| `DsDropdown.vue` | Simple dropdown | `application-ui/elements/dropdowns/01__simple.vue` | Headless UI keyboard navigation, origin transition, active/disabled states. |
| `DsTabs.vue` | Tabs with underline | `application-ui/navigation/tabs/01__tabs-with-underline.vue` | Headless UI tab semantics, underline selection and keyboard navigation. |
| `DsTable.vue` | Simple table | `application-ui/lists/tables/01__simple.vue` | Semantic table/caption and horizontally contained mobile overflow. |
| `DsPagination.vue` | Simple card footer pagination | `application-ui/navigation/pagination/03__simple-card-footer.vue` | Previous/next controls, disabled boundaries and explicit page summary. |
| `DsCard.vue` | Basic card | `application-ui/layout/cards/01__basic-card.vue` | White surface, rounded-lg, subtle ring/shadow and configurable internal spacing. |
| `DsEmptyState.vue` | Simple empty state | `application-ui/feedback/empty-states/01__simple.vue` | Centered icon/title/supporting copy/action geometry. |
| `DsToast.vue` | Simple notification | `application-ui/overlays/notifications/01__simple.vue` | Fixed notification region, icon, close action, transition and `aria-live`. |
| `DsBreadcrumb.vue` | Simple with chevrons | `application-ui/navigation/breadcrumbs/03__simple-with-chevrons.vue` | Ordered navigation, home affordance, chevrons and current-page semantics. |
| `DsSkeleton.vue` | Composite from cards and loading geometry | `application-ui/layout/cards/01__basic-card.vue` plus Tailwind animation primitives | No exact catalogue entry exists. Uses the final component geometry, neutral fill and reduced-motion override instead of inventing a separate visual style. |
| `DsTooltip.vue` | Composite from dropdown surface and compact labels | `application-ui/elements/dropdowns/01__simple.vue`, `application-ui/elements/badges/12__small-flat.vue` | No exact catalogue entry exists. Reuses compact dark surface, radius, shadow and hover/focus visibility; native trigger semantics remain owned by the slotted control. |

All adapters live under `frontend/src/components/design-skill/`. No second component framework is used. Heroicons and Headless UI are the same interaction dependencies used by the audited Vue examples.

## Deliberate product adaptations

- Indigo was adjusted to the accepted `#4f6ef7` product accent; neutral colors remain Tailwind slate/gray equivalents.
- The shell source's sample company, teams, avatar and external image URLs were replaced with code-native CaMel Arxiv identity and the required Chinese navigation.
- Dark-mode source classes were not copied into every adapter because the approved v1 operations console is light-only. This is a documented scope choice, not an incompatible component substitution.
- Dashboard and analytics metrics render real API data only. Empty cohorts show named zero/empty states and `NO_DATA` freshness rather than sample figures from source previews.
- Tooltip and skeleton are documented composites because the audited catalogue does not contain exact standalone entries.

## Verification hooks

- `data-design-skill="sidebar-with-header"` identifies the application shell.
- `data-design-skill="bento-grid"` identifies the overview composition.
- `frontend/src/layouts/__tests__/AppShell.spec.ts` protects the responsive shell structure.
- `frontend/src/views/__tests__/DashboardView.spec.ts` protects the unequal bento grid and the no-fake-metrics rule.
- `frontend/src/modules/contacts/__tests__/phase4.views.spec.ts` protects masking, evidence disclosure affordances, verification controls, the seven paper tabs, Source actions and extraction cleanup presentation.
- `frontend/src/modules/analytics/__tests__/AnalyticsView.spec.ts` protects URL hydration and cross-view RouterView reuse; `AnalyticsChart.spec.ts` protects loading/empty/error rendering and chart lifecycle.
- `frontend/src/modules/email/__tests__/email.views.spec.ts` protects template/SMTP states, address masking and maintained rich-image nodes; `frontend/e2e/phase6-email.spec.ts` provisions disposable API fixtures and verifies signed PNG loading, copy-route rebinding and the licensed UI in Microsoft Edge at 1280×720 and 390×844.
- `docs/design/phase4-contacts-desktop.png` and `docs/design/phase4-contacts-mobile.png` record the accepted 1280×720 and 390×844 viewport implementations; the mobile full-page capture is 390×963 because content exceeds one viewport vertically.
