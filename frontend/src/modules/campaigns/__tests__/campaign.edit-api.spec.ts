import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import { campaignsApi } from '@/modules/campaigns/campaigns.api'

vi.mock('@/api/client', () => ({ apiClient: { put: vi.fn() } }))

describe('audited campaign editing HTTP boundary', () => {
  beforeEach(() => vi.clearAllMocks())

  it('binds the mailbox and sender settings with the open-time campaign version', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ data: { id: 'campaign-1', lockVersion: 8 } })
    const saved = await campaignsApi.updateCampaign('campaign-1', {
      expectedLockVersion: 7, name: 'CTBench', purpose: 'Research collaboration',
      mailboxAccountId: 'mailbox-1', fromName: 'Camel Hub', replyTo: 'research@example.org',
      trackingOpensEnabled: true, trackingClicksEnabled: false,
    })
    expect(apiClient.put).toHaveBeenCalledWith('/campaigns/campaign-1', {
      expectedLockVersion: 7, name: 'CTBench', purpose: 'Research collaboration',
      mailboxAccountId: 'mailbox-1', fromName: 'Camel Hub', replyTo: 'research@example.org',
      trackingOpensEnabled: true, trackingClicksEnabled: false,
    })
    expect(saved.lockVersion).toBe(8)
  })

  it('scopes corrected content to one recipient and preserves the audit reason', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ data: { recipientId: 'author-1', lockVersion: 9 } })
    const saved = await campaignsApi.updateRecipientContent('campaign-1', 'author-1', {
      expectedLockVersion: 8, subject: 'Research on CTBench', html: '<p>{{unsubscribe_url}}</p>',
      text: 'Opt out: {{unsubscribe_url}}', rationale: 'Based on the paper abstract', reason: 'Correct unsupported comparison',
    })
    expect(apiClient.put).toHaveBeenCalledWith('/campaigns/campaign-1/recipients/author-1/content', {
      expectedLockVersion: 8, subject: 'Research on CTBench', html: '<p>{{unsubscribe_url}}</p>',
      text: 'Opt out: {{unsubscribe_url}}', rationale: 'Based on the paper abstract', reason: 'Correct unsupported comparison',
    })
    expect(saved.lockVersion).toBe(9)
  })
})
