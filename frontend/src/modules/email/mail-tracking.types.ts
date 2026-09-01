import type { PageResponse } from '@/modules/jobs/jobs.types'

export type MailTrackingCallbackScope = 'LOCAL_ONLY' | 'PUBLIC_HTTPS_CONFIGURED'
export type MailSendSource = 'SMTP_DIAGNOSTIC' | 'TEMPLATE_TEST'
export type MailSendStatus = 'SENDING' | 'SMTP_ACCEPTED' | 'FAILED' | 'UNKNOWN'
export type MailOpenClassification = 'UNCLASSIFIED' | 'PREFETCH' | 'IMAGE_PROXY' | 'BOT'

export interface MailTrackingStatus {
  enabled: boolean
  callbackBaseUrl: string
  callbackScope: MailTrackingCallbackScope
  tokenTtlSeconds: number
}

export interface MailSendRecord {
  id: string
  source: MailSendSource
  recipientMasked: string
  subject: string
  smtpAccountName: string | null
  status: MailSendStatus
  failureCategory: string | null
  trackingEnabled: boolean
  createdAt: string
  completedAt: string | null
  trackingExpiresAt: string | null
  rawOpenCount: number
  automatedOpenCount: number
  firstOpenAt: string | null
  lastOpenAt: string | null
  rawClickCount: number
  automatedClickCount: number
  firstClickAt: string | null
  lastClickAt: string | null
}

export interface MailOpenEvent {
  id: number
  occurredAt: string
  classification: MailOpenClassification
  reason: string
}

export interface MailClickLink {
  id: string
  targetUrl: string
  label: string | null
  position: number
  rawClickCount: number
  automatedClickCount: number
  firstClickAt: string | null
  lastClickAt: string | null
}

export interface MailClickEvent {
  id: number
  linkId: string
  occurredAt: string
  classification: MailOpenClassification
  reason: string
}

export interface MailSendRecordDetail {
  record: MailSendRecord
  events: MailOpenEvent[]
  links: MailClickLink[]
  clickEvents: MailClickEvent[]
}

export type MailSendRecordPage = PageResponse<MailSendRecord>
