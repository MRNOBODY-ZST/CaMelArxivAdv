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

export type CampaignStatus =
  | 'DRAFT' | 'READY_FOR_REVIEW' | 'APPROVED' | 'REJECTED' | 'SCHEDULED'
  | 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELED'

export interface RecipientCounts {
  queued: number
  running: number
  generated: number
  failed: number
  total: number
}

export interface DeliveryCounts {
  queued: number
  connecting: number
  smtpAccepted: number
  temporaryFailure: number
  permanentFailure: number
  bounced: number
  suppressed: number
  unsubscribed: number
  canceled: number
  outcomeUnknown: number
  total: number
}

export interface CampaignView {
  id: string
  name: string
  purpose: string
  status: CampaignStatus
  templateId: string
  templateName: string
  templateVersion: number
  segmentId: string
  segmentName: string
  smtpAccountId: string
  smtpName: string
  mailboxAccountId: string | null
  fromName: string
  fromEmail: string
  replyTo: string
  trackingOpensEnabled: boolean
  trackingClicksEnabled: boolean
  generationStatus: CampaignGenerationStatus
  generationProvider: string | null
  generationModel: string | null
  generationJobId: string | null
  recipientCounts: RecipientCounts
  deliveryCounts: DeliveryCounts
  lockVersion: number
  submittedForReviewAt: string | null
  approvedAt: string | null
  approvedBy: string | null
  rejectedAt: string | null
  rejectedBy: string | null
  rejectionReason: string | null
  scheduledAt: string | null
  startedAt: string | null
  completedAt: string | null
  canceledAt: string | null
  statusChangedAt: string | null
  statusChangedBy: string | null
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
  trackingArtifactsRedacted: boolean
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
  outcomeUnknown: number
  bounced: number
  unsubscribed: number
  replied: number
  rawOpens: number
  humanOpens: number
  automatedOpens: number
  rawClicks: number
  humanClicks: number
  automatedClicks: number
  rates: {
    smtpAcceptance: number
    bounce: number
    unsubscribe: number
    reply: number
  }
  safety: SafetySummary
  createdAt: string
}

export interface SafetySummary {
  runs: number
  messages: number
  smtpAccepted: number
  outcomeUnknown: number
  replies: number
  bounces: number
}

export interface LinkAnalyticsView {
  id: string
  campaignId: string
  campaignName: string
  targetUrl: string
  label: string
  rawClicks: number
  humanClicks: number
  automatedClicks: number
  createdAt: string
}

export interface PreflightCheck { passed: boolean; detail: string }

export interface CampaignPreflight {
  ready: boolean
  checks: Record<string, PreflightCheck>
  counts: Record<string, number>
  estimatedMinutes: number
  digest: string
}

export type SafetyRunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIALLY_FAILED' | 'FAILED' | 'CANCELED'

export interface SafetyProgress {
  total: number
  queued: number
  connecting: number
  smtpAccepted: number
  temporaryFailure: number
  permanentFailure: number
  canceled: number
  outcomeUnknown: number
}

export interface SafetyEventCounts {
  open: number
  click: number
  unsubscribe: number
  reply: number
  autoReply: number
  bounce: number
}

export interface SafetyMessageView {
  id: string
  campaignRecipientId: string
  status: string
  attemptCount: number
  smtpAcceptedAt: string | null
  outcomeUnknownAt: string | null
  outcomeUnknownReason: string | null
}

export interface SafetyRunView {
  id: string
  campaignId: string
  status: SafetyRunStatus
  recipientLimit: number
  destinationMasked: string
  progress: SafetyProgress
  events: SafetyEventCounts
  lockVersion: number
  startedAt: string | null
  completedAt: string | null
  createdAt: string
  messages: SafetyMessageView[]
}

export interface RuntimeStatus {
  personalizationEnabled: boolean
  provider: string
  model: string
  rayConfigured: boolean
  kafkaConfigured: boolean
  liveSmtpAllowed: boolean
  publicMailboxAllowed: boolean
  generationReady: boolean
}

export type SegmentPage = PageResponse<SegmentView>
export type CampaignPage = PageResponse<CampaignView>
export type RecipientPage = PageResponse<CampaignRecipient>
export type DeliveryPage = PageResponse<DeliveryView>
export type CampaignAnalyticsPage = PageResponse<CampaignAnalyticsView>
export type LinkAnalyticsPage = PageResponse<LinkAnalyticsView>
