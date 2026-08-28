import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import MailSendRecordsPanel from '@/modules/email/MailSendRecordsPanel.vue'
import MailTrackingOption from '@/modules/email/MailTrackingOption.vue'
import { mailTrackingApi } from '@/modules/email/mail-tracking.api'

vi.mock('@/modules/email/mail-tracking.api', () => ({
  mailTrackingApi: {
    getStatus: vi.fn(),
    listSendRecords: vi.fn(),
    getSendRecord: vi.fn(),
  },
  mailTrackingErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

interface RecordFixture {
  id: string
  source: 'SMTP_DIAGNOSTIC' | 'TEMPLATE_TEST'
  recipientMasked: string
  subject: string
  smtpAccountName: string | null
  status: 'SENDING' | 'SMTP_ACCEPTED' | 'FAILED' | 'UNKNOWN'
  failureCategory: string | null
  trackingEnabled: boolean
  createdAt: string
  completedAt: string | null
  trackingExpiresAt: string | null
  rawOpenCount: number
  automatedOpenCount: number
  firstOpenAt: string | null
  lastOpenAt: string | null
}

const modalStub = {
  props: ['open', 'title', 'description'],
  emits: ['close'],
  template: '<section v-if="open"><button aria-label="关闭" @click="$emit(\'close\')">关闭</button><h2>{{ title }}</h2><p>{{ description }}</p><slot /><slot name="actions" /></section>',
}

function trackingStatus(overrides: Partial<{
  enabled: boolean
  callbackBaseUrl: string
  callbackScope: 'LOCAL_ONLY' | 'PUBLIC_HTTPS_UNVERIFIED'
  tokenTtlSeconds: number
}> = {}) {
  return {
    enabled: true,
    callbackBaseUrl: 'http://127.0.0.1:8080',
    callbackScope: 'LOCAL_ONLY' as const,
    tokenTtlSeconds: 86_400,
    ...overrides,
  }
}

function record(overrides: Partial<RecordFixture> = {}): RecordFixture {
  return {
    id: 'record-1',
    source: 'SMTP_DIAGNOSTIC',
    recipientMasked: 'q***@example.invalid',
    subject: 'CaMel arXiv SMTP 内部测试',
    smtpAccountName: 'Mailpit',
    status: 'SMTP_ACCEPTED',
    failureCategory: null,
    trackingEnabled: true,
    createdAt: '2026-08-28T10:00:00Z',
    completedAt: '2026-08-28T10:00:01Z',
    trackingExpiresAt: '2026-08-29T10:00:00Z',
    rawOpenCount: 0,
    automatedOpenCount: 0,
    firstOpenAt: null,
    lastOpenAt: null,
    ...overrides,
  }
}

function page(items: RecordFixture[], pageNumber = 1, totalPages = 1) {
  return { items, page: pageNumber, pageSize: 20, total: totalPages > 1 ? 21 : items.length, totalPages }
}

describe('mail tracking option', () => {
  beforeEach(() => vi.clearAllMocks())

  it('starts unchecked and explains a local-only callback without claiming public reachability', async () => {
    vi.mocked(mailTrackingApi.getStatus).mockResolvedValue(trackingStatus())

    const wrapper = mount(MailTrackingOption, { props: { id: 'track-opens', modelValue: false } })
    await flushPromises()

    expect(wrapper.get<HTMLInputElement>('#track-opens').element.checked).toBe(false)
    expect(wrapper.text()).toContain('http://127.0.0.1:8080')
    expect(wrapper.text()).toContain('仅限本机')
    expect(wrapper.text()).toContain('不能证明人工阅读')
  })

  it('blocks a requested opt-in when tracking is disabled', async () => {
    vi.mocked(mailTrackingApi.getStatus).mockResolvedValue(trackingStatus({ enabled: false }))

    const wrapper = mount(MailTrackingOption, { props: { id: 'disabled-track-opens', modelValue: true } })
    await flushPromises()

    expect(wrapper.get<HTMLInputElement>('#disabled-track-opens').attributes('disabled')).toBeDefined()
    expect(wrapper.get<HTMLInputElement>('#disabled-track-opens').element.checked).toBe(false)
    expect(wrapper.text()).toContain('当前配置未启用')
    expect(wrapper.emitted('update:modelValue')?.flat()).toContain(false)
  })

  it('blocks only tracking when its configuration cannot be loaded', async () => {
    vi.mocked(mailTrackingApi.getStatus).mockRejectedValue(new Error('offline'))

    const wrapper = mount(MailTrackingOption, { props: { id: 'errored-track-opens', modelValue: false } })
    await flushPromises()

    expect(wrapper.get<HTMLInputElement>('#errored-track-opens').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('配置暂时无法加载')
  })
})

describe('mail send records', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(mailTrackingApi.getStatus).mockResolvedValue(trackingStatus())
  })

  it('renders a masked recipient and changes only to a truthful image-load state after manual refresh', async () => {
    vi.mocked(mailTrackingApi.listSendRecords)
      .mockResolvedValueOnce(page([record()]))
      .mockResolvedValueOnce(page([record({ rawOpenCount: 1, firstOpenAt: '2026-08-28T10:05:00Z', lastOpenAt: '2026-08-28T10:05:00Z' })]))

    const { wrapper } = await mountPanel('/email/deliveries')

    expect(wrapper.text()).toContain('q***@example.invalid')
    expect(wrapper.text()).toContain('尚无回传')
    await wrapper.get('button[aria-label="刷新测试邮件记录"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('检测到图片加载')
  })

  it('keeps untracked, expired, failed, and unknown records distinct from a read claim', async () => {
    vi.mocked(mailTrackingApi.listSendRecords).mockResolvedValue(page([
      record({ id: 'untracked', recipientMasked: 'u***@example.invalid', trackingEnabled: false }),
      record({ id: 'expired', recipientMasked: 'e***@example.invalid', trackingExpiresAt: '2026-08-01T00:00:00Z' }),
      record({ id: 'failed', recipientMasked: 'f***@example.invalid', status: 'FAILED', failureCategory: 'SMTP_REJECTED' }),
      record({ id: 'unknown', recipientMasked: 'n***@example.invalid', status: 'UNKNOWN' }),
    ]))

    const { wrapper } = await mountPanel('/email/deliveries')

    expect(wrapper.text()).toContain('未启用检测')
    expect(wrapper.text()).toContain('检测期已过期')
    expect(wrapper.text()).toContain('发送失败，未确认检测')
    expect(wrapper.text()).toContain('发送状态未知')
    expect(wrapper.text()).not.toContain('已阅读')
  })

  it('opens a deep-linked detail, labels classified callbacks, and clears the query on close', async () => {
    const detail = record({ rawOpenCount: 4, automatedOpenCount: 3, firstOpenAt: '2026-08-28T10:05:00Z', lastOpenAt: '2026-08-28T10:08:00Z' })
    vi.mocked(mailTrackingApi.listSendRecords).mockResolvedValue(page([detail]))
    vi.mocked(mailTrackingApi.getSendRecord).mockResolvedValue({
      record: detail,
      events: [
        { id: 4, occurredAt: '2026-08-28T10:08:00Z', classification: 'BOT', reason: 'scanner' },
        { id: 3, occurredAt: '2026-08-28T10:07:00Z', classification: 'IMAGE_PROXY', reason: 'image proxy' },
        { id: 2, occurredAt: '2026-08-28T10:06:00Z', classification: 'PREFETCH', reason: 'prefetch' },
        { id: 1, occurredAt: '2026-08-28T10:05:00Z', classification: 'UNCLASSIFIED', reason: 'pixel returned' },
      ],
    })

    const { router, wrapper } = await mountPanel('/email/deliveries?record=record-1')

    expect(wrapper.text()).toContain('record-1')
    expect(wrapper.text()).toContain('图片代理')
    expect(wrapper.text()).toContain('预取')
    expect(wrapper.text()).toContain('自动化')
    expect(wrapper.text()).toContain('未分类回传')
    await wrapper.get('button[aria-label="关闭"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.record).toBeUndefined()
  })

  it('shows a detail error without turning a missing callback into a read claim', async () => {
    vi.mocked(mailTrackingApi.listSendRecords).mockResolvedValue(page([record()]))
    vi.mocked(mailTrackingApi.getSendRecord).mockRejectedValue(new Error('not found'))

    const { wrapper } = await mountPanel('/email/deliveries?record=record-1')

    expect(wrapper.text()).toContain('测试邮件记录详情加载失败。')
    expect(wrapper.text()).not.toContain('已阅读')
  })

  it('refreshes an open detail with newly received image callbacks', async () => {
    const initial = record()
    const refreshed = record({ rawOpenCount: 1, firstOpenAt: '2026-08-28T10:05:00Z', lastOpenAt: '2026-08-28T10:05:00Z' })
    vi.mocked(mailTrackingApi.listSendRecords).mockResolvedValue(page([initial]))
    vi.mocked(mailTrackingApi.getSendRecord)
      .mockResolvedValueOnce({ record: initial, events: [] })
      .mockResolvedValueOnce({
        record: refreshed,
        events: [{ id: 1, occurredAt: '2026-08-28T10:05:00Z', classification: 'UNCLASSIFIED', reason: 'pixel returned' }],
      })

    const { wrapper } = await mountPanel('/email/deliveries?record=record-1')
    expect(wrapper.text()).toContain('尚无回传')
    const refresh = wrapper.findAll('button').find((button) => button.text() === '刷新回传')
    if (!refresh) throw new Error('刷新回传 button was not rendered')
    await refresh.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('检测到图片加载（1）')
  })

  it('paginates test records without mixing them into campaign deliveries', async () => {
    vi.mocked(mailTrackingApi.listSendRecords)
      .mockResolvedValueOnce(page([record()], 1, 2))
      .mockResolvedValueOnce(page([record({ id: 'record-2', recipientMasked: 'z***@example.invalid' })], 2, 2))

    const { wrapper } = await mountPanel('/email/deliveries')
    const next = wrapper.findAll('button').find((button) => button.text().includes('下一页'))
    if (!next) throw new Error('下一页 button was not rendered')
    await next.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('z***@example.invalid')
    expect(wrapper.text()).not.toContain('q***@example.invalid')
  })
})

async function mountPanel(path: string): Promise<{ router: ReturnType<typeof createRouter>; wrapper: VueWrapper }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/email/deliveries', component: MailSendRecordsPanel }],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(MailSendRecordsPanel, {
    global: { plugins: [router, createPinia()], stubs: { DsModal: modalStub } },
  })
  await flushPromises()
  return { router, wrapper }
}
