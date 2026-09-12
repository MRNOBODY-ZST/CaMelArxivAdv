import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import { useAuthStore } from '@/modules/auth/auth.store'
import SmtpAccountsView from '@/modules/email/SmtpAccountsView.vue'
import type { SmtpAccountView } from '@/modules/email/email.types'

vi.mock('@/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}))

const account: SmtpAccountView = {
  id: 'smtp-1', name: 'Research SMTP', host: 'smtp.example.test', port: 465, tlsMode: 'TLS_IMPLICIT',
  username: 'smtp-user', passwordConfigured: true, fromEmail: 'research@example.test',
  defaultFromName: 'Research Team', replyTo: 'reply@example.test',
  perMinuteLimit: 10, perHourLimit: 100, perDayLimit: 400, perMonthLimit: 12_000,
  perDomainHourLimit: 50, enabled: true, lastTestedAt: null, lastTestStatus: null, lastTestError: null,
  lockVersion: 3, createdAt: '2026-09-13T00:00:00Z', updatedAt: '2026-09-13T00:00:00Z',
}

function mountAccounts(items: SmtpAccountView[] = [account]): VueWrapper {
  vi.mocked(apiClient.get).mockResolvedValue({
    data: { items, page: 1, pageSize: 100, total: items.length, totalPages: items.length ? 1 : 0 },
  })
  vi.mocked(apiClient.post).mockResolvedValue({ data: account })
  vi.mocked(apiClient.put).mockResolvedValue({ data: account })
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'test-token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: {
      id: 'admin-id', username: 'admin', displayName: 'Admin', roles: ['ADMIN'],
      permissions: ['smtp:read', 'smtp:manage'], mustChangePassword: false,
    },
  })
  return mount(SmtpAccountsView, {
    global: {
      plugins: [pinia],
      stubs: {
        DsModal: {
          props: ['open', 'title'],
          template: '<section v-if="open"><h2>{{ title }}</h2><slot /><slot name="actions" /></section>',
        },
      },
    },
  })
}

async function clickButton(wrapper: VueWrapper, label: string): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text() === label)
  expect(button).toBeDefined()
  await button!.trigger('click')
}

describe('SMTP account quotas', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows daily and monthly quotas and preserves the monthly value when another setting changes', async () => {
    const wrapper = mountAccounts()
    await flushPromises()
    expect(wrapper.get('[data-testid="smtp-daily-quota"]').text()).toContain('400')
    expect(wrapper.get('[data-testid="smtp-monthly-quota"]').text()).toContain('12,000')
    expect(wrapper.get('[data-testid="smtp-monthly-quota"]').text()).toContain('UTC 自然月')

    await clickButton(wrapper, '编辑')
    expect(wrapper.get<HTMLInputElement>('#limit-month').element.value).toBe('12000')
    await wrapper.get('#smtp-name').setValue('Renamed research account')
    await wrapper.get('#smtp-form').trigger('submit')
    await flushPromises()

    expect(apiClient.put).toHaveBeenCalledExactlyOnceWith('/smtp-accounts/smtp-1', {
      expectedLockVersion: 3,
      account: expect.objectContaining({
        name: 'Renamed research account', perDayLimit: 400, perMonthLimit: 12_000, password: null,
      }),
    })
    wrapper.unmount()
  })

  it('submits a configured monthly limit when creating an account', async () => {
    const wrapper = mountAccounts([])
    await flushPromises()
    await clickButton(wrapper, '新增账户')
    expect(wrapper.get<HTMLInputElement>('#limit-month').element.value).toBe('12000')
    await wrapper.get('#limit-day').setValue('400')
    await wrapper.get('#limit-month').setValue('12000')
    await wrapper.get('#smtp-form').trigger('submit')
    await flushPromises()

    expect(apiClient.post).toHaveBeenCalledExactlyOnceWith('/smtp-accounts', expect.objectContaining({
      perDayLimit: 400, perMonthLimit: 12_000,
    }))
    wrapper.unmount()
  })

  it('explains that leaving an existing monthly limit blank preserves it and refreshes the stored value', async () => {
    const wrapper = mountAccounts()
    await flushPromises()
    await clickButton(wrapper, '编辑')
    expect(wrapper.get('#limit-month-description').text()).toContain('留空保留现有限额')
    await wrapper.get('#limit-month').setValue('')
    await wrapper.get('#smtp-form').trigger('submit')
    await flushPromises()

    expect(apiClient.put).toHaveBeenCalledWith('/smtp-accounts/smtp-1', {
      expectedLockVersion: 3, account: expect.objectContaining({ perMonthLimit: null }),
    })
    expect(wrapper.get('[data-testid="smtp-monthly-quota"]').text()).toContain('12,000')
    await clickButton(wrapper, '编辑')
    expect(wrapper.get<HTMLInputElement>('#limit-month').element.value).toBe('12000')
    wrapper.unmount()
  })

  it('keeps an account without a monthly limit unchanged when editing', async () => {
    const wrapper = mountAccounts([{ ...account, perMonthLimit: null }])
    await flushPromises()
    expect(wrapper.get('[data-testid="smtp-monthly-quota"]').text()).toContain('未设置')
    await clickButton(wrapper, '编辑')
    expect(wrapper.get<HTMLInputElement>('#limit-month').element.value).toBe('')
    expect(wrapper.get('#limit-month-description').text()).toContain('留空则不设置月上限')
    await wrapper.get('#smtp-form').trigger('submit')
    await flushPromises()

    expect(apiClient.put).toHaveBeenCalledWith('/smtp-accounts/smtp-1', {
      expectedLockVersion: 3, account: expect.objectContaining({ perMonthLimit: null }),
    })
    wrapper.unmount()
  })
})
