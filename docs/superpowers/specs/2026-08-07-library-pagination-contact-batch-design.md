# Library Selection, Discovery Pagination, and Contact Batch Review Design

## Goal

Make high-volume review workflows practical by adding current-page selection to the paper library, restoring real pagination to arXiv discovery results, and supporting safe batch confirmation or rejection of contacts.

## Confirmed Root Cause

The arXiv preview contract already accepts and returns `page` and `pageSize`. `ArxivLegacyQueryBuilder` converts them to the official API `start` offset, and `ArxivQueryNormalizer` includes the page in the cache hash. The frontend result view never renders `DsPagination` and has no page-change handler, so users cannot request any page other than the initial criteria page.

## User Experience

### arXiv discovery

- A new search always resets to page 1 and clears selections from the prior query.
- Pagination appears when the official total exceeds one page.
- Changing pages preserves selected arXiv IDs, enabling selection across preview pages.
- Page changes use the same normalized search criteria and only change `page`.
- The interface shows that the total page count can be an estimate when platform-derived filters are active.

### Paper library

- The list header provides `全选本页` and `清空本页` controls plus a select-all checkbox.
- Selection can persist while paging, but remains bounded by the existing 100-paper extraction limit.
- The existing `批量解析` action continues to submit the selected paper IDs as one extraction job.
- Checkbox dimensions use the fixed `size-4 min-h-4 min-w-4 shrink-0` pattern.

### Contacts

- Each row receives a fixed-size selection checkbox and the table header receives a current-page select-all checkbox.
- Selection is cleared after filtering, pagination, or a successful batch update so version metadata cannot silently become stale.
- Users with `contact:verify` can run `批量标记有效` or `批量标记无效` for 1–100 visible contacts.
- The backend accepts contact ID, mapping ID, and expected version for every item, preserving existing optimistic-concurrency protection.
- A conflict or missing mapping rolls back the entire batch; no partially reviewed page is left behind.
- Each changed contact records the existing `CONTACT_VERIFICATION_UPDATED` audit event.

## Backend Contract

Add `PATCH /api/v1/contacts/batch-verification`, protected by `contact:verify`.

Request:

```json
{
  "items": [
    {
      "contactId": "uuid",
      "mappingId": "uuid",
      "expectedVersion": 0
    }
  ],
  "status": "CONFIRMED"
}
```

`items` must contain 1–100 unique contact IDs and unique mapping IDs. `status` is `CONFIRMED` or `REJECTED`. The response returns `updatedCount` and `status`. Work executes sequentially inside one reactive transaction so optimistic updates and audit records commit or roll back together.

## Error Handling

- Discovery keeps the current visible result if a page request fails and displays the API error.
- Paper selection refuses additions past 100 and communicates the existing batch ceiling.
- Contact batch validation rejects empty, oversized, duplicate, malformed, or unsupported requests before database writes.
- A concurrent contact edit returns the existing contact conflict response; the frontend reloads the page after acknowledging failure so versions are refreshed.

## Testing

- Frontend discovery test proves page 2 sends `page: 2`, renders page 2 data, and preserves prior selections.
- Frontend paper test proves the header checkbox selects and clears all current-page IDs before batch extraction.
- Contact API and service tests prove request validation, permission annotation, transactional batch updates, duplicate rejection, and rollback-compatible error propagation.
- Contact view test proves selection plus confirmed/rejected batch payloads and page reload behavior.
- Existing backend, frontend, and worker suites remain green, followed by local Docker rebuild, API smoke tests, and Edge interaction QA.
