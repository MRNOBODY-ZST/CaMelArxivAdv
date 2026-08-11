import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import DashboardView from '@/views/DashboardView.vue'

const mocks = vi.hoisted(() => ({
  getSystemHealth: vi.fn(),
  overview: vi.fn(),
}))

vi.mock('@/api/system', () => ({ getSystemHealth: mocks.getSystemHealth }))
vi.mock('@/modules/analytics/analytics.api', () => ({ analyticsApi: { overview: mocks.overview } }))

vi.mock('echarts/core', () => ({ use: vi.fn(), init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), getDataURL: vi.fn() })) }))
vi.mock('echarts/charts', () => ({
  BarChart: {}, FunnelChart: {}, LineChart: {}, PieChart: {}, TreemapChart: {},
}))
vi.mock('echarts/components', () => ({ AriaComponent: {}, GridComponent: {}, LegendComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

describe('DashboardView', () => {
  beforeEach(() => {
    mocks.getSystemHealth.mockReset().mockResolvedValue({
      status: 'UP', components: { database: 'UP', redis: 'UP', kafka: 'UP' },
    })
    mocks.overview.mockReset().mockResolvedValue(overviewFixture())
  })

  it('turns live metrics into one guided workbench instead of equal-weight cards', async () => {
    const wrapper = await mountDashboard()
    await vi.waitFor(() => expect(wrapper.text()).toContain('运行正常'))

    expect(wrapper.get('h1').text()).toBe('工作台')
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.get('[aria-label="下一步行动"]').text()).toContain('继续解析论文来源')
    expect(wrapper.get('[aria-label="工作流程"]').text()).toContain('个性化触达')
    expect(wrapper.get('[aria-label="需要关注"]').text()).toContain('运行任务')
    expect(wrapper.get('[aria-label="工作流程"]').text()).toContain('81 篇论文')
    expect(wrapper.text()).toContain('每日论文导入')
    expect(wrapper.text()).toContain('来源处理情况')
    expect(wrapper.text()).not.toContain('邮件模板、审批、活动发送及追踪统计将在后续')
    expect(wrapper.find('[data-design-skill="bento-grid"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('12,840')
  })

  it('promotes a retry action when analytics fails and reloads through the real API boundary', async () => {
    mocks.overview.mockRejectedValueOnce(new Error('analytics unavailable'))
    const wrapper = await mountDashboard()
    await vi.waitFor(() => expect(wrapper.text()).toContain('恢复数据概览'))
    mocks.overview.mockResolvedValueOnce(overviewFixture())

    await wrapper.get('[aria-label="下一步行动"] button').trigger('click')

    await vi.waitFor(() => expect(mocks.overview).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(wrapper.text()).toContain('继续解析论文来源'))
  })
})

function overviewFixture() {
  return {
    window: { from: '2026-07-13', to: '2026-08-11', dateBasis: 'papers.imported_at', timezone: 'UTC' },
    freshness: { dataThrough: '2026-08-11T03:20:03Z', generatedAt: '2026-08-11T03:21:00Z', status: 'CURRENT' },
    metrics: [
      { key: 'cohortPapers', label: '已导入论文', value: 81, numerator: 81, denominator: 1, unit: 'count', definition: '当前分析窗口导入论文数' },
      { key: 'parsedCoverage', label: '解析覆盖率', value: 0.284, numerator: 23, denominator: 81, unit: 'rate', definition: '完成正文解析的论文比例' },
      { key: 'emailDiscovery', label: '邮箱发现率', value: 0.148, numerator: 12, denominator: 81, unit: 'rate', definition: '发现邮箱的论文比例' },
    ],
    dailyImported: [{ date: '2026-08-06', count: 2 }],
    primaryCategories: [{ key: 'cs.AI', label: 'Artificial Intelligence', count: 23 }],
    funnel: [
      { key: 'imported', label: '已导入', count: 81, previousCount: 0, rateFromPrevious: 1 },
      { key: 'parsed', label: '已解析', count: 23, previousCount: 81, rateFromPrevious: 23 / 81 },
      { key: 'email', label: '发现邮箱', count: 12, previousCount: 23, rateFromPrevious: 12 / 23 },
    ],
    activeJobs: [],
  }
}

async function mountDashboard() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/arxiv/discovery', component: { template: '<div />' } },
      { path: '/jobs', component: { template: '<div />' } },
      { path: '/papers', component: { template: '<div />' } },
      { path: '/contacts', component: { template: '<div />' } },
      { path: '/email/campaigns', component: { template: '<div />' } },
      { path: '/analytics/ingestion', component: { template: '<div />' } },
      { path: '/analytics/papers', component: { template: '<div />' } },
      { path: '/analytics/contacts', component: { template: '<div />' } },
      { path: '/analytics/authors', component: { template: '<div />' } },
      { path: '/analytics/campaigns', component: { template: '<div />' } },
      { path: '/analytics/links', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return mount(DashboardView, { global: { plugins: [router] } })
}
