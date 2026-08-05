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

export interface SearchCriteria {
  categoryIds: string[]
  categoryMode: 'ANY' | 'PRIMARY'
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

export interface PaperPreview {
  arxivId: string
  title: string
  abstractText: string
  authors: Array<{ name: string; affiliations: string[] }>
  primaryCategory: string
  categories: string[]
  publishedAt: string
  updatedAt: string
  doi: string | null
  journalReference: string | null
  pdfUrl: string
  version: number
}

export interface PreviewResult {
  queryHash: string
  criteria: SearchCriteria
  officialTotal: number
  totalIsExact: boolean
  page: number
  pageSize: number
  cacheStatus: 'HIT' | 'MISS' | 'COALESCED'
  annotations: Array<{ field: string; source: 'OFFICIAL' | 'PLATFORM_DERIVED'; applied: boolean; detail: string }>
  papers: PaperPreview[]
}

export interface JobSubmission {
  jobId: string
  status: string
  created: boolean
  idempotencyKey: string
}
