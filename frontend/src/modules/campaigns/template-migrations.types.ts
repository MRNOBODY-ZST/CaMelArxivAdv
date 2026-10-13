export interface PreviewCommand {
  targetTemplateId: string
  targetTemplateVersionId: string
  recipientIds: string[]
}
export interface MigrationCommand extends PreviewCommand {
  expectedLockVersion: number
  expectedTemplateLockVersion: number
  expectedMembershipDigest: string
  idempotencyKey: string
}
export interface MigrationReason { code: string; count: number }
export interface SelectionCounts {
  historicalSelected: number
  actionableQueued: number
  transferred: number
  migrationBlocked: number
}
export interface MigrationPreview extends PreviewCommand {
  sourceCampaignId: string
  expectedLockVersion: number
  expectedTemplateLockVersion: number
  expectedMembershipDigest: string
  counts: SelectionCounts
  ready: boolean
  reasons: MigrationReason[]
}
export interface MigrationResult {
  id: string
  sourceCampaignId: string
  targetCampaignId: string | null
  targetTemplateId: string
  targetTemplateVersionId: string
  selectedCount: number
  migratedCount: number
  status: 'MIGRATED' | 'CONFIG_UPDATED' | 'BLOCKED' | 'NO_MAIL'
  reasons: MigrationReason[]
}
