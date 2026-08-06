import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import AnalyticsView from '@/modules/analytics/AnalyticsView.vue'
import { analyticsApi } from '@/modules/analytics/analytics.api'

vi.mock('@/modules/analytics/analytics.api', () => ({
  analyticsApi: {
    ingestion: vi.fn().mockResolvedValue({
      window: { from: '2026-08-01', to: '2026-08-06', dateBasis: 'papers.imported_at', timezone: 'UTC' },
      freshness: { dataThrough: '2026-08-06T10:00:00Z', generatedAt: '2026-08-06T11:00:00Z' },
      metrics: [], funnel: [], duration: { samples: 0, averageMs: 0, p50Ms: 0, p90Ms: 0, p95Ms: 0, p99Ms: 0 },
      dailyImported: [], extractionStatuses: [], workerErrors: [], jobThroughput: [],
    }),
    filters: vi.fn().mockResolvedValue({
      minimumDate: null, maximumDate: null, categories: [], jobs: [], users: [], domains: [],
      confidenceLevels: [], relationTypes: [{ id: 'ALL', label: '全部' }],
    }),
    export: vi.fn(),
  },
}))

describe('AnalyticsView', () => {
  it('hydrates filters from the URL and writes applied changes back to it', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/analytics/ingestion', component: AnalyticsView, props: { view: 'ingestion' } }],
    })
    await router.push('/analytics/ingestion?from=2026-08-01&to=2026-08-06&relation=ALL')
    await router.isReady()
    const wrapper = mount(AnalyticsView, {
      props: { view: 'ingestion' },
      global: { plugins: [router], stubs: { AnalyticsChart: true } },
    })
    await flushPromises()

    expect(analyticsApi.ingestion).toHaveBeenCalledWith(expect.objectContaining({ from: '2026-08-01', to: '2026-08-06' }))
    await wrapper.get('input[type="date"]').setValue('2026-07-01')
    await wrapper.get('button.bg-brand-600').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.from).toBe('2026-07-01')
    expect(analyticsApi.ingestion).toHaveBeenLastCalledWith(expect.objectContaining({ from: '2026-07-01' }))
  })
})
