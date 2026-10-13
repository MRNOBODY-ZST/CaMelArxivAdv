import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import CampaignRecipientEditor from '@/modules/campaigns/CampaignRecipientEditor.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'
import { campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { CampaignRecipient, CampaignView } from '@/modules/campaigns/campaigns.types'

vi.mock('@/modules/campaigns/campaigns.api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/modules/campaigns/campaigns.api')>()
  return { ...actual, campaignsApi: { ...actual.campaignsApi, updateRecipientContent: vi.fn() } }
})

const wrappers: VueWrapper[] = []
const editedHtml = '<table style="width:100%"><tr><td>CTBench feedback</td></tr></table>\n<a href="{{unsubscribe_url}}">Unsubscribe</a>'
const editedText = 'CTBench feedback\n\nUnsubscribe: {{unsubscribe_url}}'
const result = {
  campaignId: 'campaign-1', recipientId: 'recipient-1', lockVersion: 8,
  subject: 'CTBench research feedback', html: editedHtml, text: editedText,
  rationale: 'Explain relevance to the CTBench paper.', updatedAt: '2026-09-21T01:00:00Z',
}

describe('recipient draft content editor', () => {
  beforeEach(() => {
    vi.mocked(campaignsApi.updateRecipientContent).mockResolvedValue(result)
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('opens a generated draft with all existing fields and reports its editing state', async () => {
    const wrapper = mountEditor()
    await open(wrapper)

    expect(value(wrapper, 'subject')).toBe('Original subject')
    expect(value(wrapper, 'html')).toBe('<p>Original body</p><a href="{{unsubscribe_url}}">Unsubscribe</a>')
    expect(value(wrapper, 'text')).toBe('Original body\n{{unsubscribe_url}}')
    expect(value(wrapper, 'rationale')).toBe('Original rationale')
    expect(value(wrapper, 'reason')).toBe('')
    expect(wrapper.emitted('editing')).toEqual([[true]])
  })

  it('does not offer editing without campaign:create permission', () => {
    const wrapper = mountEditor({}, ['campaign:read'])
    expect(wrapper.find('[data-testid="edit-recipient-content"]').exists()).toBe(false)
  })

  it.each<CampaignView['generationStatus']>(['COMPLETED', 'PARTIALLY_FAILED', 'FAILED'])(
    'allows a generated recipient when generation has terminated as %s', (generationStatus) => {
      const wrapper = mountEditor({ campaign: campaign({ generationStatus }) })
      expect(wrapper.get('[data-testid="edit-recipient-content"]').attributes('disabled')).toBeUndefined()
    },
  )

  it('allows editing generated draft content when all eight delivery recipients are only queued', async () => {
    const wrapper = mountEditor({
      campaign: campaign({ deliveryCounts: { ...campaign().deliveryCounts, queued: 8, total: 8 } }),
    })

    expect(wrapper.get('[data-testid="edit-recipient-content"]').attributes('disabled')).toBeUndefined()
    await open(wrapper)
    await fillDraft(wrapper)
    await submit(wrapper)

    expect(wrapper.emitted('saved')).toEqual([[result]])
  })

  it.each([
    { name: 'approved campaign', campaign: campaign({ status: 'APPROVED' }) },
    { name: 'pending review', campaign: campaign({ status: 'READY_FOR_REVIEW' }) },
    { name: 'unrequested generation', campaign: campaign({ generationStatus: 'NOT_REQUESTED' }) },
    { name: 'queued generation', campaign: campaign({ generationStatus: 'QUEUED' }) },
    { name: 'running generation', campaign: campaign({ generationStatus: 'RUNNING' }) },
    { name: 'failed recipient', recipient: recipient({ personalizationStatus: 'FAILED' }) },
    { name: 'redacted recipient', recipient: recipient({ trackingArtifactsRedacted: true }) },
    { name: 'parent mutation', disabled: true },
  ])('disables editing for $name', async ({ campaign, recipient, disabled }) => {
    const wrapper = mountEditor({ ...(campaign ? { campaign } : {}), ...(recipient ? { recipient } : {}), ...(disabled ? { disabled } : {}) })
    expect(wrapper.get('[data-testid="edit-recipient-content"]').attributes()).toHaveProperty('disabled')
    await wrapper.get('[data-testid="edit-recipient-content"]').trigger('click')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('saves the exact content with the version captured at open and emits the saved result', async () => {
    const wrapper = mountEditor()
    await open(wrapper)
    await fillDraft(wrapper)
    await wrapper.setProps({ campaign: campaign({ lockVersion: 19 }), recipient: recipient({ subject: 'Server refresh' }) })
    await submit(wrapper)

    expect(campaignsApi.updateRecipientContent).toHaveBeenCalledExactlyOnceWith('campaign-1', 'recipient-1', {
      expectedLockVersion: 7, subject: 'CTBench research feedback', html: editedHtml, text: editedText,
      rationale: 'Explain relevance to the CTBench paper.', reason: 'Correct the research framing after manual review.',
    })
    expect(wrapper.emitted('saved')).toEqual([[result]])
    expect(wrapper.emitted('editing')).toEqual([[true], [false]])
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it.each([
    { field: 'html', invalid: '<p>No unsubscribe link</p>', label: 'HTML 正文' },
    { field: 'text', invalid: 'Unsubscribe: {{ unsubscribe_url }}', label: '纯文本正文' },
    { field: 'subject', invalid: 'Read {{unsubscribe_url}}', label: '邮件主题' },
    { field: 'subject', invalid: 'Read {{ unsubscribe_url }}', label: '邮件主题' },
    { field: 'subject', invalid: 'Read\u007f now', label: '邮件主题' },
    { field: 'subject', invalid: 'x'.repeat(999), label: '邮件主题' },
    { field: 'html', invalid: 'x'.repeat(200_001) + '{{unsubscribe_url}}', label: 'HTML 正文' },
    { field: 'text', invalid: 'x'.repeat(100_001) + '{{unsubscribe_url}}', label: '纯文本正文' },
    { field: 'rationale', invalid: ' ', label: '个性化说明' },
    { field: 'rationale', invalid: 'x'.repeat(2001), label: '个性化说明' },
    { field: 'reason', invalid: ' ', label: '修改原因' },
    { field: 'reason', invalid: 'x'.repeat(1001), label: '修改原因' },
  ])('blocks invalid $field ($label) before requesting a save', async ({ field, invalid, label }) => {
    const wrapper = mountEditor()
    await open(wrapper)
    await fillDraft(wrapper)
    await wrapper.get(`[id$="-${field}"]`).setValue(invalid)
    await submit(wrapper)

    expect(campaignsApi.updateRecipientContent).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain(label)
    expect(wrapper.find('form').exists()).toBe(true)
  })

  it('keeps unsaved content on a version conflict and never retries automatically', async () => {
    vi.mocked(campaignsApi.updateRecipientContent).mockRejectedValue({
      isAxiosError: true, response: { status: 409, data: { detail: 'Version changed' } },
    })
    const wrapper = mountEditor()
    await open(wrapper)
    await fillDraft(wrapper)
    await submit(wrapper)

    expect(value(wrapper, 'html')).toBe(editedHtml)
    expect(value(wrapper, 'reason')).toBe('Correct the research framing after manual review.')
    expect(wrapper.get('[role="alert"]').text()).toContain('刷新')
    expect(wrapper.get('[role="alert"]').text()).toContain('保留')
    expect(campaignsApi.updateRecipientContent).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.get('[data-testid="save-recipient-content"]').attributes()).toHaveProperty('disabled')
  })

  it('keeps the draft and enables manual retry after an ordinary save error', async () => {
    vi.mocked(campaignsApi.updateRecipientContent).mockRejectedValueOnce(new Error('network unavailable'))
    const wrapper = mountEditor()
    await open(wrapper)
    await fillDraft(wrapper)
    await submit(wrapper)

    expect(wrapper.get('[role="alert"]').text()).toContain('保存失败')
    expect(value(wrapper, 'text')).toBe(editedText)
    expect(wrapper.get('[data-testid="save-recipient-content"]').attributes('disabled')).toBeUndefined()
    await submit(wrapper)
    expect(wrapper.emitted('saved')).toEqual([[result]])
  })

  it('locks fields and closing while saving and sends only one request for duplicate submits', async () => {
    let resolveSave!: (value: typeof result) => void
    vi.mocked(campaignsApi.updateRecipientContent).mockReturnValue(new Promise((resolve) => { resolveSave = resolve }))
    const wrapper = mountEditor()
    await open(wrapper)
    await fillDraft(wrapper)
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')

    expect(campaignsApi.updateRecipientContent).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="save-recipient-content"]').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('[id$="-html"]').attributes()).toHaveProperty('disabled')
    wrapper.getComponent(DsModal).vm.$emit('close')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
    resolveSave(result)
    await flushPromises()
    expect(wrapper.emitted('saved')).toEqual([[result]])
  })

  it('rechecks lifecycle state at save without discarding the open draft', async () => {
    const wrapper = mountEditor()
    await open(wrapper)
    await fillDraft(wrapper)
    await wrapper.setProps({ campaign: campaign({ status: 'READY_FOR_REVIEW' }) })
    await submit(wrapper)

    expect(campaignsApi.updateRecipientContent).not.toHaveBeenCalled()
    expect(value(wrapper, 'text')).toBe(editedText)
    expect(wrapper.get('[data-testid="save-recipient-content"]').attributes()).toHaveProperty('disabled')
  })

  it('closes without saving and starts a fresh snapshot next time', async () => {
    const wrapper = mountEditor()
    await open(wrapper)
    await fillDraft(wrapper)
    wrapper.getComponent(DsModal).vm.$emit('close')
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.emitted('editing')).toEqual([[true], [false]])
    await wrapper.setProps({ campaign: campaign({ lockVersion: 8 }), recipient: recipient({ subject: 'Fresh subject' }) })
    await open(wrapper)
    expect(value(wrapper, 'subject')).toBe('Fresh subject')
    expect(value(wrapper, 'reason')).toBe('')
    expect(campaignsApi.updateRecipientContent).not.toHaveBeenCalled()
  })

  it('previews layout in a sandbox with network content and navigation removed without changing the draft', async () => {
    const source = '<table style="width:100%"><tr><td>Feedback</td></tr></table><img src="https://tracker.example/pixel"><script>alert(1)</script><meta http-equiv="refresh" content="0;url=https://example.org"><a href="{{unsubscribe_url}}">Unsubscribe</a>'
    const wrapper = mountEditor({ recipient: recipient({ html: source }) })
    await open(wrapper)

    const frame = wrapper.get('iframe')
    expect(frame.attributes('sandbox')).toBe('')
    const preview = new DOMParser().parseFromString(frame.attributes('srcdoc') ?? '', 'text/html')
    expect(preview.querySelector('table')?.getAttribute('style')).toBe('width:100%')
    expect(preview.querySelector('script')).toBeNull()
    expect(preview.querySelector('meta[http-equiv="refresh"]')).toBeNull()
    expect(preview.querySelector('a')?.getAttribute('href')).toBeNull()
    expect(preview.querySelector('meta[http-equiv="Content-Security-Policy"]')?.getAttribute('content')).toContain("default-src 'none'")
    expect(value(wrapper, 'html')).toBe(source)
  })

  it('removes deferred and animated structures that can restore navigation in the preview', async () => {
    const source = '<p>Feedback</p><div><template shadowrootmode="open"><a href="https://example.org/collect">Hidden link</a></template></div><svg><a><animate attributeName="href" values="https://example.org/collect" /><text>Animated link</text></a></svg><math><mtext>Unsupported content</mtext></math><a href="{{unsubscribe_url}}">Unsubscribe</a>'
    const wrapper = mountEditor({ recipient: recipient({ html: source }) })
    await open(wrapper)

    const preview = new DOMParser().parseFromString(wrapper.get('iframe').attributes('srcdoc') ?? '', 'text/html')
    expect(preview.querySelector('template, svg, math')).toBeNull()
    expect(preview.querySelector('p')?.textContent).toBe('Feedback')
    expect(value(wrapper, 'html')).toBe(source)
  })

  it('redacts an unexpected claim token from recipient preview without altering the draft', async () => {
    const source = '<p>https://api.camel-hub.cn/partner/claim?token=private-token</p><a href="{{unsubscribe_url}}">Unsubscribe</a>'
    const wrapper = mountEditor({ recipient: recipient({ html: source }) })
    await open(wrapper)

    expect(wrapper.get('iframe').attributes('srcdoc')).not.toContain('private-token')
    expect(value(wrapper, 'html')).toBe(source)
  })
})

function mountEditor(props: Partial<{ campaign: CampaignView; recipient: CampaignRecipient; disabled: boolean }> = {}, permissions: Permission[] = ['campaign:read', 'campaign:create']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'test-token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: { id: 'editor-1', username: 'editor', displayName: 'Editor', roles: [], permissions, mustChangePassword: false },
  })
  const wrapper = mount(CampaignRecipientEditor, {
    props: { campaign: campaign(), recipient: recipient(), ...props },
    global: { plugins: [pinia], stubs: { DsModal: { props: ['open', 'title'], template: '<section v-if="open"><h2>{{ title }}</h2><slot /><slot name="actions" /></section>' } } },
  })
  wrappers.push(wrapper)
  return wrapper
}

async function open(wrapper: VueWrapper) {
  await wrapper.get('[data-testid="edit-recipient-content"]').trigger('click')
}

async function submit(wrapper: VueWrapper) {
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

function value(wrapper: VueWrapper, field: string) {
  return wrapper.get<HTMLInputElement | HTMLTextAreaElement>(`[id$="-${field}"]`).element.value
}

async function fillDraft(wrapper: VueWrapper) {
  await wrapper.get('[id$="-subject"]').setValue('CTBench research feedback')
  await wrapper.get('[id$="-html"]').setValue(editedHtml)
  await wrapper.get('[id$="-text"]').setValue(editedText)
  await wrapper.get('[id$="-rationale"]').setValue('Explain relevance to the CTBench paper.')
  await wrapper.get('[id$="-reason"]').setValue('Correct the research framing after manual review.')
}

function campaign(overrides: Partial<CampaignView> = {}): CampaignView {
  return {
    id: 'campaign-1', name: 'CTBench research outreach', purpose: 'Research feedback', status: 'DRAFT',
    templateId: 'template-1', templateName: 'Research', templateVersion: 2,
    segmentId: 'segment-1', segmentName: 'CTBench authors', smtpAccountId: 'smtp-1', smtpName: 'Research mail',
    mailboxAccountId: null, fromName: 'Research Team', fromEmail: 'research@example.org', replyTo: 'research@example.org',
    trackingOpensEnabled: false, trackingClicksEnabled: false,
    generationStatus: 'COMPLETED', generationProvider: 'openai', generationModel: 'model', generationJobId: 'job-1',
    recipientCounts: { queued: 0, running: 0, generated: 1, failed: 0, total: 1 },
    deliveryCounts: { queued: 0, connecting: 0, smtpAccepted: 0, temporaryFailure: 0, permanentFailure: 0, bounced: 0, suppressed: 0, unsubscribed: 0, canceled: 0, outcomeUnknown: 0, total: 0 },
    lockVersion: 7, submittedForReviewAt: null, approvedAt: null, approvedBy: null,
    rejectedAt: null, rejectedBy: null, rejectionReason: null, scheduledAt: null, startedAt: null,
    completedAt: null, canceledAt: null, statusChangedAt: null, statusChangedBy: null,
    createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z', ...overrides,
  }
}

function recipient(overrides: Partial<CampaignRecipient> = {}): CampaignRecipient {
  return {
    id: 'recipient-1', authorName: 'Research Author', paperTitle: 'CTBench', category: 'cs.AI',
    organization: 'Research Lab', personalizationStatus: 'GENERATED', subject: 'Original subject',
    html: '<p>Original body</p><a href="{{unsubscribe_url}}">Unsubscribe</a>', text: 'Original body\n{{unsubscribe_url}}',
    rationale: 'Original rationale', errorCode: null, errorMessage: null,
    personalizedAt: '2026-09-21T00:00:00Z', createdAt: '2026-09-21T00:00:00Z', trackingArtifactsRedacted: false,
    ...overrides,
  }
}
