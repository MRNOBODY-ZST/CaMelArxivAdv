import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const setOption = vi.fn()
const resize = vi.fn()
const dispose = vi.fn()
const getDataURL = vi.fn(() => 'data:image/png;base64,chart')

vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption, resize, dispose, getDataURL })),
}))
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({
  AriaComponent: {}, GridComponent: {}, LegendComponent: {}, TooltipComponent: {},
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import AnalyticsChart from '@/modules/analytics/AnalyticsChart.vue'

describe('AnalyticsChart', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders data and exposes a PNG export without losing accessible context', async () => {
    const wrapper = mount(AnalyticsChart, {
      props: { title: '每日导入', description: 'UTC 导入日期', option: { series: [{ data: [1, 2] }] } },
    })
    await vi.waitFor(() => expect(setOption).toHaveBeenCalled())
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toContain('每日导入')
    expect(wrapper.get('button').attributes('aria-label')).toContain('PNG')
  })

  it('uses an explicit empty state and does not initialize invented values', () => {
    const wrapper = mount(AnalyticsChart, {
      props: { title: '空图表', option: {}, empty: true },
    })
    expect(wrapper.text()).toContain('当前筛选范围暂无数据')
    expect(setOption).not.toHaveBeenCalled()
  })
})
