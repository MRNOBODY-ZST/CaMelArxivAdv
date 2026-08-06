import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import AnalyticsView from '@/modules/analytics/AnalyticsView.vue'
import { analyticsApi } from '@/modules/analytics/analytics.api'

vi.mock('@/modules/analytics/analytics.api', () => ({
  analyticsApi: {
    ingestion: vi.fn().mockResolvedValue({
      window: { from: '2026-08-01', to: '2026-08-06', dateBasis: 'papers.imported_at', timezone: 'UTC' },
      freshness: { dataThrough: '2026-08-06T10:00:00Z', status: 'CURRENT', generatedAt: '2026-08-06T11:00:00Z' },
      metrics: [], funnel: [], duration: { samples: 0, averageMs: 0, p50Ms: 0, p90Ms: 0, p95Ms: 0, p99Ms: 0 },
      dailyImported: [], extractionStatuses: [], workerErrors: [], jobThroughput: [],
    }),
    papers: vi.fn().mockResolvedValue({
      window: { from: '2026-08-01', to: '2026-08-06', dateBasis: 'papers.imported_at', timezone: 'UTC' },
      freshness: { dataThrough: null, status: 'NO_DATA', generatedAt: '2026-08-06T11:00:00Z' },
      metrics: [], groups: [], archives: [], categories: [], allCategories: [], crossListCategories: [],
      categoryRelations: [], publicationMonths: [], updateMonths: [], authorCounts: [], versionCounts: [], sourceFormats: [],
    }),
    contacts: vi.fn().mockResolvedValue({
      window: { from: '2026-08-01', to: '2026-08-06', dateBasis: 'papers.imported_at', timezone: 'UTC' },
      freshness: { dataThrough: null, status: 'NO_DATA', generatedAt: '2026-08-06T11:00:00Z' },
      metrics: [], confidence: [], domains: [], inferredDomainClasses: [], categoryDiscovery: [],
      documentClasses: [], extractionRules: [], reuseBuckets: [], coauthorPairs: [],
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

  it('reloads the correct payload when vue-router reuses the analytics component', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/analytics/ingestion', component: AnalyticsView, props: { view: 'ingestion' } },
        { path: '/analytics/papers', component: AnalyticsView, props: { view: 'papers' } },
        { path: '/analytics/contacts', component: AnalyticsView, props: { view: 'contacts' } },
      ],
    })
    await router.push('/analytics/ingestion?from=2026-08-01&to=2026-08-06')
    await router.isReady()
    const wrapper = mount({ template: '<RouterView />' }, {
      global: { plugins: [router], stubs: { AnalyticsChart: true } },
    })
    await flushPromises()

    await router.push('/analytics/papers?from=2026-08-01&to=2026-08-06')
    await flushPromises()
    expect(analyticsApi.papers).toHaveBeenCalled()
    expect(wrapper.get('h1').text()).toBe('论文分析')

    await router.push('/analytics/contacts?from=2026-08-01&to=2026-08-06')
    await flushPromises()
    expect(analyticsApi.contacts).toHaveBeenCalled()
    expect(wrapper.get('h1').text()).toBe('联系人分析')
  })

  it('exports the exact query that produced the visible payload, not an unapplied draft', async () => {
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

    await wrapper.get('input[type="date"]').setValue('2026-07-01')
    const exportButton = wrapper.findAll('button').find((button) => button.text().includes('导出 CSV'))
    expect(exportButton).toBeDefined()
    await exportButton!.trigger('click')
    await flushPromises()

    expect(analyticsApi.export).toHaveBeenLastCalledWith('ingestion', expect.objectContaining({
      from: '2026-08-01',
      to: '2026-08-06',
    }))
  })

  it('does not let a delayed response overwrite the reused route payload', async () => {
    let resolveStale!: (value: Awaited<ReturnType<typeof analyticsApi.ingestion>>) => void
    const staleResponse = new Promise<Awaited<ReturnType<typeof analyticsApi.ingestion>>>((resolve) => {
      resolveStale = resolve
    })
    vi.mocked(analyticsApi.ingestion).mockReturnValueOnce(staleResponse)
    vi.mocked(analyticsApi.papers).mockResolvedValueOnce({
      window: { from: '2026-08-01', to: '2026-08-06', dateBasis: 'papers.imported_at', timezone: 'UTC' },
      freshness: { dataThrough: null, status: 'NO_DATA', generatedAt: '2026-08-06T11:00:00Z' },
      metrics: [{ key: 'papers', label: '论文页指标', value: 7, numerator: 7, denominator: 1, unit: 'count', definition: 'paper route' }],
      groups: [], archives: [], categories: [], allCategories: [], crossListCategories: [],
      categoryRelations: [], publicationMonths: [], updateMonths: [], authorCounts: [], versionCounts: [], sourceFormats: [],
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/analytics/ingestion', component: AnalyticsView, props: { view: 'ingestion' } },
        { path: '/analytics/papers', component: AnalyticsView, props: { view: 'papers' } },
      ],
    })
    await router.push('/analytics/ingestion?from=2026-08-01&to=2026-08-06')
    await router.isReady()
    const wrapper = mount({ template: '<RouterView />' }, {
      global: { plugins: [router], stubs: { AnalyticsChart: true } },
    })
    await vi.waitFor(() => expect(analyticsApi.ingestion).toHaveBeenCalled())

    await router.push('/analytics/papers?from=2026-08-01&to=2026-08-06')
    await flushPromises()
    expect(wrapper.text()).toContain('论文页指标')

    resolveStale({
      window: { from: '2026-08-01', to: '2026-08-06', dateBasis: 'papers.imported_at', timezone: 'UTC' },
      freshness: { dataThrough: '2026-08-06T10:00:00Z', status: 'CURRENT', generatedAt: '2026-08-06T11:00:00Z' },
      metrics: [{ key: 'stale', label: '过期采集指标', value: 99, numerator: 99, denominator: 1, unit: 'count', definition: 'stale route' }],
      funnel: [], duration: { samples: 0, averageMs: 0, p50Ms: 0, p90Ms: 0, p95Ms: 0, p99Ms: 0 },
      dailyImported: [], extractionStatuses: [], workerErrors: [], jobThroughput: [],
    })
    await flushPromises()

    expect(wrapper.text()).toContain('论文页指标')
    expect(wrapper.text()).not.toContain('过期采集指标')
  })
})
