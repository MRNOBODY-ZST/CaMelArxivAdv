import { flushPromises, mount } from '@vue/test-utils'
import type { Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import CampaignsView from '@/modules/campaigns/CampaignsView.vue'
import CampaignAnalyticsView from '@/modules/campaigns/CampaignAnalyticsView.vue'
import { campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { CampaignAnalyticsView as Metric, CampaignView } from '@/modules/campaigns/campaigns.types'

vi.mock('@/modules/campaigns/campaigns.api', () => ({
  campaignsApi: { listCampaigns: vi.fn(), listCampaignAnalytics: vi.fn() },
  campaignErrorMessage: (_: unknown, fallback: string) => fallback,
}))
vi.mock('@/modules/analytics/AnalyticsChart.vue', () => ({
  default: { template: '<section />' },
}))

const source = '00000000-0000-0000-0000-000000000001'
const target = '00000000-0000-0000-0000-000000000002'
const counts = { historicalSelected: 3, actionableQueued: 1, transferred: 2, migrationBlocked: 0 }
const references = [{ migrationId: '00000000-0000-0000-0000-000000000003', sourceCampaignId: source, targetCampaignId: target }]
const page = <T,>(items: T[]) => ({ items, page: 1, pageSize: 20, total: items.length, totalPages: 1 })

function campaign(extra = {}): CampaignView {
  return {
    id: source, name: 'Synthetic source', purpose: 'Inventory', status: 'PAUSED',
    templateId: 'template', templateName: 'Pinned', templateVersion: 1,
    segmentId: 'segment', segmentName: 'Frozen', smtpAccountId: 'smtp', smtpName: 'Synthetic',
    mailboxAccountId: null, fromName: 'Team', fromEmail: 'sender@example.test', replyTo: 'reply@example.test',
    trackingOpensEnabled: false, trackingClicksEnabled: false, generationStatus: 'COMPLETED',
    generationProvider: 'TEMPLATE', generationModel: 'exact-v1', generationJobId: null,
    recipientCounts: { queued: 0, running: 0, generated: 3, failed: 0, total: 3 },
    deliveryCounts: { queued: 3, connecting: 0, smtpAccepted: 0, temporaryFailure: 0, permanentFailure: 0, bounced: 0, suppressed: 0, unsubscribed: 0, canceled: 0, outcomeUnknown: 0, total: 3 },
    lockVersion: 0, submittedForReviewAt: null, approvedAt: null, approvedBy: null,
    rejectedAt: null, rejectedBy: null, rejectionReason: null, scheduledAt: null, startedAt: null,
    completedAt: null, canceledAt: null, statusChangedAt: null, statusChangedBy: null,
    createdAt: '2026-10-12T00:00:00Z', updatedAt: '2026-10-12T00:00:00Z', ...extra,
  }
}
function metric(extra = {}): Metric {
  return { id: source, name: 'Synthetic source', status: 'PAUSED', generationStatus: 'COMPLETED',
    recipients: 3, smtpAccepted: 1, permanentFailures: 0, outcomeUnknown: 1, bounced: 1,
    unsubscribed: 0, replied: 1, rawOpens: 2, humanOpens: 1, automatedOpens: 1,
    rawClicks: 0, humanClicks: 0, automatedClicks: 0,
    rates: { smtpAcceptance: 1 / 3, bounce: 1, unsubscribe: 0, reply: 1 },
    safety: { runs: 1, messages: 1, smtpAccepted: 1, outcomeUnknown: 0, replies: 0, bounces: 0 },
    createdAt: '2026-10-12T00:00:00Z', ...extra }
}
async function view(component: Component) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component }, { path: '/email/campaigns/:id', component: { template: '<div>Read-only destination</div>' } },
  ] })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(component, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('historical selections and unsent ownership inventory', () => {
  it('preserves source history and labels remaining ownership without send authority', async () => {
    vi.mocked(campaignsApi.listCampaigns).mockResolvedValue(page([campaign({ selectionCounts: counts, migrationReferences: references })]))
    const { wrapper, router } = await view(CampaignsView)
    expect(wrapper.text()).toContain('不代表审批、预检通过或发送许可')
    const inventory = wrapper.get('[data-testid="selection-inventory"]')
    expect(inventory.text()).toContain('历史入选3')
    expect(inventory.text()).toContain('剩余未发送1')
    expect(inventory.text()).toContain('已转出2')
    expect(inventory.text()).toContain('不在库存边界0')
    const link = inventory.get('a')
    expect(link.attributes('href')).toBe(`/email/campaigns/${target}`)
    await link.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(`/email/campaigns/${target}`)
  })

  it('missing object or individual field is unavailable while actual zero remains zero', async () => {
    vi.mocked(campaignsApi.listCampaigns).mockResolvedValue(page([
      campaign(), campaign({ id: target, selectionCounts: { historicalSelected: 0, actionableQueued: 0, transferred: 0 } }),
    ]))
    const { wrapper } = await view(CampaignsView)
    const inventory = wrapper.findAll('[data-testid="selection-inventory"]')
    expect(inventory[0]!.text()).toContain('剩余未发送不可用')
    expect(inventory[0]!.text()).toContain('迁移引用不可用')
    expect(inventory[1]!.text()).toContain('剩余未发送0')
    expect(inventory[1]!.text()).toContain('不在库存边界不可用')
  })

  it('analytics page-local sums distinguish history from source-plus-target actionable ownership', async () => {
    vi.mocked(campaignsApi.listCampaignAnalytics).mockResolvedValue(page([
      metric({ selectionCounts: counts, migrationReferences: references }),
      metric({ id: target, name: 'Synthetic target', selectionCounts: { historicalSelected: 2, actionableQueued: 2, transferred: 0, migrationBlocked: 0 }, migrationReferences: references }),
    ]))
    const { wrapper } = await view(CampaignAnalyticsView)
    expect(wrapper.text()).toContain('本页合计')
    expect(wrapper.get('[data-testid="page-selection-inventory"]').text()).toContain('剩余未发送3')
    expect(wrapper.get('[data-testid="page-selection-inventory"]').text()).toContain('历史入选5')
    expect(wrapper.text()).toContain('1/3 · 33.3%')
  })

  it('analytics partial legacy page never silently sums missing counts as zero', async () => {
    vi.mocked(campaignsApi.listCampaignAnalytics).mockResolvedValue(page([metric({ selectionCounts: counts }), metric({ id: target })]))
    const { wrapper } = await view(CampaignAnalyticsView)
    expect(wrapper.get('[data-testid="page-selection-inventory"]').text()).toContain('剩余未发送不可用')
  })
})
