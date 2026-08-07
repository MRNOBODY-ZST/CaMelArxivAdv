import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

import AppShell from '@/layouts/AppShell.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'

describe('AppShell', () => {
  it('retains the licensed sidebar-with-header DesignSkill structure', () => {
    const wrapper = mountShell([])

    expect(wrapper.attributes('data-design-skill')).toBe('sidebar-with-header')
    expect(wrapper.find('[data-testid="desktop-sidebar"]').classes()).toContain('lg:flex')
    expect(wrapper.find('[data-testid="mobile-navigation"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="mobile-task-status"]').attributes('aria-label')).toBe('运行中任务 —')
    expect(wrapper.get('[data-testid="page-content"]').text()).toBe('Page')
  })

  it('shows only navigation entries granted by the live permission set', () => {
    const wrapper = mountShell(['user:read', 'paper:read'])

    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('论文库')
    expect(wrapper.text()).not.toContain('角色与权限')
    expect(wrapper.text()).not.toContain('审计日志')
    expect(wrapper.text()).not.toContain('SMTP 账户')
  })

  it('exposes the dedicated author relationship analytics route', () => {
    const wrapper = mountShell(['analytics:read'])
    const link = wrapper.findAll('a').find((candidate) => candidate.text().includes('作者关系'))

    expect(link).toBeDefined()
    expect(link!.attributes('href')).toBe('/analytics/authors')
  })

  it('derives the header title and breadcrumb from route metadata', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().acceptSession({
      accessToken: 'memory-token',
      tokenType: 'Bearer',
      expiresInSeconds: 600,
      user: {
        id: '5d3a9802-375f-42ee-9739-d419299bc4a8',
        username: 'admin',
        displayName: 'Administrator',
        roles: ['SUPER_ADMIN'],
        permissions: ['user:read'],
        mustChangePassword: false,
      },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }, {
        path: '/admin/users',
        component: { template: '<div />' },
        meta: { pageTitle: '用户管理', pageSection: '系统管理' },
      }],
    })
    await router.push('/admin/users')
    await router.isReady()

    const wrapper = mount(AppShell, {
      slots: { default: '<div />' },
      global: { plugins: [pinia, router] },
    })

    expect(wrapper.get('[data-testid="page-title"]').text()).toBe('用户管理')
    expect(wrapper.get('[data-testid="page-breadcrumb"]').text()).toBe('系统管理 / 用户管理')
  })

  it('does not steal desktop focus when the closed mobile drawer route changes', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().acceptSession({
      accessToken: 'memory-token', tokenType: 'Bearer', expiresInSeconds: 600,
      user: {
        id: '5d3a9802-375f-42ee-9739-d419299bc4a8', username: 'admin', displayName: 'Administrator',
        roles: ['SUPER_ADMIN'], permissions: [], mustChangePassword: false,
      },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/next', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(AppShell, {
      attachTo: document.body,
      slots: { default: '<button id="desktop-focus-target">Keep focus</button>' },
      global: { plugins: [pinia, router] },
    })
    const target = wrapper.get('#desktop-focus-target').element as HTMLButtonElement
    target.focus()

    await router.push('/next')
    await nextTick()

    expect(document.activeElement).toBe(target)
    wrapper.unmount()
  })
})

function mountShell(grantedPermissions: Permission[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'memory-token',
    tokenType: 'Bearer',
    expiresInSeconds: 600,
    user: {
      id: '5d3a9802-375f-42ee-9739-d419299bc4a8',
      username: 'analyst',
      displayName: 'Data Analyst',
      roles: ['DATA_ANALYST'],
      permissions: grantedPermissions,
      mustChangePassword: false,
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  return mount(AppShell, {
    slots: { default: '<div data-testid="page-content">Page</div>' },
    global: { plugins: [pinia, router] },
  })
}
