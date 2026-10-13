import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'
import CampaignSettingsPanel from '@/modules/campaigns/CampaignSettingsPanel.vue'
import type { CampaignStatus, CampaignView } from '@/modules/campaigns/campaigns.types'
import type { MailboxAccountView } from '@/modules/email/email.types'

describe('campaign settings', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: mailboxPage([
      mailbox(), mailbox({ id: 'disabled', name: 'Disabled inbox', enabled: false }),
      mailbox({ id: 'untested', name: 'Untested inbox', lastTestStatus: null }),
      mailbox({ id: 'failed', name: 'Failed inbox', lastTestStatus: 'FAILED' }),
    ]) })
    vi.spyOn(apiClient, 'put').mockResolvedValue({ data: campaign({ lockVersion: 8 }) })
  })

  afterEach(() => wrapper?.unmount())

  it('shows the current sender and missing binding without loading mailbox accounts', () => {
    wrapper = mountPanel()
    expect(wrapper.text()).toContain('sender@example.org')
    expect(wrapper.text()).toContain('Research SMTP')
    expect(wrapper.text()).toContain('reply@example.org')
    expect(wrapper.text()).toContain('未绑定')
    expect(apiClient.get).not.toHaveBeenCalled()
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
  })

  it('loads mailboxes only on open and never chooses an account for an unbound campaign', async () => {
    wrapper = mountPanel()
    await openSettings(wrapper)
    expect(apiClient.get).toHaveBeenCalledWith('/mailbox-accounts', { params: { page: 1, pageSize: 100 } })
    expect(wrapper.get<HTMLSelectElement>('#campaign-settings-mailbox').element.value).toBe('')
    expect(wrapper.get('[data-testid="save-campaign-settings"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.emitted('editing')).toEqual([[true]])
    await wrapper.get('[data-testid="cancel-campaign-settings"]').trigger('click')
    expect(wrapper.emitted('editing')).toEqual([[true], [false]])
  })

  it('submits edited fields with the original open-time lock even when the parent refreshes', async () => {
    wrapper = mountPanel(campaign({ mailboxAccountId: 'mailbox-1' }))
    await openSettings(wrapper)
    await wrapper.get('#campaign-settings-name').setValue('  CTBench outreach  ')
    await wrapper.get('#campaign-settings-purpose').setValue('  Invite the authors\nwith context.  ')
    await wrapper.get('#campaign-settings-from-name').setValue('  CTBench Team  ')
    await wrapper.get('#campaign-settings-reply-to').setValue('  authors@example.org  ')
    await wrapper.get('#campaign-settings-track-opens').setValue(false)
    await wrapper.get('#campaign-settings-track-clicks').setValue(true)
    await wrapper.setProps({ campaign: campaign({ lockVersion: 12, mailboxAccountId: 'mailbox-1' }) })
    await wrapper.get('#campaign-settings-form').trigger('submit')
    await flushPromises()

    expect(apiClient.put).toHaveBeenCalledExactlyOnceWith('/campaigns/campaign-1', {
      expectedLockVersion: 7,
      name: 'CTBench outreach', purpose: 'Invite the authors\nwith context.', mailboxAccountId: 'mailbox-1',
      fromName: 'CTBench Team', replyTo: 'authors@example.org',
      trackingOpensEnabled: false, trackingClicksEnabled: true,
    })
    expect(wrapper.emitted('saved')).toEqual([[]])
    expect(wrapper.emitted('editing')).toEqual([[true], [false]])
    expect(wrapper.find('#campaign-settings-form').exists()).toBe(false)
  })

  it.each<CampaignStatus>(['READY_FOR_REVIEW', 'APPROVED', 'SCHEDULED', 'RUNNING', 'PAUSED', 'COMPLETED', 'CANCELED'])(
    'does not offer settings mutations for %s campaigns', (status) => {
      wrapper = mountPanel(campaign({ status }))
      expect(wrapper.find('[data-testid="edit-campaign-settings"]').exists()).toBe(false)
      expect(apiClient.get).not.toHaveBeenCalled()
    },
  )

  it('allows an authorized rejected campaign to be edited', async () => {
    wrapper = mountPanel(campaign({ status: 'REJECTED' }))
    await openSettings(wrapper)
    expect(wrapper.find('#campaign-settings-form').exists()).toBe(true)
  })

  it('does not expose editing without campaign:create permission', () => {
    wrapper = mountPanel(campaign(), ['campaign:read'])
    expect(wrapper.find('[data-testid="edit-campaign-settings"]').exists()).toBe(false)
    expect(apiClient.get).not.toHaveBeenCalled()
  })

  it('blocks saves when lifecycle or parent busy state changes while the form is open', async () => {
    wrapper = mountPanel(campaign({ mailboxAccountId: 'mailbox-1' }))
    await wrapper.setProps({ disabled: true })
    expect(wrapper.get('[data-testid="edit-campaign-settings"]').attributes()).toHaveProperty('disabled')
    await wrapper.setProps({ disabled: false })
    await openSettings(wrapper)
    await wrapper.setProps({ disabled: true })
    await wrapper.get('#campaign-settings-form').trigger('submit')
    expect(apiClient.put).not.toHaveBeenCalled()
    await wrapper.setProps({ disabled: false, campaign: campaign({ status: 'APPROVED', mailboxAccountId: 'mailbox-1' }) })
    await wrapper.get('#campaign-settings-form').trigger('submit')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('rejects disabled, untested, failed, and missing mailbox bindings', async () => {
    wrapper = mountPanel()
    await openSettings(wrapper)
    for (const accountId of ['disabled', 'untested', 'failed']) {
      expect(wrapper.get(`option[value="${accountId}"]`).attributes()).toHaveProperty('disabled')
      await wrapper.get('#campaign-settings-mailbox').setValue(accountId)
      await wrapper.get('#campaign-settings-form').trigger('submit')
    }
    await wrapper.get('#campaign-settings-mailbox').setValue('')
    await wrapper.get('#campaign-settings-form').trigger('submit')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it.each(['name', 'purpose', 'from-name', 'reply-to'])('blocks a blank required %s', async (field) => {
    wrapper = mountPanel(campaign({ mailboxAccountId: 'mailbox-1' }))
    await openSettings(wrapper)
    await wrapper.get(`#campaign-settings-${field}`).setValue('   ')
    await wrapper.get('#campaign-settings-form').trigger('submit')
    expect(apiClient.put).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="save-campaign-settings"]').attributes()).toHaveProperty('disabled')
  })

  it('keeps the form and edits after a conflict and never retries automatically', async () => {
    vi.mocked(apiClient.put).mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { detail: '活动已被修改，请重新加载。' } } })
    wrapper = mountPanel(campaign({ mailboxAccountId: 'mailbox-1' }))
    await openSettings(wrapper)
    await wrapper.get('#campaign-settings-name').setValue('Unsaved local title')
    await wrapper.get('#campaign-settings-form').trigger('submit')
    await flushPromises()
    await wrapper.setProps({ campaign: campaign({ lockVersion: 8, mailboxAccountId: 'mailbox-1' }) })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('活动已被修改')
    expect(wrapper.get<HTMLInputElement>('#campaign-settings-name').element.value).toBe('Unsaved local title')
    expect(apiClient.put).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.emitted('editing')).toEqual([[true]])
  })

  it('keeps a failed mailbox load visible and prevents saving stale options', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('offline'))
    wrapper = mountPanel(campaign({ mailboxAccountId: 'mailbox-1' }))
    await openSettings(wrapper)
    expect(wrapper.get('[role="alert"]').text()).toContain('收信邮箱')
    await wrapper.get('#campaign-settings-form').trigger('submit')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('prevents duplicate submissions and closing while a save is pending', async () => {
    let resolveSave!: (value: { data: CampaignView }) => void
    vi.mocked(apiClient.put).mockImplementation(() => new Promise((resolve) => { resolveSave = resolve }))
    wrapper = mountPanel(campaign({ mailboxAccountId: 'mailbox-1' }))
    await openSettings(wrapper)
    await wrapper.get('#campaign-settings-form').trigger('submit')
    expect(wrapper.get('[data-testid="save-campaign-settings"]').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('[data-testid="cancel-campaign-settings"]').attributes()).toHaveProperty('disabled')
    await wrapper.get('#campaign-settings-form').trigger('submit')
    expect(apiClient.put).toHaveBeenCalledTimes(1)
    resolveSave({ data: campaign({ lockVersion: 8 }) })
    await flushPromises()
    expect(wrapper.emitted('saved')).toEqual([[]])
  })
})

