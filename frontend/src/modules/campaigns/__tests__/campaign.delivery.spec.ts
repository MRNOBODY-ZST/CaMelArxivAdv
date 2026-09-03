import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'
import CampaignDetailView from '@/modules/campaigns/CampaignDetailView.vue'
import DeliveriesView from '@/modules/campaigns/DeliveriesView.vue'
import { campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { CampaignView, SafetyRunView } from '@/modules/campaigns/campaigns.types'

vi.mock('@/modules/campaigns/campaigns.api', () => ({
  campaignsApi: {
    getCampaign: vi.fn(), listRecipients: vi.fn(), runtimeStatus: vi.fn(), startPersonalization: vi.fn(),
    preflightCampaign: vi.fn(), submitCampaignForReview: vi.fn(), approveCampaign: vi.fn(),
    rejectCampaign: vi.fn(), scheduleCampaign: vi.fn(), startCampaign: vi.fn(), pauseCampaign: vi.fn(),
    resumeCampaign: vi.fn(), cancelCampaign: vi.fn(), startSafetyRun: vi.fn(), listSafetyRuns: vi.fn(),
    getSafetyRun: vi.fn(), cancelSafetyRun: vi.fn(), listDeliveries: vi.fn(), listCampaignAnalytics: vi.fn(),
    listLinkAnalytics: vi.fn(), listCampaigns: vi.fn(),
  },
  campaignErrorMessage: (_error: unknown, fallback: string) => fallback,
}))
vi.mock('@/modules/email/MailSendRecordsPanel.vue', () => ({
  default: { template: '<div>测试邮件数据</div>' },
}))

describe('campaign delivery operations', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(campaignsApi.getCampaign).mockResolvedValue(campaign())
    vi.mocked(campaignsApi.listRecipients).mockResolvedValue(page([]))
    vi.mocked(campaignsApi.runtimeStatus).mockResolvedValue({
      personalizationEnabled: true, provider: 'anthropic', model: 'claude-opus-4-6',
      rayConfigured: true, kafkaConfigured: true, liveSmtpAllowed: true,
      publicMailboxAllowed: true, generationReady: true,
    })
    vi.mocked(campaignsApi.preflightCampaign).mockResolvedValue({
      ready: true,
      checks: {
        CONTENT_READY: { passed: true, detail: '2 of 2 recipients have complete generated content' },
        SMTP_READY: { passed: true, detail: 'SMTP account is enabled and tested' },
      },
      counts: { TOTAL: 4, ELIGIBLE: 2, SUPPRESSED: 1, COOLDOWN_ACTIVE: 1 },
      estimatedMinutes: 1,
      digest: 'safe-digest',
    })
    vi.mocked(campaignsApi.listSafetyRuns).mockResolvedValue([safetyRun('RUNNING')])
    vi.mocked(campaignsApi.listDeliveries).mockResolvedValue(page([]))
    vi.mocked(campaignsApi.listCampaigns).mockResolvedValue(page([campaign()]))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows one permission-aware primary action and truthful preflight evidence', async () => {
    const viewer = await mountDetail(['campaign:read'])
    expect(viewer.text()).toContain('已批准')
    expect(viewer.text()).toContain('草稿不等于已发送')
    expect(viewer.text()).toContain('抑制名单')
    expect(viewer.text()).toContain('1')
    expect(viewer.find('[data-testid="primary-campaign-action"]').exists()).toBe(false)
    expect(viewer.find('[data-testid="start-safety-run"]').exists()).toBe(false)

    const sender = await mountDetail(['campaign:read', 'campaign:send'])
    expect(sender.get('[data-testid="primary-campaign-action"]').text()).toContain('开始正式发送')
    expect(sender.text()).toContain('q***@example.org')
    expect(sender.text()).not.toContain('qa@example.org')
    expect(sender.text()).toContain('SAFETY_REDIRECT')
  })

  it('requires the literal confirmation and a 1–20 safety limit', async () => {
    vi.mocked(campaignsApi.listSafetyRuns).mockResolvedValue([])
    vi.mocked(campaignsApi.startSafetyRun).mockResolvedValue(safetyRun('QUEUED'))
    const wrapper = await mountDetail(['campaign:read', 'campaign:send'])
    const button = wrapper.get('[data-testid="start-safety-run"]')
    expect(button.attributes()).toHaveProperty('disabled')

    await wrapper.get('#safety-recipient-limit').setValue('21')
    await wrapper.get('#safety-confirmation').setValue('SAFETY_REDIRECT')
    expect(wrapper.text()).toContain('1–20')
    expect(button.attributes()).toHaveProperty('disabled')

    await wrapper.get('#safety-recipient-limit').setValue('2')
    expect(button.attributes()).not.toHaveProperty('disabled')
    await button.trigger('click')
    await flushPromises()
    expect(campaignsApi.startSafetyRun).toHaveBeenCalledWith('campaign-1', {
      expectedLockVersion: 7, recipientLimit: 2, confirmation: 'SAFETY_REDIRECT',
    })
  })

  it('polls nonterminal work every three seconds and stops after terminal state', async () => {
    vi.mocked(campaignsApi.listSafetyRuns)
      .mockResolvedValueOnce([safetyRun('RUNNING')])
      .mockResolvedValueOnce([safetyRun('COMPLETED')])
    const wrapper = await mountDetail(['campaign:read'])
    expect(campaignsApi.listSafetyRuns).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(3_000)
    await flushPromises()
    expect(campaignsApi.listSafetyRuns).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(6_000)
    await flushPromises()
    expect(campaignsApi.listSafetyRuns).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('separates test, safety and production evidence with precise caveats', async () => {
    const wrapper = await mountDeliveries(['campaign:read', 'smtp:read'])
    expect(wrapper.text()).toContain('测试邮件')
    expect(wrapper.text()).toContain('安全实流')
    expect(wrapper.text()).toContain('正式活动')
    expect(wrapper.text()).toContain('SMTP 已接受不等于最终送达')
    expect(wrapper.text()).toContain('回传不等于确认人工阅读')
  })
})

