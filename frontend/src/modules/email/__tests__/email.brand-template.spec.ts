import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import { useAuthStore } from '@/modules/auth/auth.store'
import EmailTemplateEditorView from '@/modules/email/EmailTemplateEditorView.vue'
import TemplateRichTextEditor from '@/modules/email/TemplateRichTextEditor.vue'
import type { TemplateUpsertRequest, TemplateView } from '@/modules/email/email.types'

vi.mock('@/api/client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}))

const tableHtml = '<table role="presentation" cellpadding="0" cellspacing="0">\n<tbody><tr><td>Research invitation for {{author_name}}</td></tr></tbody>\n</table><p><a href="{{unsubscribe_url}}">Unsubscribe</a></p>'
const styledHtml = '<div style="background-color:#f7f4ef;padding:32px">\n<p style="font-family:Arial;color:#102b35">Research invitation for {{author_name}}</p>\n<a href="{{unsubscribe_url}}">Unsubscribe</a></div>'
const simpleHtml = '<p>Hello {{author_name}}</p><p><a href="{{unsubscribe_url}}">Unsubscribe</a></p>'

function template(htmlContent: string, autoGenerateText = false): TemplateView {
  return {
    id: 'brand-template', name: 'Research invitation', description: '', status: 'DRAFT',
    currentVersion: 1, lockVersion: 4, subjectTemplate: 'Research collaboration',
    fromNameTemplate: 'Research Team', replyTo: 'reply@example.test', htmlContent,
    textContent: 'Research invitation', autoGenerateText, contentSizeBytes: htmlContent.length,
    validation: { valid: true, errors: [], warnings: [], variables: ['author_name', 'unsubscribe_url'] },
    createdAt: '2026-09-13T00:00:00Z', updatedAt: '2026-09-13T00:00:00Z', versionCreatedAt: '2026-09-13T00:00:00Z',
  }
}

async function mountEditor(html: string | null, autoGenerateText = false): Promise<VueWrapper> {
  const loaded = template(html ?? simpleHtml, autoGenerateText)
  vi.mocked(apiClient.get).mockImplementation((async (url: string) => {
    if (url === '/templates/brand-template') return { data: loaded }
    if (url.endsWith('/versions') || url.endsWith('/assets')) return { data: [] }
    throw new Error(`Unexpected GET ${url}`)
  }) as never)
  vi.mocked(apiClient.post).mockResolvedValue({
    data: {
      rendered: { subject: loaded.subjectTemplate, fromName: loaded.fromNameTemplate, replyTo: loaded.replyTo, html: html ?? '', text: loaded.textContent },
      validation: loaded.validation, contentSizeBytes: html?.length ?? 0,
    },
  })
  vi.mocked(apiClient.put).mockImplementation((async (_url: string, body: { template: TemplateUpsertRequest }) => ({
    data: { ...loaded, name: body.template.name, ...body.template.content, currentVersion: 2, lockVersion: 5 },
  })) as never)
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'test-token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: {
      id: 'admin', username: 'admin', displayName: 'Admin', roles: ['ADMIN'],
      permissions: ['template:read', 'template:manage'], mustChangePassword: false,
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/email/templates', component: { template: '<div />' } },
      { path: '/email/templates/:id', component: EmailTemplateEditorView },
    ],
  })
  await router.push(html === null ? '/email/templates/new' : '/email/templates/brand-template')
  await router.isReady()
  const wrapper = mount(EmailTemplateEditorView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find((item) => item.text() === label)
  expect(found).toBeDefined()
  return found!
}