function mountPanel(value = campaign(), permissions: Permission[] = ['campaign:read', 'campaign:create']): VueWrapper {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'test-token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: { id: 'editor', username: 'editor', displayName: 'Editor', roles: ['EDITOR'], permissions, mustChangePassword: false },
  })
  return mount(CampaignSettingsPanel, {
    props: { campaign: value },
    global: {
      plugins: [pinia],
      // The panel owns the form; dialog focus/teleport behavior is covered by primitive tests.
      stubs: { DsModal: { props: ['open'], template: '<section v-if="open"><slot /><slot name="actions" /></section>' } },
    },
  })
}

async function openSettings(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-testid="edit-campaign-settings"]').trigger('click')
  await flushPromises()
}

function mailbox(overrides: Partial<MailboxAccountView> = {}): MailboxAccountView {
  return {
    id: 'mailbox-1', name: 'Research inbox', protocol: 'IMAP', host: 'mail.example.org', port: 993,
    tlsMode: 'TLS_IMPLICIT', username: 'private-user', passwordConfigured: true, folderName: 'INBOX', enabled: true,
    lastTestedAt: '2026-09-21T00:00:00Z', lastTestStatus: 'SUCCEEDED', lastTestError: null,
    lockVersion: 2, createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z', ...overrides,
  }
}

function mailboxPage(items: MailboxAccountView[]) {
  return { items, page: 1, pageSize: 100, total: items.length, totalPages: items.length ? 1 : 0 }
}

function campaign(overrides: Partial<CampaignView> = {}): CampaignView {
  return {
    id: 'campaign-1', name: 'Research outreach', purpose: 'Invite research authors', status: 'DRAFT',
    templateId: 'template-1', templateName: 'Invitation', templateVersion: 1,
    segmentId: 'segment-1', segmentName: 'Authors', smtpAccountId: 'smtp-1', smtpName: 'Research SMTP',
    mailboxAccountId: null, fromName: 'Research Team', fromEmail: 'sender@example.org', replyTo: 'reply@example.org',
    trackingOpensEnabled: true, trackingClicksEnabled: false,
    generationStatus: 'COMPLETED', generationProvider: 'openai', generationModel: 'model', generationJobId: 'job-1',
    recipientCounts: { queued: 0, running: 0, generated: 8, failed: 0, total: 8 },
    deliveryCounts: { queued: 0, connecting: 0, smtpAccepted: 0, temporaryFailure: 0, permanentFailure: 0, bounced: 0, suppressed: 0, unsubscribed: 0, canceled: 0, outcomeUnknown: 0, total: 0 },
    lockVersion: 7, submittedForReviewAt: null, approvedAt: null, approvedBy: null,
    rejectedAt: null, rejectedBy: null, rejectionReason: null, scheduledAt: null, startedAt: null,
    completedAt: null, canceledAt: null, statusChangedAt: null, statusChangedBy: null,
    createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z', ...overrides,
  }
}
