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

function template(htmlContent: string): TemplateView {
  return {
    id: 'brand-template', name: 'Research invitation', description: '', status: 'DRAFT',
    currentVersion: 1, lockVersion: 4, subjectTemplate: 'Research collaboration',
    fromNameTemplate: 'Research Team', replyTo: 'reply@example.test', htmlContent,
    textContent: 'Research invitation', autoGenerateText: false, contentSizeBytes: htmlContent.length,
    validation: { valid: true, errors: [], warnings: [], variables: ['author_name', 'unsubscribe_url'] },
    createdAt: '2026-09-13T00:00:00Z', updatedAt: '2026-09-13T00:00:00Z', versionCreatedAt: '2026-09-13T00:00:00Z',
  }
}

async function mountEditor(html: string): Promise<VueWrapper> {
  const loaded = template(html)
  vi.mocked(apiClient.get).mockImplementation((async (url: string) => {
    if (url === '/templates/brand-template') return { data: loaded }
    if (url.endsWith('/versions') || url.endsWith('/assets')) return { data: [] }
    throw new Error(`Unexpected GET ${url}`)
  }) as never)
  vi.mocked(apiClient.post).mockResolvedValue({
    data: {
      rendered: { subject: loaded.subjectTemplate, fromName: loaded.fromNameTemplate, replyTo: loaded.replyTo, html, text: loaded.textContent },
      validation: loaded.validation, contentSizeBytes: html.length,
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
  await router.push('/email/templates/brand-template')
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
})
