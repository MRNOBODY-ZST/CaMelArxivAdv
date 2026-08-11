import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import CampaignAnalyticsView from '@/modules/campaigns/CampaignAnalyticsView.vue'
import CampaignDetailView from '@/modules/campaigns/CampaignDetailView.vue'
import CampaignsView from '@/modules/campaigns/CampaignsView.vue'
import DeliveriesView from '@/modules/campaigns/DeliveriesView.vue'
import LinkAnalyticsView from '@/modules/campaigns/LinkAnalyticsView.vue'
import SegmentsView from '@/modules/campaigns/SegmentsView.vue'
import { campaignsApi } from '@/modules/campaigns/campaigns.api'
import type {
  CampaignRecipient,
  CampaignView,
  RuntimeStatus,
  SegmentView,
} from '@/modules/campaigns/campaigns.types'
import SystemSettingsView from '@/modules/admin/SystemSettingsView.vue'
import { emailApi } from '@/modules/email/email.api'

vi.mock('@/modules/campaigns/campaigns.api', () => ({
  campaignsApi: {
    listSegments: vi.fn(), previewSegment: vi.fn(), createSegment: vi.fn(),
    listCampaigns: vi.fn(), createCampaign: vi.fn(), getCampaign: vi.fn(),
    listRecipients: vi.fn(), startPersonalization: vi.fn(),
    listDeliveries: vi.fn(), listCampaignAnalytics: vi.fn(), listLinkAnalytics: vi.fn(),
    runtimeStatus: vi.fn(),
  },
  campaignErrorMessage: (_error: unknown, fallback: string) => fallback,
}))
vi.mock('@/modules/email/email.api', () => ({
  emailApi: { listTemplates: vi.fn(), listSmtpAccounts: vi.fn() },
  emailErrorMessage: (_error: unknown, fallback: string) => fallback,
}))
vi.mock('@/modules/analytics/AnalyticsChart.vue', () => ({
  default: { props: ['title'], template: '<section data-testid="analytics-chart">{{ title }}</section>' },
}))

describe('campaign workspace views', () => {
  beforeEach(() => {
    vi.mocked(campaignsApi.listSegments).mockResolvedValue(page([segment()]))
    vi.mocked(campaignsApi.listCampaigns).mockResolvedValue(page([campaign()]))
    vi.mocked(campaignsApi.getCampaign).mockResolvedValue(campaign())
    vi.mocked(campaignsApi.listRecipients).mockResolvedValue(page([recipient()]))
    vi.mocked(campaignsApi.runtimeStatus).mockResolvedValue(runtime(false))
    vi.mocked(campaignsApi.listDeliveries).mockResolvedValue(page([]))
    vi.mocked(campaignsApi.listCampaignAnalytics).mockResolvedValue(page([]))
    vi.mocked(campaignsApi.listLinkAnalytics).mockResolvedValue(page([]))
    vi.mocked(emailApi.listTemplates).mockResolvedValue(page([]))
    vi.mocked(emailApi.listSmtpAccounts).mockResolvedValue(page([]))
  })

  it('loads real segments and opens the controlled rule form', async () => {
    const wrapper = await mountView(SegmentsView, '/email/segments')
    expect(wrapper.text()).toContain('AI 已验证作者')
    expect(wrapper.text()).toContain('12 位可用联系人')

    await click(wrapper, '新建分组')
    expect(wrapper.text()).toContain('arXiv 主分类')
    expect(wrapper.text()).toContain('邮箱置信度')
    expect(wrapper.text()).toContain('联系人验证状态')
  })

  it('opens a real campaign from the list and explains why generation is unavailable', async () => {
    const list = await mountView(CampaignsView, '/email/campaigns')
    expect(list.text()).toContain('AI 作者邀约')
    expect(list.get('[data-testid="campaign-detail-link"]').attributes('href')).toBe('/email/campaigns/campaign-1')

    const detail = await mountView(CampaignDetailView, '/email/campaigns/campaign-1')
    expect(detail.text()).toContain('个性化生成当前未启用')
    expect(detail.get('[data-testid="template-editor-link"]').attributes('href')).toBe('/email/templates/template-1')
    expect(detail.get('[data-testid="start-personalization"]').attributes()).toHaveProperty('disabled')
    expect(detail.text()).toContain('A Reliable AI Paper')
  })

  it('renders truthful reporting empty states and non-secret runtime status', async () => {
    const deliveries = await mountView(DeliveriesView, '/email/deliveries')
    expect(deliveries.text()).toContain('暂无发送记录')
    const campaigns = await mountView(CampaignAnalyticsView, '/analytics/campaigns')
    expect(campaigns.text()).toContain('暂无活动指标')
    const links = await mountView(LinkAnalyticsView, '/analytics/links')
    expect(links.text()).toContain('暂无链接互动')
    const settings = await mountView(SystemSettingsView, '/admin/settings')
    expect(settings.text()).toContain('OpenAI')
    expect(settings.text()).toContain('gpt-5.6-luna')
    expect(settings.text()).toContain('未启用')
    expect(settings.text()).not.toContain('API Key')
  })
})

