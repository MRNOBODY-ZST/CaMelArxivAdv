import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import DashboardView from '@/views/DashboardView.vue'

vi.mock('@/api/system', () => ({
  getSystemHealth: vi.fn().mockResolvedValue({
    status: 'UP',
    components: { database: 'UP', redis: 'UP', rabbitmq: 'UP' },
  }),
}))

describe('DashboardView', () => {
  it('uses the licensed bento layout and does not invent business metrics', async () => {
    const wrapper = mount(DashboardView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('运行正常'))

    expect(wrapper.get('[data-design-skill="bento-grid"]').classes()).toContain('lg:grid-cols-6')
    expect(wrapper.get('[aria-label="核心指标"]').classes()).toContain('snap-x')
    expect(wrapper.text()).toContain('尚无统计数据')
    expect(wrapper.text()).not.toContain('12,840')
  })
})
