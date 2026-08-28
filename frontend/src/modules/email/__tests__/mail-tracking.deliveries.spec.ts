import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import DeliveriesView from '@/modules/campaigns/DeliveriesView.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'
import { campaignsApi } from '@/modules/campaigns/campaigns.api'
import MailSendRecordDialog from '@/modules/email/MailSendRecordDialog.vue'
import { mailTrackingApi } from '@/modules/email/mail-tracking.api'

vi.mock('@/modules/campaigns/campaigns.api', () => ({
  campaignsApi: { listDeliveries: vi.fn() },
  campaignErrorMessage: (_error: unknown, fallback: string) => fallback,
}))
vi.mock('@/modules/email/mail-tracking.api', () => ({
  mailTrackingApi: { getStatus: vi.fn(), listSendRecords: vi.fn(), getSendRecord: vi.fn() },
  mailTrackingErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

function installPermissions(permissions: Permission[]): ReturnType<typeof createPinia> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: { id: 'operator', username: 'operator', displayName: 'Operator', roles: ['OPERATOR'], permissions, mustChangePassword: false },
  })
  return pinia
}

function mailRecord() {
  return {
    id: 'record-1', source: 'SMTP_DIAGNOSTIC' as const, recipientMasked: 'q***@example.invalid',
    subject: '测试记录', smtpAccountName: 'Mailpit', status: 'SMTP_ACCEPTED' as const,
    failureCategory: null, trackingEnabled: true, createdAt: '2026-08-28T10:00:00Z', completedAt: null,
    trackingExpiresAt: '2026-08-29T10:00:00Z', rawOpenCount: 0, automatedOpenCount: 0, firstOpenAt: null, lastOpenAt: null,
  }
}

function delivery(overrides: Partial<{ id: string, campaignName: string }> = {}) {
  return {
    id: 'delivery-1', campaignId: 'campaign-1', campaignName: 'AI 邀约', recipientId: 'recipient-1',
    authorName: 'Ada', paperTitle: 'A test paper', attemptNumber: 1, status: 'SMTP_ACCEPTED',
    smtpResponseCode: 250, smtpResponseSummary: 'accepted', failureCategory: null, retryable: false,
    startedAt: '2026-08-28T10:00:00Z', completedAt: '2026-08-28T10:00:01Z',
    ...overrides,
  }
}

describe('deliveries permission-gated tabs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(campaignsApi.listDeliveries).mockResolvedValue({ items: [delivery()], page: 1, pageSize: 20, total: 1, totalPages: 1 })
    vi.mocked(mailTrackingApi.getStatus).mockResolvedValue({
      enabled: true, callbackBaseUrl: 'http://127.0.0.1:8080', callbackScope: 'LOCAL_ONLY', tokenTtlSeconds: 86_400,
    })
    vi.mocked(mailTrackingApi.listSendRecords).mockResolvedValue({ items: [mailRecord()], page: 1, pageSize: 20, total: 1, totalPages: 1 })
  })

  it('defaults SMTP readers to records and preserves the campaign view in its own tab', async () => {
    const { wrapper } = await mountDeliveries(['smtp:read', 'campaign:read'])

    expect(wrapper.text()).toContain('测试邮件记录')
    expect(wrapper.text()).toContain('q***@example.invalid')
    await button(wrapper, '活动发送记录').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('AI 邀约')
  })

  it('switches back to records when a same-route record query arrives after selecting campaigns', async () => {
    vi.mocked(mailTrackingApi.getSendRecord).mockResolvedValue({ record: mailRecord(), events: [] })
    const { router, wrapper } = await mountDeliveries(['smtp:read', 'campaign:read'])

    await button(wrapper, '活动发送记录').trigger('click')
    await flushPromises()
    expect(button(wrapper, '活动发送记录').attributes('aria-selected')).toBe('true')

    await router.push('/email/deliveries?record=record-1')
    await flushPromises()

    expect(button(wrapper, '测试邮件记录').attributes('aria-selected')).toBe('true')
    expect(wrapper.findComponent(MailSendRecordDialog).props('recordId')).toBe('record-1')
    expect(mailTrackingApi.getSendRecord).toHaveBeenCalledWith('record-1')
  })

  it('does not fetch SMTP-protected data when a record query arrives for a campaign-only reader', async () => {
    const { router, wrapper } = await mountDeliveries(['campaign:read'])

    await router.push('/email/deliveries?record=record-1')
    await flushPromises()

    expect(button(wrapper, '活动发送记录').attributes('aria-selected')).toBe('true')
    expect(mailTrackingApi.getStatus).not.toHaveBeenCalled()
    expect(mailTrackingApi.listSendRecords).not.toHaveBeenCalled()
    expect(mailTrackingApi.getSendRecord).not.toHaveBeenCalled()
  })

  it('does not fetch SMTP-protected records for campaign-only readers', async () => {
    const { wrapper } = await mountDeliveries(['campaign:read'])

    expect(wrapper.text()).toContain('活动发送记录')
    expect(wrapper.text()).toContain('AI 邀约')
    expect(mailTrackingApi.listSendRecords).not.toHaveBeenCalled()
    expect(mailTrackingApi.getStatus).not.toHaveBeenCalled()
  })

  it('does not load campaign deliveries for SMTP-only readers', async () => {
    const { wrapper } = await mountDeliveries(['smtp:read'])

    expect(wrapper.text()).toContain('q***@example.invalid')
    expect(wrapper.text()).not.toContain('活动发送记录')
    expect(campaignsApi.listDeliveries).not.toHaveBeenCalled()
  })

  it('retains campaign pagination in the separate campaign tab', async () => {
    vi.mocked(campaignsApi.listDeliveries)
      .mockResolvedValueOnce({ items: [delivery()], page: 1, pageSize: 20, total: 21, totalPages: 2 })
      .mockResolvedValueOnce({ items: [delivery({ id: 'delivery-2', campaignName: '第二个活动' })], page: 2, pageSize: 20, total: 21, totalPages: 2 })
    const { wrapper } = await mountDeliveries(['smtp:read', 'campaign:read'])

    await button(wrapper, '活动发送记录').trigger('click')
    await flushPromises()
    const next = wrapper.findAll('button').find((item) => item.text().includes('下一页'))
    if (!next) throw new Error('Next campaign page button was not found')
    await next.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('第二个活动')
    expect(wrapper.text()).not.toContain('AI 邀约')
  })

  it('retains campaign load errors in the separate campaign tab', async () => {
    vi.mocked(campaignsApi.listDeliveries).mockRejectedValue(new Error('offline'))
    const { wrapper } = await mountDeliveries(['campaign:read'])

    expect(wrapper.text()).toContain('发送记录加载失败。')
  })
})

async function mountDeliveries(permissions: Permission[]): Promise<{ router: ReturnType<typeof createRouter>; wrapper: VueWrapper }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/email/deliveries', component: DeliveriesView }],
  })
  await router.push('/email/deliveries')
  await router.isReady()
  const wrapper = mount(DeliveriesView, { global: { plugins: [installPermissions(permissions), router] } })
  await flushPromises()
  return { router, wrapper }
}

function button(wrapper: VueWrapper, label: string): DOMWrapper<HTMLButtonElement> {
  const target = wrapper.findAll('button').find((item) => item.text() === label)
  if (!target) throw new Error(`Button ${label} was not found`)
  return target as DOMWrapper<HTMLButtonElement>
}
