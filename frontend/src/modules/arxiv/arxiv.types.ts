export interface TaxonomyCategory {
  categoryId: string
  categoryName: string
  description: string | null
  alias: boolean
  aliasTarget: string | null
}

export interface TaxonomyArchive {
  archiveId: string
  archiveName: string
  categories: TaxonomyCategory[]
}

export interface TaxonomyGroup {
  groupId: string
  groupName: string
  archives: TaxonomyArchive[]
}

export interface TaxonomyResponse {
  snapshotVersion: string
  sourceType: string
  sourceUrls: string[]
  sourceUpdatedAt: string
  syncedAt: string
  groups: TaxonomyGroup[]
}

export interface SearchCriteriaRequest {
  categoryIds: string[]
  categoryMode: 'ANY' | 'PRIMARY' | 'CROSS_LIST'
  submittedFrom?: string
  submittedTo?: string
  updatedFrom?: string
  updatedTo?: string
  titleKeywords: string
  abstractKeywords: string
  authorKeywords: string
  hasDoi?: boolean
  hasJournalReference?: boolean
  sourceAvailable?: boolean
  sortBy: 'RELEVANCE' | 'LAST_UPDATED_DATE' | 'SUBMITTED_DATE'
  sortOrder: 'ASCENDING' | 'DESCENDING'
  page: number
  pageSize: number
}

export interface NormalizedSearchCriteria {
  categoryIds: string[]
  categoryMode: 'ANY' | 'PRIMARY' | 'CROSS_LIST'
  submittedFrom: string | null
  submittedTo: string | null
  updatedFrom: string | null
  updatedTo: string | null
  titleKeywords: string | null
  abstractKeywords: string | null
  authorKeywords: string | null
  hasDoi: boolean | null
  hasJournalReference: boolean | null
  sourceAvailable: boolean | null
  sortBy: 'RELEVANCE' | 'LAST_UPDATED_DATE' | 'SUBMITTED_DATE'
  sortOrder: 'ASCENDING' | 'DESCENDING'
  page: number
  pageSize: number
}

export interface PaperPreview {
  arxivId: string
  title: string
  abstractText: string
  authors: Array<{ name: string; affiliations: string[] }>
  primaryCategory: string
  categoryIds: string[]
  publishedAt: string
  updatedAt: string
  doi: string | null
  journalReference: string | null
  pdfUrl: string
  versionCount: number
}

export interface PreviewResult {
  queryHash: string
  criteria: NormalizedSearchCriteria
  officialTotal: number
  totalIsEstimate: boolean
  page: number
  pageSize: number
  cacheStatus: 'HIT' | 'MISS' | 'COALESCED'
  filters: Array<{ field: string; source: 'OFFICIAL' | 'PLATFORM_DERIVED'; appliedToPreview: boolean; description: string }>
  papers: PaperPreview[]
}

export interface JobSubmission {
  jobId: string
  status: string
  created: boolean
  idempotencyKey: string
}