async function mountDetail(permissions: Permission[]): Promise<VueWrapper> {
  return mountView(CampaignDetailView, '/email/campaigns/campaign-1', permissions)
}

async function mountDeliveries(permissions: Permission[]): Promise<VueWrapper> {
  return mountView(DeliveriesView, '/email/deliveries', permissions)
}

async function mountView(component: Parameters<typeof mount>[0], path: string, permissions: Permission[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: { id: 'operator', username: 'operator', displayName: 'Operator', roles: ['OPERATOR'], permissions, mustChangePassword: false },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/email/campaigns/:id', component: CampaignDetailView },
      { path: '/email/templates/:id', component: { template: '<div />' } },
      { path: '/email/deliveries', component: DeliveriesView },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

function campaign(): CampaignView {
  return {
    id: 'campaign-1', name: 'AI 作者邀约', purpose: '研究合作邀请', status: 'APPROVED',
    templateId: 'template-1', templateName: '论文邀约', templateVersion: 2,
    segmentId: 'segment-1', segmentName: '高可信作者', smtpAccountId: 'smtp-1', smtpName: 'Production SMTP',
    mailboxAccountId: 'mailbox-1', fromName: 'Research Team', fromEmail: 'sender@example.org', replyTo: 'reply@example.org',
    trackingOpensEnabled: true, trackingClicksEnabled: true,
    generationStatus: 'COMPLETED', generationProvider: 'anthropic', generationModel: 'claude-opus-4-6', generationJobId: 'job-1',
    recipientCounts: { queued: 0, running: 0, generated: 4, failed: 0, total: 4 },
    deliveryCounts: { queued: 4, connecting: 0, smtpAccepted: 0, temporaryFailure: 0, permanentFailure: 0, bounced: 0, suppressed: 0, unsubscribed: 0, canceled: 0, outcomeUnknown: 0, total: 4 },
    lockVersion: 7, submittedForReviewAt: '2026-09-04T00:00:00Z', approvedAt: '2026-09-04T00:10:00Z', approvedBy: 'admin-1',
    rejectedAt: null, rejectedBy: null, rejectionReason: null, scheduledAt: null, startedAt: null,
    completedAt: null, canceledAt: null, statusChangedAt: '2026-09-04T00:10:00Z', statusChangedBy: 'admin-1',
    createdAt: '2026-09-04T00:00:00Z', updatedAt: '2026-09-04T00:10:00Z',
  }
}

function safetyRun(status: SafetyRunView['status']): SafetyRunView {
  return {
    id: 'safety-1', campaignId: 'campaign-1', status, recipientLimit: 2, destinationMasked: 'q***@example.org',
    progress: { total: 2, queued: status === 'RUNNING' ? 1 : 0, connecting: 0, smtpAccepted: status === 'COMPLETED' ? 2 : 1, temporaryFailure: 0, permanentFailure: 0, canceled: 0, outcomeUnknown: 0 },
    events: { open: 1, click: 1, unsubscribe: 0, reply: 1, autoReply: 0, bounce: 0 },
    lockVersion: 1, startedAt: '2026-09-04T01:00:00Z', completedAt: status === 'COMPLETED' ? '2026-09-04T01:01:00Z' : null,
    createdAt: '2026-09-04T01:00:00Z', messages: [],
  }
}

function page<T>(items: T[]) {
  return { items, page: 1, pageSize: 20, total: items.length, totalPages: items.length ? 1 : 0 }
}
