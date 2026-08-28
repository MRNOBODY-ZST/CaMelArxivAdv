import type { PageResponse } from '@/modules/jobs/jobs.types'

export type MailTrackingCallbackScope = 'LOCAL_ONLY' | 'PUBLIC_HTTPS_UNVERIFIED'
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
}

export interface MailOpenEvent {
  id: number
  occurredAt: string
  classification: MailOpenClassification
  reason: string
}

export interface MailSendRecordDetail {
  record: MailSendRecord
  events: MailOpenEvent[]
}

export type MailSendRecordPage = PageResponse<MailSendRecord>
