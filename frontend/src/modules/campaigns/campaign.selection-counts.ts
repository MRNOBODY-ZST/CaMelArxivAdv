import type { CampaignSelectionMetadata } from './campaigns.types'
import type { SelectionCounts } from './template-migrations.types'

export const selectionFields = [
  { key: 'historicalSelected', label: '历史入选' },
  { key: 'actionableQueued', label: '剩余未发送' },
  { key: 'transferred', label: '已转出' },
  { key: 'migrationBlocked', label: '不在库存边界' },
] as const

export function countLabel(value: number | undefined): string {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0 ? String(value) : '不可用'
}

/** A missing contributor never silently becomes zero; these are page-local sums only. */
export function pageSelectionCounts(items: CampaignSelectionMetadata[]): Partial<SelectionCounts> {
  const result: Partial<SelectionCounts> = {}
  for (const { key } of selectionFields) {
    const values = items.map((item) => item.selectionCounts?.[key])
    if (values.every((value) => countLabel(value) !== '不可用')) {
      const total = values.reduce<number>((sum, value) => sum + value!, 0)
      if (Number.isSafeInteger(total)) result[key] = total
    }
  }
  return result
}
