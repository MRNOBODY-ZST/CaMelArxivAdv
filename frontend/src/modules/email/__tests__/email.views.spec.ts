import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import EmailTemplatesView from '@/modules/email/EmailTemplatesView.vue'
import SmtpAccountsView from '@/modules/email/SmtpAccountsView.vue'
import TemplateRichTextEditor from '@/modules/email/TemplateRichTextEditor.vue'
import { emailApi } from '@/modules/email/email.api'

vi.mock('@/modules/email/email.api', () => ({
  emailApi: {
    listTemplates: vi.fn(),
    listSmtpAccounts: vi.fn(),
  },
  emailErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

describe('email administration views', () => {
  beforeEach(() => vi.clearAllMocks())

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

  it('makes local-only SMTP state and password preservation explicit', async () => {
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

    expect(wrapper.get('[data-testid="local-only-banner"]').text()).toContain('仅允许本机 Mailpit')
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
})
