import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import EmailTemplatesView from '@/modules/email/EmailTemplatesView.vue'
import SmtpAccountsView from '@/modules/email/SmtpAccountsView.vue'
import TemplateRichTextEditor from '@/modules/email/TemplateRichTextEditor.vue'
import { emailApi } from '@/modules/email/email.api'
import { mailTrackingApi } from '@/modules/email/mail-tracking.api'
import { useAuthStore } from '@/modules/auth/auth.store'

vi.mock('@/modules/email/email.api', () => ({
  emailApi: {
    listTemplates: vi.fn(),
    listSmtpAccounts: vi.fn(),
    sendSmtpDiagnostic: vi.fn(),
  },
  emailErrorMessage: (_error: unknown, fallback: string) => fallback,
}))
vi.mock('@/modules/email/mail-tracking.api', () => ({
  mailTrackingApi: { getStatus: vi.fn(), listSendRecords: vi.fn(), getSendRecord: vi.fn() },
  mailTrackingErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

describe('email administration views', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(mailTrackingApi.getStatus).mockResolvedValue({
      enabled: true, callbackBaseUrl: 'http://127.0.0.1:8080', callbackScope: 'LOCAL_ONLY', tokenTtlSeconds: 86_400,
    })
  })

  it('shows the template empty state after loading', async () => {
    vi.mocked(emailApi.listTemplates).mockResolvedValue({
      items: [], page: 1, pageSize: 20, total: 0, totalPages: 0,
    })
    const wrapper = mount(EmailTemplatesView, {
      global: { plugins: [createPinia()], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })

    await flushPromises()

    expect(wrapper.get('[data-testid="template-empty"]').text()).toContain('创建第一封邮件模板')
  })

  it('makes public SMTP TLS policy and password preservation explicit', async () => {
    vi.mocked(emailApi.listSmtpAccounts).mockResolvedValue({
      items: [{
        id: 'smtp-1', name: 'Mailpit', host: 'mailpit', port: 1025, tlsMode: 'PLAIN_LOCAL_ONLY',
        username: 'local', passwordConfigured: true, fromEmail: 'sender@example.org',
        defaultFromName: 'Research Team', replyTo: 'reply@example.org', perMinuteLimit: 10,
        perHourLimit: 100, perDayLimit: 1000, perDomainHourLimit: 50, enabled: true,
        lastTestedAt: null, lastTestStatus: null, lastTestError: null, lockVersion: 0,
        createdAt: '2026-08-07T00:00:00Z', updatedAt: '2026-08-07T00:00:00Z',
      }], page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    const wrapper = mount(SmtpAccountsView, { global: { plugins: [createPinia()] } })

    await flushPromises()

    expect(wrapper.get('[data-testid="public-smtp-banner"]').text()).toContain('公网主机必须使用 STARTTLS')
    expect(wrapper.get('[data-testid="password-sentinel"]').text()).toContain('已安全配置')
    expect(wrapper.text()).not.toContain('sender@example.org')
    expect(wrapper.text()).not.toContain('reply@example.org')
    expect(wrapper.text()).toContain('se***@example.org')
  })

  it('keeps a signed template image when inserting rich HTML', async () => {
    const wrapper = mount(TemplateRichTextEditor, { props: { modelValue: '<p>Body</p>' } })
    const signedUrl = '/api/v1/template-assets/5d3a9802-375f-42ee-9739-d419299bc4a8/5d3a9802-375f-42ee-9739-d419299bc4a9/content?signature=abcdefghijklmnopqrstuvwxyzABCDEFGH123456789'

    ;(wrapper.vm as unknown as { insertImage: (src: string, alt: string) => void })
      .insertImage(signedUrl, 'figure')
    await flushPromises()

    const updates = wrapper.emitted('update:modelValue') ?? []
    expect(updates.at(-1)?.[0]).toContain(`<img src="${signedUrl}"`)
  })

  it('requires a fresh recipient for each public SMTP diagnostic and reports acceptance only', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().acceptSession({
      accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
      user: {
        id: 'admin-id', username: 'admin', displayName: 'Admin', roles: ['ADMIN'],
        permissions: ['smtp:read', 'smtp:manage'], mustChangePassword: false,
      },
    })
    vi.mocked(emailApi.listSmtpAccounts).mockResolvedValue({
      items: [{
        id: 'smtp-public', name: 'Public test account', host: 'smtp.example.test', port: 465,
        tlsMode: 'TLS_IMPLICIT', username: 'sender', passwordConfigured: true,
        fromEmail: 'sender@example.test', defaultFromName: 'Research Team',
        replyTo: 'sender@example.test', perMinuteLimit: 1, perHourLimit: 2,
        perDayLimit: 10, perDomainHourLimit: 2, enabled: true, lastTestedAt: null,
        lastTestStatus: null, lastTestError: null, lockVersion: 0,
        createdAt: '2026-08-28T00:00:00Z', updatedAt: '2026-08-28T00:00:00Z',
      }], page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    vi.mocked(emailApi.sendSmtpDiagnostic).mockResolvedValue({
      status: 'SMTP_ACCEPTED', errorCategory: null, correlationId: 'test-correlation',
    })
    const wrapper = mount(SmtpAccountsView, {
      global: {
        plugins: [pinia],
        stubs: {
          DsModal: {
            props: ['open', 'title', 'description'],
            template: '<section v-if="open"><h2>{{ title }}</h2><p>{{ description }}</p><slot /><slot name="actions" /></section>',
          },
        },
      },
    })
    const button = (label: string) => wrapper.findAll('button').find((item) => item.text() === label)!
    await flushPromises()
    await button('测试邮件').trigger('click')
    expect(wrapper.get<HTMLInputElement>('#smtp-test-recipient').element.value).toBe('')
    expect(button('发送测试邮件').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('公网账户会真实发信')
    await wrapper.get('#smtp-test-recipient').setValue('old@example.test')
    await button('取消').trigger('click')
    await button('测试邮件').trigger('click')
    expect(wrapper.get<HTMLInputElement>('#smtp-test-recipient').element.value).toBe('')
    expect(button('发送测试邮件').attributes('disabled')).toBeDefined()
    await wrapper.get('#smtp-test-recipient').setValue('receiver@example.test')
    expect(button('发送测试邮件').attributes('disabled')).toBeUndefined()
    await button('发送测试邮件').trigger('click')
    await flushPromises()
    expect(emailApi.sendSmtpDiagnostic).toHaveBeenCalledExactlyOnceWith('smtp-public', 'receiver@example.test', false)
    expect(wrapper.text()).toContain('SMTP 已接受测试邮件')
    expect(wrapper.text()).toContain('不代表最终投递')
    wrapper.unmount()
  })
})
