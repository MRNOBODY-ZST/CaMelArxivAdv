import { computed, reactive, watch } from 'vue'
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

  watch(() => route.query, (routeQuery) => {
    const fresh = defaultWindow()
    Object.assign(filter, {
      from: text(routeQuery.from) ?? fresh.from,
      to: text(routeQuery.to) ?? fresh.to,
      categoryId: text(routeQuery.categoryId),
      relation: (text(routeQuery.relation) as AnalyticsQuery['relation']) ?? 'ALL',
      jobId: text(routeQuery.jobId),
      userId: text(routeQuery.userId),
      domain: text(routeQuery.domain),
      confidence: text(routeQuery.confidence) as AnalyticsQuery['confidence'],
    })
  }, { deep: true })

  const query = computed<AnalyticsQuery>(() => Object.fromEntries(
    Object.entries(filter).filter(([, value]) => value !== undefined && value !== ''),
  ) as unknown as AnalyticsQuery)

  async function sync(): Promise<boolean> {
    const before = route.fullPath
    await router.replace({ query: { ...query.value } })
    return route.fullPath !== before
  }

  async function reset(): Promise<boolean> {
    const fresh = defaultWindow()
    Object.assign(filter, {
      ...fresh, categoryId: undefined, relation: 'ALL', jobId: undefined,
      userId: undefined, domain: undefined, confidence: undefined,
    })
    return sync()
  }

  return { filter, query, reset, sync }
}
