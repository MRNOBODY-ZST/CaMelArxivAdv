import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

import AppShell from '@/layouts/AppShell.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'
import { routes as applicationRoutes } from '@/router'

describe('AppShell', () => {
  it('retains the responsive shell while removing controls without real behavior', () => {
    const wrapper = mountShell([])

    expect(wrapper.attributes('data-design-skill')).toBe('sidebar-with-header')
    expect(wrapper.find('[data-testid="desktop-sidebar"]').classes()).toContain('lg:flex')
    expect(wrapper.find('[data-testid="mobile-navigation"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('工作台')
    expect(wrapper.find('[aria-label="打开全局搜索"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="查看通知"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="mobile-task-status"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="page-content"]').text()).toBe('Page')
  })

  it('shows only navigation entries granted by the live permission set', async () => {
    const wrapper = mountShell(['user:read', 'paper:read'])
    await disclosure(wrapper, '系统管理').trigger('click')

    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('论文库')
    expect(wrapper.text()).not.toContain('角色与权限')
    expect(wrapper.text()).not.toContain('审计日志')
    expect(wrapper.text()).not.toContain('邮件账户')
  })

  it('keeps the shared delivery navigation visible to SMTP readers without campaign access', async () => {
    const wrapper = mountShell(['smtp:read'])
    await disclosure(wrapper, '邮件触达').trigger('click')

    expect(wrapper.text()).toContain('发送记录')
    expect(wrapper.text()).not.toContain('邮件活动')
  })

  it('exposes the dedicated author relationship analytics route', async () => {
    const wrapper = mountShell(['analytics:read'])
    await disclosure(wrapper, '分析洞察').trigger('click')
    const link = wrapper.findAll('a').find((candidate) => candidate.text().includes('作者关系'))

    expect(link).toBeDefined()
    expect(link!.attributes('href')).toBe('/analytics/authors')
  })

  it('uses only absolute registered routes for every sidebar destination', async () => {
    const wrapper = mountShell([
      'analytics:read', 'audit:read', 'campaign:read', 'contact:read_masked', 'paper:read',
      'role:read', 'smtp:read', 'system:manage', 'template:read', 'user:read',
    ])
    for (const label of ['分析洞察', '系统管理']) {
      await disclosure(wrapper, label).trigger('click')
    }
    const destinations = new Set(wrapper.findAll('nav a').map((link) => link.attributes('href')).filter((href): href is string => Boolean(href)))
    const registeredPaths = new Set(applicationRoutes.map((route) => route.path))

    expect([...destinations]).not.toHaveLength(0)
    for (const destination of destinations) {
      expect(destination).toMatch(/^\/(?!\/)/)
      expect(destination).not.toContain('#')
      expect(registeredPaths.has(destination)).toBe(true)
    }
  })

  it('opens workflow navigation first and keeps secondary groups quiet on the workbench', () => {
    const wrapper = mountShell([
      'analytics:read', 'campaign:read', 'contact:read_masked', 'paper:read',
      'smtp:read', 'system:manage', 'template:read',
    ])

    expect(disclosure(wrapper, '研究数据').attributes('aria-expanded')).toBe('true')
    expect(disclosure(wrapper, '邮件触达').attributes('aria-expanded')).toBe('true')
    expect(disclosure(wrapper, '分析洞察').attributes('aria-expanded')).toBe('false')
    expect(disclosure(wrapper, '系统管理').attributes('aria-expanded')).toBe('false')
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

function disclosure(wrapper: ReturnType<typeof mountShell>, label: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().trim() === label)
  if (!button) throw new Error(`Missing navigation disclosure: ${label}`)
  return button
}
