import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import MailboxAccountsPanel from '@/modules/email/MailboxAccountsPanel.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { emailApi } from '@/modules/email/email.api'

vi.mock('@/modules/email/email.api', () => ({
  emailApi: {
    listMailboxAccounts: vi.fn(),
    previewMailboxMessages: vi.fn(),
  },
  emailErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

describe('mailbox account administration', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    vi.clearAllMocks()
    pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().acceptSession({
      accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
      user: {
        id: '5d3a9802-375f-42ee-9739-d419299bc4a8', username: 'admin', displayName: 'Admin',
        roles: ['ADMIN'], permissions: ['mailbox:read', 'mailbox:manage'], mustChangePassword: false,
      },
    })
  })

  it('shows protocol security and renders only the bounded masked header preview', async () => {
    vi.mocked(emailApi.listMailboxAccounts).mockResolvedValue({
      items: [{
        id: 'mailbox-1', name: 'Research inbox', protocol: 'IMAP', host: 'mail-test', port: 3143,
        tlsMode: 'PLAIN_LOCAL_ONLY', username: 'researcher', passwordConfigured: true,
        folderName: 'INBOX', enabled: true, lastTestedAt: null, lastTestStatus: null,
        lastTestError: null, lockVersion: 0, createdAt: '2026-08-11T00:00:00Z',
        updatedAt: '2026-08-11T00:00:00Z',
      }], page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    vi.mocked(emailApi.previewMailboxMessages).mockResolvedValue([{
      remoteId: 'imap:1', subject: 'Protocol smoke test', fromMasked: 'se***@example.test',
      receivedAt: '2026-08-11T01:00:00Z', sentAt: '2026-08-11T01:00:00Z',
      sizeBytes: 128, hasAttachments: false,
    }])

    const wrapper = mount(MailboxAccountsPanel, {
      attachTo: document.body,
      global: {
        plugins: [pinia],
        stubs: {
          DsModal: { props: ['open'], template: '<section v-if="open"><slot /><slot name="actions" /></section>' },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('IMAP')
    expect(wrapper.text()).toContain('本地白名单')
    expect(wrapper.text()).not.toContain('researcher')
    expect(wrapper.text()).toContain('re***')

    await wrapper.get('[data-testid="preview-mailbox-1"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('Protocol smoke test')
    expect(document.body.textContent).toContain('se***@example.test')
    expect(emailApi.previewMailboxMessages).toHaveBeenCalledWith('mailbox-1', 20)
    wrapper.unmount()
  })
})
