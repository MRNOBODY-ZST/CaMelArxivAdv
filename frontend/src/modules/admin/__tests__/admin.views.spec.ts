import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { Component } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { adminApi } from '@/modules/admin/admin.api'
import AuditLogsView from '@/modules/admin/AuditLogsView.vue'
import RolesView from '@/modules/admin/RolesView.vue'
import UsersView from '@/modules/admin/UsersView.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'

vi.mock('@/modules/admin/admin.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/modules/admin/admin.api')>()
  return { ...actual, adminApi: {
    createRole: vi.fn(),
    createUser: vi.fn(),
    deleteRole: vi.fn(),
    disableUser: vi.fn(),
    enableUser: vi.fn(),
    listAuditLogs: vi.fn(),
    listPermissions: vi.fn(),
    listRoles: vi.fn(),
    listUsers: vi.fn(),
    resetPassword: vi.fn(),
    updateRole: vi.fn(),
    updateUser: vi.fn(),
  } }
})

describe('identity administration views', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders user loading/empty state and hides write actions without exact permission', async () => {
    let release: ((value: Awaited<ReturnType<typeof adminApi.listUsers>>) => void) | undefined
    vi.mocked(adminApi.listUsers).mockReturnValue(new Promise((resolve) => { release = resolve }))
    const wrapper = mountWithPermissions(UsersView, ['user:read'])

    expect(wrapper.find('[data-testid="users-skeleton"]').exists()).toBe(true)
    release?.({ items: [], page: 1, pageSize: 20, total: 0, totalPages: 0 })
    await flushPromises()

    expect(wrapper.text()).toContain('暂无用户')
    expect(wrapper.find('[data-testid="create-user"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="disable-user"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="edit-user"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="reset-user-password"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('passwordHash')
  })

  it('updates user details and resets a password only through explicit dialogs', async () => {
    vi.mocked(adminApi.listUsers).mockResolvedValue({
      items: [{
        id: 'user-1', username: 'analyst', email: 'analyst@example.edu', displayName: 'Data Analyst',
        status: 'ACTIVE', forcePasswordChange: false, tokenVersion: 1,
        lastLoginAt: null, createdAt: '2026-08-05T08:00:00Z', roles: ['DATA_ANALYST'],
      }], page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    vi.mocked(adminApi.listRoles).mockResolvedValue([{
      id: 'role-1', code: 'DATA_ANALYST', name: 'Data Analyst', description: '', systemRole: true,
      userCount: 1, permissions: ['paper:read'], createdAt: '2026-08-05T08:00:00Z',
    }])
    vi.mocked(adminApi.updateUser).mockResolvedValue({} as never)
    vi.mocked(adminApi.resetPassword).mockResolvedValue(undefined)
    const wrapper = mountWithPermissions(UsersView, ['user:read', 'user:update'])
    await flushPromises()

    await wrapper.get('[data-testid="edit-user"]').trigger('click')
    await flushPromises()
    await wrapper.get('#edit-user-display-name').setValue('Lead Analyst')
    await wrapper.get('#edit-user-form').trigger('submit')
    await flushPromises()
    expect(adminApi.updateUser).toHaveBeenCalledWith('user-1', expect.objectContaining({
      email: 'analyst@example.edu', displayName: 'Lead Analyst', roleCodes: ['DATA_ANALYST'],
    }))

    await wrapper.get('[data-testid="reset-user-password"]').trigger('click')
    await wrapper.get('#reset-user-new-password').setValue('Cedar!Galaxy97')
    await wrapper.get('[data-testid="confirm-reset-user-password"]').trigger('click')
    await flushPromises()
    expect(adminApi.resetPassword).toHaveBeenCalledWith('user-1', 'Cedar!Galaxy97')
  })

  it('requires confirmation before disabling a user', async () => {
    vi.mocked(adminApi.listUsers).mockResolvedValue({
      items: [{
        id: 'user-1', username: 'analyst', email: 'analyst@example.edu', displayName: 'Data Analyst',
        status: 'ACTIVE', forcePasswordChange: false, tokenVersion: 1,
        lastLoginAt: null, createdAt: '2026-08-05T08:00:00Z', roles: ['DATA_ANALYST'],
      }],
      page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    vi.mocked(adminApi.disableUser).mockResolvedValue(undefined)
    const wrapper = mountWithPermissions(UsersView, ['user:read', 'user:disable'])
    await flushPromises()

    await wrapper.get('[data-testid="disable-user"]').trigger('click')
    expect(wrapper.text()).toContain('确认停用用户')
    expect(adminApi.disableUser).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="confirm-disable-user"]').trigger('click')
    await flushPromises()

    expect(adminApi.disableUser).toHaveBeenCalledWith('user-1')
  })

  it('does not offer takeover actions for a super admin to an ordinary admin', async () => {
    vi.mocked(adminApi.listUsers).mockResolvedValue({
      items: [{
        id: 'root-1', username: 'root', email: 'root@example.edu', displayName: 'Root Administrator',
        status: 'ACTIVE', forcePasswordChange: false, tokenVersion: 0,
        lastLoginAt: null, createdAt: '2026-08-05T08:00:00Z', roles: ['SUPER_ADMIN'],
      }],
      page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    const wrapper = mountWithPermissions(
      UsersView,
      ['user:read', 'user:update', 'user:disable', 'user:create'],
    )
    await flushPromises()

    expect(wrapper.find('[data-testid="edit-user"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="reset-user-password"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="disable-user"]').exists()).toBe(false)
  })

  it('labels every role permission checkbox and reports a forbidden API state', async () => {
    vi.mocked(adminApi.listRoles).mockRejectedValue({ response: { status: 403 } })
    vi.mocked(adminApi.listPermissions).mockResolvedValue([
      { id: 'permission-1', code: 'user:read', description: 'Read users', createdAt: '2026-08-05T08:00:00Z' },
    ])
    const forbidden = mountWithPermissions(RolesView, ['role:read'])
    await flushPromises()
    expect(forbidden.text()).toContain('没有权限读取角色数据')

    vi.mocked(adminApi.listRoles).mockResolvedValue([])
    const editable = mountWithPermissions(RolesView, ['role:read', 'role:manage'])
    await flushPromises()
    await editable.get('[data-testid="create-role"]').trigger('click')

    expect(editable.get('label[for="permission-user-read"]').text()).toContain('user:read')
  })

  it('deletes only an unused custom role after confirmation', async () => {
    vi.mocked(adminApi.listRoles).mockResolvedValue([{
      id: 'custom-role', code: 'RESEARCHER', name: 'Researcher', description: 'Custom', systemRole: false,
      userCount: 0, permissions: ['paper:read'], createdAt: '2026-08-05T08:00:00Z',
    }])
    vi.mocked(adminApi.listPermissions).mockResolvedValue([
      { id: 'permission-1', code: 'paper:read', description: 'Read papers', createdAt: '2026-08-05T08:00:00Z' },
    ])
    vi.mocked(adminApi.deleteRole).mockResolvedValue(undefined)
    const wrapper = mountWithPermissions(RolesView, ['role:read', 'role:manage'])
    await flushPromises()

    await wrapper.get('[data-testid="delete-role"]').trigger('click')
    expect(adminApi.deleteRole).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="confirm-delete-role"]').trigger('click')
    await flushPromises()

    expect(adminApi.deleteRole).toHaveBeenCalledWith('custom-role')
  })


  it('sends the visible audit filters and renders only safe summaries', async () => {
    vi.mocked(adminApi.listAuditLogs).mockResolvedValue({
      items: [{
        id: 'audit-1', actorUserId: 'actor-1', actorUsername: 'admin', action: 'USER_DISABLED',
        resourceType: 'USER', resourceId: 'user-1', occurredAt: '2026-08-05T08:00:00Z',
        traceId: 'trace-audit-1', beforeSummary: { status: 'ACTIVE' },
        afterSummary: { status: 'DISABLED' }, result: 'SUCCESS', errorType: null,
      }],
      page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    const wrapper = mountWithPermissions(AuditLogsView, ['audit:read'])
    await flushPromises()
    await wrapper.get('#audit-action').setValue('USER_DISABLED')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(adminApi.listAuditLogs).toHaveBeenLastCalledWith(expect.objectContaining({ action: 'USER_DISABLED' }))
    expect(wrapper.text()).toContain('trace-audit-1')
    expect(wrapper.text()).not.toContain('passwordHash')
    expect(wrapper.text()).not.toContain('refreshToken')
  })
})

function mountWithPermissions(component: Component, permissions: Permission[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'memory-token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: {
      id: 'actor-1', username: 'admin', displayName: 'Administrator', roles: ['ADMIN'],
      permissions, mustChangePassword: false,
    },
  })
  return mount(component, {
    global: {
      plugins: [pinia],
      stubs: {
        teleport: true,
        transition: false,
        DsModal: {
          props: ['open', 'title'],
          template: '<div v-if="open">{{ title }}<slot /><slot name="actions" /></div>',
        },
      },
    },
  })
}
