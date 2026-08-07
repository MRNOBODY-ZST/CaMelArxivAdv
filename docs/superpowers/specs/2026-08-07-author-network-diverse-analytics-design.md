# Author Network and Diverse Analytics Design

## Goal

Make the analytics area easier to scan by giving each page a distinct visual hierarchy, and add an interactive author-collaboration graph rendered by Vis Network with a real force-directed physics simulation.

## Approved approach

Add a dedicated `/analytics/authors` page and `GET /api/v1/analytics/authors` endpoint. This keeps the graph query, loading state, interaction state, and rendering lifecycle independent from contact analytics while reusing the existing UTC date/category/job/user/domain/confidence filters.

Rejected alternatives:

- Converting the existing `coauthorPairs` labels into nodes in the browser: the labels lack stable author IDs and per-author statistics, and the top-20 pair limit produces a misleading graph.
- Embedding the full graph in `ContactsResponse`: this couples contact charts to a more expensive graph query and makes both surfaces fail or wait together.

## Backend contract

`GET /api/v1/analytics/authors` requires `analytics:read` and accepts the existing `AnalyticsQuery` fields. The response contains:

- the standard analytics window and freshness objects;
- summary counts for all filtered authors, all filtered collaborations, filtered papers, and whether the visible graph is truncated;
- at most 120 nodes, ranked by collaborator count, paper count, and stable name/ID order;
- at most 400 edges among the returned nodes, ranked by shared-paper count and stable endpoint order.

Each node exposes the canonical author UUID, display name, paper count, collaborator count, and latest-mapping contact count. Each edge exposes stable source/target UUIDs and shared-paper count. No email address, encrypted value, evidence text, or other sensitive content is returned.

The query uses the established `filtered_papers` and `latest_mappings` CTEs so all existing filter semantics continue to apply. Limits are server-side safety boundaries; the response reports truncation instead of implying completeness.

## Frontend experience

Add an “作者关系” item to the data-analysis navigation. The page uses the existing filter bar and contains:

1. A compact summary strip for authors, collaboration edges, papers, and visible-graph coverage.
2. A full-width Vis Network canvas with `forceAtlas2Based` physics, stabilization progress, drag/zoom/select interactions, and node/edge scaling based on paper/shared-paper counts.
3. Controls for author search, fit-to-view, recomputing the force layout, and pausing/resuming physics.
4. A selected-author detail panel listing paper count, collaborator count, mapped-contact count, and strongest visible collaborators.
5. Explicit loading, empty, API-error, and truncated-data states.

Physics is enabled by default unless the operating system requests reduced motion. A user can always turn it on explicitly. The component destroys the Network instance and observers on unmount and updates its data without leaking event handlers.

## More varied analytics pages

Keep the existing charts and filters, but remove the uniform two-column-card rhythm:

- Ingestion: render the Source pipeline as a real funnel and make the primary time-series/funnel views full width.
- Papers: render the all-category composition as a treemap and make the primary category landscape full width.
- Contacts: retain quality/domain charts, promote the discovery-rate view to a full-width comparison, and point users to the dedicated interactive author graph instead of treating a short bar list as the complete network.
- Shared chart cards retain accessible labels, PNG export, empty states, and reduced-motion behavior.

## Error and performance behavior

- A failed author request shows an inline retryable error without affecting other analytics routes.
- An empty cohort renders an explanatory graph empty state and disabled graph controls.
- Server limits prevent a dense cohort from freezing the browser; node/edge counts and truncation are visible.
- Stabilization progress is shown while physics runs. Network rendering uses canvas and no HTML labels from server content.
- The graph route is lazy-loaded, so Vis Network is not included in the initial dashboard or non-author analytics chunks.

## Testing

- Backend integration test proves stable author IDs, node statistics, shared-paper edge weights, filters, and limits against PostgreSQL fixtures.
- API test proves the typed response shape and `analytics:read` protection.
- Frontend unit tests prove the API contract, graph lifecycle, physics controls, selection detail, empty state, and diversified chart types/layout.
- Full backend, worker, and frontend checks remain green.
- Edge QA covers login, all four analytics routes, graph stabilization, pause/resume, search/select, fit/recompute, responsive layout, and console health.

## Scope boundaries

This version visualizes coauthorship within imported papers. It does not infer identity across separate canonical author records, compute institutional affiliation, edit graph data, or persist manual node positions. Campaign and link analytics remain outside this change.
