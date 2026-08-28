import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'
import EmailTemplateEditorView from '@/modules/email/EmailTemplateEditorView.vue'
import SmtpAccountsView from '@/modules/email/SmtpAccountsView.vue'
import { mailTrackingApi } from '@/modules/email/mail-tracking.api'
import type { SmtpAccountView, TemplatePreview, TemplateView } from '@/modules/email/email.types'

vi.mock('@/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))
vi.mock('@/modules/email/mail-tracking.api', () => ({
  mailTrackingApi: { getStatus: vi.fn(), listSendRecords: vi.fn(), getSendRecord: vi.fn() },
  mailTrackingErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

const smtpAccount: SmtpAccountView = {
  id: 'smtp-1', name: 'Mailpit', host: 'mailpit', port: 1025, tlsMode: 'PLAIN_LOCAL_ONLY',
  username: 'local', passwordConfigured: true, fromEmail: 'sender@example.invalid', defaultFromName: 'Research Team',
  replyTo: 'reply@example.invalid', perMinuteLimit: 10, perHourLimit: 100, perDayLimit: 1_000,
  perDomainHourLimit: 50, enabled: true, lastTestedAt: null, lastTestStatus: null, lastTestError: null,
  lockVersion: 0, createdAt: '2026-08-28T10:00:00Z', updatedAt: '2026-08-28T10:00:00Z',
}

const template: TemplateView = {
  id: 'template-1', name: '测试模板', description: '仅用于测试', status: 'ACTIVE', currentVersion: 1, lockVersion: 1,
  subjectTemplate: '给 {{author_name}} 的测试', fromNameTemplate: '研究团队', replyTo: 'reply@example.invalid',
  htmlContent: '<p>你好 {{author_name}}</p>', textContent: '你好 {{author_name}}', autoGenerateText: false,
  contentSizeBytes: 32, validation: { valid: true, errors: [], warnings: [], variables: ['author_name'] },
  createdAt: '2026-08-28T10:00:00Z', updatedAt: '2026-08-28T10:00:00Z', versionCreatedAt: '2026-08-28T10:00:00Z',
}

const preview: TemplatePreview = {
  rendered: {
    subject: '给 Ada Lovelace 的测试', fromName: '研究团队', replyTo: 'reply@example.invalid',
    html: '<p>你好 Ada Lovelace</p>', text: '你好 Ada Lovelace',
  },
  validation: { valid: true, errors: [], warnings: [], variables: ['author_name'] },
  contentSizeBytes: 32,
}

const modalStub = {
  props: ['open', 'title', 'description'],
  template: '<section v-if="open"><h2>{{ title }}</h2><p>{{ description }}</p><slot /><slot name="actions" /></section>',
}

function mockTrackingEnabled(): void {
  vi.mocked(mailTrackingApi.getStatus).mockResolvedValue({
    enabled: true,
    callbackBaseUrl: 'http://127.0.0.1:8080',
    callbackScope: 'LOCAL_ONLY',
    tokenTtlSeconds: 86_400,
  })
}

function installSmtpReader(permissions: Permission[] = ['smtp:read', 'smtp:manage']): ReturnType<typeof createPinia> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: { id: 'admin-id', username: 'admin', displayName: 'Admin', roles: ['ADMIN'], permissions, mustChangePassword: false },
  })
  return pinia
}

function mockSmtpRequests(): void {
  vi.mocked(apiClient.get).mockImplementation((async (url: string) => {
    if (url === '/smtp-accounts') {
      return { data: { items: [smtpAccount], page: 1, pageSize: 100, total: 1, totalPages: 1 } }
    }
    throw new Error(`Unexpected GET ${url}`)
  }) as never)
  vi.mocked(apiClient.post).mockResolvedValue({
    data: { status: 'SMTP_ACCEPTED', errorCategory: null, correlationId: 'record-1' },
  } as never)
}

describe('SMTP diagnostic tracking dialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sends the selected tracking opt-in at the HTTP boundary and resets it for the next test', async () => {
    mockTrackingEnabled()
    mockSmtpRequests()
    const wrapper = mount(SmtpAccountsView, { global: { plugins: [installSmtpReader()], stubs: { DsModal: modalStub } } })
    await flushPromises()

    await button(wrapper, '测试邮件').trigger('click')
    await flushPromises()
    await wrapper.get('#smtp-test-recipient').setValue('q@example.invalid')
    await wrapper.get('#smtp-diagnostic-track-opens').setValue(true)
    await button(wrapper, '发送测试邮件').trigger('click')
    await flushPromises()

    expect(apiClient.post).toHaveBeenCalledWith('/smtp-accounts/smtp-1/test-email', {
      recipient: 'q@example.invalid',
      subject: 'CaMel arXiv SMTP 内部测试',
      body: '本消息只用于验证本机 SMTP 接收链路。',
      trackOpens: true,
    })

    await button(wrapper, '测试邮件').trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('#smtp-test-recipient').element.value).toBe('')
    expect(wrapper.get<HTMLInputElement>('#smtp-diagnostic-track-opens').element.checked).toBe(false)
  })

  it('keeps an untracked diagnostic send usable when tracking configuration fails', async () => {
    vi.mocked(mailTrackingApi.getStatus).mockRejectedValue(new Error('offline'))
    mockSmtpRequests()
    const wrapper = mount(SmtpAccountsView, { global: { plugins: [installSmtpReader()], stubs: { DsModal: modalStub } } })
    await flushPromises()

    await button(wrapper, '测试邮件').trigger('click')
    await flushPromises()
    await wrapper.get('#smtp-test-recipient').setValue('q@example.invalid')
    expect(wrapper.get('#smtp-diagnostic-track-opens').attributes('disabled')).toBeDefined()
    expect(button(wrapper, '发送测试邮件').attributes('disabled')).toBeUndefined()
    await button(wrapper, '发送测试邮件').trigger('click')
    await flushPromises()

    expect(apiClient.post).toHaveBeenCalledWith('/smtp-accounts/smtp-1/test-email', expect.objectContaining({ trackOpens: false }))
  })
})