async function mountView(component: Parameters<typeof mount>[0], path: string): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/email/segments', component: SegmentsView },
      { path: '/email/campaigns', component: CampaignsView },
      { path: '/email/campaigns/:id', component: CampaignDetailView },
      { path: '/email/templates/:id', component: { template: '<div />' } },
      { path: '/email/deliveries', component: DeliveriesView },
      { path: '/analytics/campaigns', component: CampaignAnalyticsView },
      { path: '/analytics/links', component: LinkAnalyticsView },
      { path: '/admin/settings', component: SystemSettingsView },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component, {
    global: {
      plugins: [router],
      stubs: {
        DsModal: { props: ['open'], template: '<section v-if="open"><slot /><slot name="actions" /></section>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

async function click(wrapper: VueWrapper, label: string): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text().includes(label))
  if (!button) throw new Error(`Button ${label} was not found`)
  await button.trigger('click')
}

function page<T>(items: T[]) {
  return { items, page: 1, pageSize: 20, total: items.length, totalPages: items.length ? 1 : 0 }
}

function segment(): SegmentView {
  return {
    id: 'segment-1', name: 'AI 已验证作者', description: '高可信联系人',
    rules: [{ field: 'primaryCategory', operator: 'equals', value: 'cs.AI' }],
    eligibleCount: 12, createdAt: '2026-08-10T00:00:00Z', updatedAt: '2026-08-10T00:00:00Z',
  }
}

function campaign(): CampaignView {
  return {
    id: 'campaign-1', name: 'AI 作者邀约', purpose: '邀请作者了解相关研究', status: 'DRAFT',
    templateId: 'template-1', templateName: '论文邀约', templateVersion: 2,
    segmentId: 'segment-1', segmentName: 'AI 已验证作者', smtpAccountId: 'smtp-1', smtpName: 'Mailpit',
    fromName: 'Research Team', fromEmail: 'sender@example.org', replyTo: 'reply@example.org',
    generationStatus: 'NOT_REQUESTED', generationProvider: null, generationModel: null, generationJobId: null,
    recipientCounts: { queued: 0, running: 0, generated: 0, failed: 0, total: 0 },
    createdAt: '2026-08-10T00:00:00Z', updatedAt: '2026-08-10T00:00:00Z',
  }
}

function recipient(): CampaignRecipient {
  return {
    id: 'recipient-1', authorName: 'Alice', paperTitle: 'A Reliable AI Paper', category: 'cs.AI',
    organization: 'Example Lab', personalizationStatus: 'PENDING', subject: null, html: null, text: null,
    rationale: null, errorCode: null, errorMessage: null, personalizedAt: null, createdAt: '2026-08-10T00:00:00Z',
  }
}

function runtime(enabled: boolean): RuntimeStatus {
  return {
    personalizationEnabled: enabled, provider: 'openai', model: 'gpt-5.6-luna',
    rayConfigured: true, kafkaConfigured: true, liveSmtpAllowed: true,
    publicMailboxAllowed: true, generationReady: enabled,
  }
}
