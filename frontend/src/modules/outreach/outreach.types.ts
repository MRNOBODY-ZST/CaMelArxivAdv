import type { SearchCriteriaRequest } from '@/modules/arxiv/arxiv.types'
import type { PageResponse } from '@/modules/jobs/jobs.types'

export type OutreachTargetMode = 'EMAILS' | 'PAPERS'

export interface OutreachSenderOption {
  id: string
  name: string
  fromEmail: string
  replyToEmail: string | null
  available: boolean
  message: string | null
}

export interface OutreachTemplateOption {
  id: string
  name: string
  currentVersion: number
  available: boolean
  message: string | null
}

export interface OutreachOptions {
  senders: OutreachSenderOption[]
  templates: OutreachTemplateOption[]
  limits: { maxEmails: number; maxPapers: number; searchPaperBudget: number }
  productionHoldReason: string | null
}

export interface OutreachCreateRequest {
  name: string
  purpose: string
  targetMode: OutreachTargetMode
  targetCount: number
  smtpAccountId: string
  templateId: string
  criteria: SearchCriteriaRequest
  confirmation: 'AUTO_OUTREACH_SEND'
  idempotencyKey: string
}

export interface OutreachRun {
  id: string
  name: string
  targetMode: OutreachTargetMode
  targetCount: number
  status: string
  stage: string
  message: string | null
  paperCount: number
  extractedCount: number
  eligibleCount: number
  selectedCount: number
  smtpAccepted: number
  failedCount: number
  unknownCount: number
  campaignId: string | null
  importJobId: string | null
  extractionJobId: string | null
  smtpAccountId: string
  templateId: string
  lockVersion: number
  createdAt: string
  updatedAt: string
  allowedActions: string[]
}

export type OutreachRunPage = PageResponse<OutreachRun>