describe('template test-send tracking dialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('preserves explicit SMTP selection and sends the template opt-in at the HTTP boundary', async () => {
    mockTrackingEnabled()
    vi.mocked(apiClient.get).mockImplementation((async (url: string) => {
      if (url === '/templates/template-1') return { data: template }
      if (url === '/templates/template-1/versions') return { data: [] }
      if (url === '/templates/template-1/assets') return { data: [] }
      if (url === '/smtp-accounts') return { data: { items: [smtpAccount], page: 1, pageSize: 100, total: 1, totalPages: 1 } }
      throw new Error(`Unexpected GET ${url}`)
    }) as never)
    vi.mocked(apiClient.post).mockImplementation((async (url: string) => {
      if (url === '/templates/preview') return { data: preview }
      if (url === '/templates/template-1/test-send') {
        return { data: { status: 'SMTP_ACCEPTED', errorCategory: null, correlationId: 'record-1' } }
      }
      throw new Error(`Unexpected POST ${url}`)
    }) as never)
    const pinia = installSmtpReader(['template:read', 'template:manage', 'smtp:read', 'smtp:manage'])
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/email/templates', component: { template: '<div />' } },
        { path: '/email/templates/:id', component: EmailTemplateEditorView },
      ],
    })
    await router.push('/email/templates/template-1')
    await router.isReady()
    const wrapper = mount(EmailTemplateEditorView, {
      global: {
        plugins: [pinia, router],
        stubs: { TemplateRichTextEditor: { template: '<div />' }, DsModal: modalStub },
      },
    })
    await flushPromises()

    await button(wrapper, '测试发送').trigger('click')
    await flushPromises()
    await wrapper.get('#test-smtp').setValue('smtp-1')
    await wrapper.get('#test-recipient').setValue('q@example.invalid')
    await wrapper.get('#template-test-track-opens').setValue(true)
    await button(wrapper, '发送测试').trigger('click')
    await flushPromises()

    expect(apiClient.post).toHaveBeenCalledWith('/templates/template-1/test-send', expect.objectContaining({
      smtpAccountId: 'smtp-1', recipient: 'q@example.invalid', trackOpens: true,
    }))
  })
})

function button(wrapper: VueWrapper, label: string): DOMWrapper<HTMLButtonElement> {
  const target = wrapper.findAll('button').find((item) => item.text() === label)
  if (!target) throw new Error(`Button ${label} was not found`)
  return target as DOMWrapper<HTMLButtonElement>
}