describe('brand template HTML preservation', () => {
  beforeEach(() => vi.clearAllMocks())

  it.each([
    ['table layout', tableHtml],
    ['inline styles', styledHtml],
  ])('preserves %s exactly when only the template title is changed and saved', async (_name, html) => {
    const wrapper = await mountEditor(html)
    expect(wrapper.findComponent(TemplateRichTextEditor).exists()).toBe(false)
    expect(button(wrapper, '富文本').attributes('disabled')).toBeDefined()
    expect(wrapper.get<HTMLTextAreaElement>('[aria-label="HTML 源码"]').element.value).toBe(html)
    expect(wrapper.get('#brand-template-html-note').text()).toContain('请使用 HTML 模式编辑')

    await wrapper.get('#template-name').setValue('Updated research invitation')
    await button(wrapper, '立即保存').trigger('click')
    await flushPromises()

    expect(apiClient.put).toHaveBeenCalledExactlyOnceWith('/templates/brand-template', {
      expectedLockVersion: 4,
      template: expect.objectContaining({
        name: 'Updated research invitation', content: expect.objectContaining({ htmlContent: html }),
      }),
    })
    expect(wrapper.get<HTMLTextAreaElement>('[aria-label="HTML 源码"]').element.value).toBe(html)
    expect(wrapper.findComponent(TemplateRichTextEditor).exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps a new template auto-generated text and unsubscribe body intact when activation is clicked in text mode', async () => {
    const wrapper = await mountEditor(null)
    await button(wrapper, '纯文本').trigger('click')
    const textBody = wrapper.get<HTMLTextAreaElement>('textarea[rows="16"]')
    expect(textBody.attributes('disabled')).toBeDefined()
    expect(textBody.element.value).toBe('')
    expect(wrapper.get('[role="switch"]').attributes('aria-checked')).toBe('true')

    await button(wrapper, '开通 CaMel 账户').trigger('click')

    expect(textBody.attributes('disabled')).toBeDefined()
    expect(textBody.element.value).toBe('')
    expect(wrapper.get('[role="switch"]').attributes('aria-checked')).toBe('true')
    expect(wrapper.text()).toContain('请先在 HTML 正文中加入开通链接')
    await button(wrapper, 'HTML').trigger('click')
    const htmlBody = wrapper.get<HTMLTextAreaElement>('[aria-label="HTML 源码"]').element.value
    expect(htmlBody).toContain('我们关注到您的论文')
    expect(htmlBody).toContain('{{unsubscribe_url}}')
    expect(htmlBody).not.toContain('{{partner_claim_url}}')
    wrapper.unmount()
  })

  it('keeps rich editing available for simple templates and stops using it when styled HTML is entered', async () => {
    const wrapper = await mountEditor(simpleHtml)
    expect(wrapper.findComponent(TemplateRichTextEditor).exists()).toBe(true)
    expect(button(wrapper, '富文本').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[aria-label="HTML 源码"]').exists()).toBe(false)

    await button(wrapper, 'HTML').trigger('click')
    await wrapper.get('[aria-label="HTML 源码"]').setValue(styledHtml)
    await flushPromises()

    expect(wrapper.findComponent(TemplateRichTextEditor).exists()).toBe(false)
    expect(button(wrapper, '富文本').attributes('disabled')).toBeDefined()
    expect(wrapper.get<HTMLTextAreaElement>('[aria-label="HTML 源码"]').element.value).toBe(styledHtml)
    wrapper.unmount()
  })

  it('appends uploaded image markup safely without changing the existing brand layout', async () => {
    const wrapper = await mountEditor(styledHtml)
    const objectUrl = '/api/v1/template-assets/template/asset/content?signature=signed&version=1'
    const originalFilename = '<research "cover" & draft>.png'
    vi.mocked(apiClient.post).mockResolvedValue({
      data: {
        id: 'asset-1', templateId: 'brand-template', objectUrl, originalFilename,
        contentType: 'image/png', sizeBytes: 1, createdAt: '2026-09-13T00:00:00Z',
      },
    })
    const input = wrapper.get<HTMLInputElement>('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['x'], originalFilename, { type: 'image/png' })], configurable: true,
    })
    await input.trigger('change')
    await flushPromises()

    expect(apiClient.post).toHaveBeenLastCalledWith('/templates/brand-template/assets', expect.any(FormData))
    expect(wrapper.get<HTMLTextAreaElement>('[aria-label="HTML 源码"]').element.value).toBe(
      `${styledHtml}<p><img src="/api/v1/template-assets/template/asset/content?signature=signed&amp;version=1" alt="&lt;research &quot;cover&quot; &amp; draft&gt;.png"></p>`,
    )
    expect(wrapper.findComponent(TemplateRichTextEditor).exists()).toBe(false)
    wrapper.unmount()
  })

  it('inserts partner activation in HTML and explains send-time binding', async () => {
    const wrapper = await mountEditor(tableHtml)
    expect(wrapper.text()).toContain('正式发送时')
    await button(wrapper, '开通 CaMel 账户').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('[aria-label="HTML 源码"]').element.value)
      .toContain('{{partner_claim_url}}')
    expect(wrapper.find('#sample-partner_claim_url').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each([
    ['subject', '#template-subject', 'Research collaboration{{author_name}}', 'Research Team'],
    ['sender name', '#template-from-name', 'Research collaboration', 'Research Team{{author_name}}'],
  ])('keeps partner activation out of the %s when text mode is active', async (_name, focusSelector, subject, fromName) => {
    const wrapper = await mountEditor(simpleHtml)
    await button(wrapper, '纯文本').trigger('click')
    await wrapper.get(focusSelector).trigger('focusin')
    await button(wrapper, '作者全名').trigger('click')
    await button(wrapper, '开通 CaMel 账户').trigger('click')

    expect(wrapper.get<HTMLInputElement>('#template-subject').element.value).toBe(subject)
    expect(wrapper.get<HTMLInputElement>('#template-from-name').element.value).toBe(fromName)
    expect(wrapper.get<HTMLTextAreaElement>('textarea[rows="16"]').element.value)
      .toBe('Research invitation {{partner_claim_url}}')
    expect(wrapper.get('textarea[rows="16"]').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it.each([
    ['Research invitation', 'Research invitation {{partner_claim_url}}'],
    ['Research invitation ', 'Research invitation {{partner_claim_url}}'],
    ['Research invitation\n', 'Research invitation\n{{partner_claim_url}}'],
    ['', '{{partner_claim_url}}'],
    ['Hello\nUnsubscribe: {{unsubscribe_url}}', 'Hello\nUnsubscribe: {{unsubscribe_url}} {{partner_claim_url}}'],
  ])('keeps a standalone activation URL boundary after manual text %j', async (text, expected) => {
    const wrapper = await mountEditor(tableHtml)
    await button(wrapper, '纯文本').trigger('click')
    await wrapper.get('textarea[rows="16"]').setValue(text)
    await button(wrapper, '开通 CaMel 账户').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('textarea[rows="16"]').element.value).toBe(expected)
    expect(wrapper.get('[role="switch"]').attributes('aria-checked')).toBe('false')
    await button(wrapper, 'HTML').trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('[aria-label="HTML 源码"]').element.value).toBe(tableHtml)
    wrapper.unmount()
  })

  it('renders preview links inert even if a claim URL is returned unexpectedly', async () => {
    const wrapper = await mountEditor(tableHtml)
    vi.mocked(apiClient.post).mockResolvedValue({
      data: {
        rendered: {
          subject: 'Research collaboration', fromName: 'Research Team', replyTo: 'reply@example.test',
          html: '<p>Activate</p><a href="https://api.camel-hub.cn/partner/claim?token=private-token">Claim</a>',
          text: 'Activate',
        },
        validation: { valid: true, errors: [], warnings: [], variables: ['partner_claim_url'] },
        contentSizeBytes: 100,
      },
    })
    await button(wrapper, '刷新预览').trigger('click')
    await flushPromises()
    const frame = wrapper.get('iframe[title="邮件正文预览"]')
    expect(frame.attributes('sandbox')).toBe('')
    expect(frame.attributes('srcdoc')).not.toContain('private-token')
    expect(new DOMParser().parseFromString(frame.attributes('srcdoc') ?? '', 'text/html')
      .querySelector('a')?.getAttribute('href')).toBeNull()
    expect(apiClient.post).toHaveBeenLastCalledWith('/templates/preview', expect.objectContaining({
      variables: expect.objectContaining({ partner_claim_url: 'https://example.invalid/partner-activation-preview' }),
    }))
    wrapper.unmount()
  })
})
