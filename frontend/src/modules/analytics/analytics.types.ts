export interface AnalyticsQuery {
  from: string
  to: string
  categoryId?: string | undefined
  relation?: 'ALL' | 'PRIMARY' | 'CROSS_LIST' | undefined
  jobId?: string | undefined
  userId?: string | undefined
  domain?: string | undefined
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW' | 'UNMAPPED' | undefined
}

export interface AnalyticsWindow {
  from: string
  to: string
  dateBasis: 'papers.imported_at'
  timezone: 'UTC'
}

export interface Freshness { dataThrough: string | null; status: 'CURRENT' | 'NO_DATA'; generatedAt: string }

export interface Metric {
  key: string
  label: string
  value: number
  numerator: number
  denominator: number
  unit: 'count' | 'rate' | 'average' | 'milliseconds'
  definition: string
}

export interface NamedCount { key: string; label: string; count: number }
export interface DailyCount { date: string; count: number }
export interface DailySeriesPoint { date: string; key: string; label: string; count: number }
export interface Breakdown { key: string; label: string; numerator: number; denominator: number; rate: number }
export interface FunnelStep { key: string; label: string; count: number; previousCount: number; rateFromPrevious: number }
export interface DurationStats { samples: number; averageMs: number; p50Ms: number; p90Ms: number; p95Ms: number; p99Ms: number }

export interface OverviewResponse {
  window: AnalyticsWindow; freshness: Freshness; metrics: Metric[]
  dailyImported: DailyCount[]; primaryCategories: NamedCount[]; funnel: FunnelStep[]; activeJobs: NamedCount[]
}

export interface IngestionResponse {
  window: AnalyticsWindow; freshness: Freshness; metrics: Metric[]; funnel: FunnelStep[]
  duration: DurationStats; dailyImported: DailyCount[]; extractionStatuses: NamedCount[]
  workerErrors: NamedCount[]; jobThroughput: DailySeriesPoint[]
}

export interface PapersResponse {
  window: AnalyticsWindow; freshness: Freshness; metrics: Metric[]
  groups: NamedCount[]; archives: NamedCount[]; categories: NamedCount[]; allCategories: NamedCount[]
  crossListCategories: NamedCount[]; categoryRelations: NamedCount[]
  publicationMonths: NamedCount[]; updateMonths: NamedCount[]; authorCounts: NamedCount[]
  versionCounts: NamedCount[]; sourceFormats: NamedCount[]
}

export interface ContactsResponse {
  window: AnalyticsWindow; freshness: Freshness; metrics: Metric[]
  confidence: NamedCount[]; domains: NamedCount[]; inferredDomainClasses: NamedCount[]
  categoryDiscovery: Breakdown[]; documentClasses: Breakdown[]; extractionRules: NamedCount[]
  reuseBuckets: NamedCount[]; coauthorPairs: NamedCount[]
}

export interface AuthorGraphSummary {
  totalAuthors: number
  totalCollaborations: number
  totalPapers: number
  truncated: boolean
}

export interface AuthorNode {
  id: string
  label: string
  paperCount: number
  collaboratorCount: number
  contactCount: number
}

export interface AuthorEdge {
  source: string
  target: string
  sharedPaperCount: number
}

export interface AuthorsResponse {
  window: AnalyticsWindow
  freshness: Freshness
  summary: AuthorGraphSummary
  nodes: AuthorNode[]
  edges: AuthorEdge[]
}

export interface Option { id: string; label: string }
export interface FilterOptionsResponse {
  minimumDate: string | null; maximumDate: string | null; categories: Option[]; jobs: Option[]
  users: Option[]; domains: Option[]; confidenceLevels: Option[]; relationTypes: Option[]
}

export type AnalyticsView = 'overview' | 'ingestion' | 'papers' | 'contacts' | 'authors'
