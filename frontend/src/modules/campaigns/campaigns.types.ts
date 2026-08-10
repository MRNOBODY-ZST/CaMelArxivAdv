import type { PageResponse } from '@/modules/jobs/jobs.types'

export type SegmentRuleField = 'primaryCategory' | 'confidence' | 'verificationStatus' | 'corresponding'

export interface SegmentRule {
  field: SegmentRuleField
  operator: 'equals'
  value: string | boolean
}

export interface SegmentView {
  id: string
  name: string
  description: string | null
  rules: SegmentRule[]
  eligibleCount: number
  createdAt: string
  updatedAt: string
}

export interface EligibleContactPreview {
  contactId: string
  emailDomain: string
  authorName: string
  paperTitle: string
  arxivId: string
  primaryCategory: string
  confidence: string
  verificationStatus: string
  correspondingAuthor: boolean
}

export interface SegmentPreview {
  eligibleCount: number
  sample: EligibleContactPreview[]
}

export type CampaignGenerationStatus =
  | 'NOT_REQUESTED' | 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIALLY_FAILED' | 'FAILED'

export interface RecipientCounts {
  queued: number
  running: number
  generated: number
  failed: number
  total: number
}

export interface CampaignView {
  id: string
  name: string
  purpose: string
  status: string
  templateId: string
  templateName: string
  templateVersion: number
  segmentId: string
  segmentName: string
  smtpAccountId: string
  smtpName: string
  fromName: string
  fromEmail: string
  replyTo: string
  generationStatus: CampaignGenerationStatus
  generationProvider: string | null
  generationModel: string | null
  generationJobId: string | null
  recipientCounts: RecipientCounts
  createdAt: string
  updatedAt: string
}

export interface CampaignRecipient {
  id: string
  authorName: string
  paperTitle: string
  category: string | null
  organization: string | null
  personalizationStatus: string
  subject: string | null
  html: string | null
  text: string | null
  rationale: string | null
  errorCode: string | null
  errorMessage: string | null
  personalizedAt: string | null
  createdAt: string
}

export interface GenerationStart { jobId: string; queuedRecipients: number }

export interface DeliveryView {
  id: string
  campaignId: string
  campaignName: string
  recipientId: string
  authorName: string
  paperTitle: string
  attemptNumber: number
  status: string
  smtpResponseCode: number | null
  smtpResponseSummary: string | null
  failureCategory: string | null
  retryable: boolean
  startedAt: string
  completedAt: string | null
}

export interface CampaignAnalyticsView {
  id: string
  name: string
  status: string
  generationStatus: string
  recipients: number
  smtpAccepted: number
  permanentFailures: number
  humanOpens: number
  humanClicks: number
  automatedOpens: number
  automatedClicks: number
  createdAt: string
}

export interface LinkAnalyticsView {
  id: string
  campaignId: string
  campaignName: string
  targetUrl: string
  label: string
  humanClicks: number
  automatedClicks: number
  createdAt: string
}

export interface RuntimeStatus {
  personalizationEnabled: boolean
  provider: string
  model: string
  rayConfigured: boolean
  rabbitConfigured: boolean
  liveSmtpAllowed: boolean
  generationReady: boolean
}

export type SegmentPage = PageResponse<SegmentView>
export type CampaignPage = PageResponse<CampaignView>
export type RecipientPage = PageResponse<CampaignRecipient>
export type DeliveryPage = PageResponse<DeliveryView>
export type CampaignAnalyticsPage = PageResponse<CampaignAnalyticsView>
export type LinkAnalyticsPage = PageResponse<LinkAnalyticsView>

