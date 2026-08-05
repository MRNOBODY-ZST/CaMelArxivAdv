import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AppShell from '@/layouts/AppShell.vue'

describe('AppShell', () => {
  it('retains the licensed sidebar-with-header DesignSkill structure', () => {
    const wrapper = mount(AppShell, {
      slots: { default: '<div data-testid="page-content">Page</div>' },
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    expect(wrapper.attributes('data-design-skill')).toBe('sidebar-with-header')
    expect(wrapper.find('[data-testid="desktop-sidebar"]').classes()).toContain('lg:flex')
    expect(wrapper.find('[data-testid="mobile-navigation"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="mobile-task-status"]').attributes('aria-label')).toBe('运行中任务 —')
    expect(wrapper.get('[data-testid="page-content"]').text()).toBe('Page')
  })
})
