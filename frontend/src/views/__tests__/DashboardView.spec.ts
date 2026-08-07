import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import DashboardView from '@/views/DashboardView.vue'

vi.mock('@/api/system', () => ({
  getSystemHealth: vi.fn().mockResolvedValue({
    status: 'UP',
    components: { database: 'UP', redis: 'UP', rabbitmq: 'UP' },
  }),
}))

vi.mock('@/modules/analytics/analytics.api', () => ({
  analyticsApi: { overview: vi.fn().mockResolvedValue({
    window: { from: '2026-07-08', to: '2026-08-06', dateBasis: 'papers.imported_at', timezone: 'UTC' },
    freshness: { dataThrough: '2026-08-06T10:00:00Z', generatedAt: '2026-08-06T11:00:00Z' },
    metrics: [{ key: 'cohortPapers', label: 'Imported papers', value: 2, numerator: 2, denominator: 1, unit: 'count', definition: 'real fixture' }],
    dailyImported: [{ date: '2026-08-06', count: 2 }], primaryCategories: [], funnel: [], activeJobs: [],
  }) },
}))

vi.mock('echarts/core', () => ({ use: vi.fn(), init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), getDataURL: vi.fn() })) }))
vi.mock('echarts/charts', () => ({
  BarChart: {}, FunnelChart: {}, LineChart: {}, PieChart: {}, TreemapChart: {},
}))
vi.mock('echarts/components', () => ({ AriaComponent: {}, GridComponent: {}, LegendComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

describe('DashboardView', () => {
  it('uses the licensed bento layout and renders only API-backed business metrics', async () => {
    const wrapper = mount(DashboardView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('运行正常'))

    expect(wrapper.get('[data-design-skill="bento-grid"]').classes()).toContain('lg:grid-cols-6')
    expect(wrapper.get('[aria-label="核心指标"]').classes()).toContain('snap-x')
    expect(wrapper.text()).toContain('2')
    expect(wrapper.text()).not.toContain('12,840')
  })
})
