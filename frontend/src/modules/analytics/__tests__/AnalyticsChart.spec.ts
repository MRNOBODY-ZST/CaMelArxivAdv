import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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
  afterEach(() => vi.unstubAllGlobals())

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

  it('updates animation when the reduced-motion preference changes at runtime', async () => {
    let reduced = false
    const listeners = new Set<EventListenerOrEventListenerObject>()
    const mediaQuery = {
      get matches() { return reduced },
      addEventListener: vi.fn((_type: string, listener: EventListenerOrEventListenerObject) => listeners.add(listener)),
      removeEventListener: vi.fn((_type: string, listener: EventListenerOrEventListenerObject) => listeners.delete(listener)),
    } as unknown as MediaQueryList
    vi.stubGlobal('matchMedia', vi.fn(() => mediaQuery))
    const wrapper = mount(AnalyticsChart, {
      props: { title: '动态图表', option: { series: [{ data: [1] }] } },
    })
    await vi.waitFor(() => expect(setOption).toHaveBeenCalled())

    reduced = true
    for (const listener of listeners) {
      if (typeof listener === 'function') listener(new Event('change'))
      else listener.handleEvent(new Event('change'))
    }

    expect(setOption).toHaveBeenLastCalledWith(expect.objectContaining({ animation: false }), { notMerge: true })
    wrapper.unmount()
    expect(mediaQuery.removeEventListener).toHaveBeenCalled()
  })
})
