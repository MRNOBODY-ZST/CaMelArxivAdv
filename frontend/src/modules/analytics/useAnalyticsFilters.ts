import { computed, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import type { AnalyticsQuery } from '@/modules/analytics/analytics.types'

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10)
}

function defaultWindow(): Pick<AnalyticsQuery, 'from' | 'to'> {
  const today = new Date()
  const start = new Date(today)
  start.setUTCDate(start.getUTCDate() - 29)
  return { from: isoDate(start), to: isoDate(today) }
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

export function useAnalyticsFilters() {
  const route = useRoute()
  const router = useRouter()
  const defaults = defaultWindow()
  const filter = reactive<AnalyticsQuery>({
    from: text(route.query.from) ?? defaults.from,
    to: text(route.query.to) ?? defaults.to,
    categoryId: text(route.query.categoryId),
    relation: (text(route.query.relation) as AnalyticsQuery['relation']) ?? 'ALL',
    jobId: text(route.query.jobId),
    userId: text(route.query.userId),
    domain: text(route.query.domain),
    confidence: text(route.query.confidence) as AnalyticsQuery['confidence'],
  })

  const query = computed<AnalyticsQuery>(() => Object.fromEntries(
    Object.entries(filter).filter(([, value]) => value !== undefined && value !== ''),
  ) as unknown as AnalyticsQuery)

  async function sync(): Promise<void> {
    await router.replace({ query: { ...query.value } })
  }

  async function reset(): Promise<void> {
    const fresh = defaultWindow()
    Object.assign(filter, {
      ...fresh, categoryId: undefined, relation: 'ALL', jobId: undefined,
      userId: undefined, domain: undefined, confidence: undefined,
    })
    await sync()
  }

  return { filter, query, reset, sync }
}
