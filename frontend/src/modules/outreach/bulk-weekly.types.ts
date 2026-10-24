import type { SearchCriteriaRequest } from '@/modules/arxiv/arxiv.types'
import type { CampaignSelectionMetadata } from '@/modules/campaigns/campaigns.types'
import type { PageResponse } from '@/modules/jobs/jobs.types'

interface OutreachCommandBase {
  name: string
  purpose: string
  smtpAccountId: string
  templateId: string
  acceptDeliverabilityRisk: boolean
  confirmation: 'AUTO_OUTREACH_SEND'
  idempotencyKey: string
}
export interface BulkOutreachCommand extends OutreachCommandBase {
  targetPapers: number
  searchPlan: SearchCriteriaRequest[]
  incremental: boolean
}
export interface WeeklyOutreachCommand extends OutreachCommandBase {
  maxPapers: number
  directions: string[]
}
export interface BulkOutreachChild extends CampaignSelectionMetadata {
  id: string
  status: string
  selectedCount: number
  smtpAccepted: number
  bouncedCount: number
  failedCount: number
  unknownCount: number
}
export interface BulkOutreachRun extends BulkOutreachChild {
  name: string
  targetPapers: number
  stage: string
  message: string | null
  paperCount: number
  extractedCount: number
  excludedCount: number
  noContactCount: number
  smtpAccountId: string
  templateId: string
  incremental: boolean
  acceptDeliverabilityRisk: boolean
  lockVersion: number
  createdAt: string
  updatedAt: string
  allowedActions: string[]
  children: BulkOutreachChild[]
}
export interface WeeklyOutreachSchedule {
  id: string
  name: string
  purpose: string
  maxPapers: number
  smtpAccountId: string
  templateId: string
  directions: string[]
  enabled: boolean
  timezone: string
  dayOfWeek: string
  localTime: string
  nextRunAt: string | null
  lastRunId: string | null
  message: string | null
  acceptDeliverabilityRisk: boolean
  lockVersion: number
  createdAt: string
  updatedAt: string
  allowedActions: string[]
}
export type BulkOutreachPage = PageResponse<BulkOutreachRun>
export type WeeklyOutreachPage = PageResponse<WeeklyOutreachSchedule>
