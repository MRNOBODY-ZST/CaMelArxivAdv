# Dashboard Fidelity Ledger

Reviewed on 2026-08-05 against the accepted desktop and mobile concepts. The implementation was loaded from the real Vite application, not a static HTML export.

## Comparison ledger

| Area | Accepted concept | Browser implementation | Result / rationale |
| --- | --- | --- | --- |
| Application shell | Persistent light sidebar, 64px header, compact navigation groups | Uses the licensed Sidebar with header structure with a fixed 256px sidebar, sticky 64px header and the complete required Chinese navigation | Pass. Spacing, border weight, icon scale and selected indigo treatment are materially aligned. |
| Information hierarchy | Title, date control, metric row, task strip, then unequal analytical rows | Same order is preserved. Metric and task containers use shared borders to reduce card noise | Pass. The accepted visual spec explicitly selected a shared-border rail and strip. |
| Bento proportions | Trend dominates; category is secondary; funnel/health are narrow; lower row has unequal spans | Six-column grid uses `3/2/1` spans for both rows | Pass. Browser computed the bento at one column on 390px and six columns at desktop. |
| Metrics and charts | Concept shows empty chart geometry and em-dash metrics | Implementation uses em dashes and named empty states; it does not draw axes, rings or funnels until real analytics data exists | Intentional safe difference. It avoids making placeholder graphics appear like measured data while retaining stable panel geometry. |
| Header controls | Hamburger/brand or title, global search, task status, notification and user control | Desktop exposes all controls. At 390px the task status becomes a compact labelled dot; notification and user controls remain visible | Pass after browser review correction. The compact task control has `aria-label="运行中任务 —"`. |
| Mobile metric behavior | A horizontal metric rail that does not widen the document | Implemented as `overflow-x-auto` with `scroll-snap-type: x mandatory` and snap-start metric cells | Pass. Browser measured `bodyScrollWidth === bodyClientWidth` while the inner rail remained horizontally scrollable. |
| Mobile content order | Analytical panels stack below task/sync state | At 390px task and sync stack within one shared container, followed by trend, category, funnel, activity, engagement and health | Pass. This is the narrow-device continuation of the wider mobile concept. |
| System status | Health section represents live infrastructure state | Calls `/api/v1/system/health`; during isolated frontend QA it rendered “健康检查不可用” with a retry action because the API was intentionally not running | Pass. Failure is explicit and no success state is fabricated. |
| Typography and surfaces | System sans, slate text, white cards on cool gray canvas, subtle borders and shadows | Uses the accepted token values and Tailwind `shadow-xs`/rings with no gradients, glow or glass effects | Pass. Browser screenshots show consistent density and hierarchy. |
| Interaction/accessibility | Drawer, menus and controls remain keyboard accessible | Mobile drawer opens as one dialog, traps focus and closes on Escape after its 200ms transition | Pass. Automated browser verification observed one dialog when open and zero after Escape. |

## Browser evidence

- Desktop CSS viewport requested: 1536×1024; no document-level horizontal overflow (`bodyScrollWidth === bodyClientWidth`).
- Mobile CSS viewport requested: 390×844; desktop sidebar computed `display: none`; mobile navigation and task controls were visible.
- Mobile bento computed to one column; metric rail computed `scroll-snap-type: x mandatory`.
- Required source markers were present: `sidebar-with-header` and `bento-grid`.
- Browser console: 0 errors and 0 warnings during the responsive and drawer checks.
- Copy audit: “SMTP 已接受不代表最终送达” and “打开事件为估算值” were present; no invented numeric metrics were present.

## Known capture limitation

The in-app browser rasterized the requested 1536px CSS desktop viewport to a 1393px image, so the right edge of that screenshot is clipped even though DOM measurements showed no horizontal overflow. Responsive correctness is based on computed layout measurements plus the rendered capture, not the raster width alone.
