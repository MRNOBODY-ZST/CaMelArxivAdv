import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import { emailApi } from '@/modules/email/email.api'
import { mailTrackingApi } from '@/modules/email/mail-tracking.api'
import type { TemplateSampleValues } from '@/modules/email/email.types'

vi.mock('@/api/client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const accepted = {
  status: 'SMTP_ACCEPTED' as const,
  errorCategory: null,
  correlationId: 'record-1',
}

const variables: TemplateSampleValues = {
  author_name: 'Ada Lovelace',
  first_name: 'Ada',
  paper_title: 'A test paper',
  arxiv_id: '2608.12345',
  primary_category: 'cs.AI',
  paper_url: 'https://example.invalid/paper',
  organization: 'Example Lab',
  unsubscribe_url: 'https://example.invalid/unsubscribe',
}

describe('mail tracking opt-in request bodies', () => {
  beforeEach(() => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: accepted } as never)
  })

  it('includes the chosen template-test opt-in at the HTTP boundary', async () => {
    await emailApi.testSendTemplate('template-1', 'smtp-1', 'q@example.invalid', variables, true)

    expect(apiClient.post).toHaveBeenCalledWith('/templates/template-1/test-send', {
      smtpAccountId: 'smtp-1',
      recipient: 'q@example.invalid',
      variables,
      trackOpens: true,
    })
  })

  it('keeps diagnostics untracked unless the caller explicitly opts in', async () => {
    await emailApi.sendSmtpDiagnostic('smtp-1', 'q@example.invalid')

    expect(apiClient.post).toHaveBeenCalledWith('/smtp-accounts/smtp-1/test-email', {
      recipient: 'q@example.invalid',
      subject: 'CaMel arXiv SMTP 内部测试',
      body: '本消息只用于验证本机 SMTP 接收链路。',
      trackOpens: false,
    })
  })
})

describe('mail tracking record HTTP boundaries', () => {
  beforeEach(() => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { enabled: true } } as never)
  })

  it('uses the scoped status, page, and detail endpoints', async () => {
    await mailTrackingApi.getStatus()
    await mailTrackingApi.listSendRecords(2, 10)
    await mailTrackingApi.getSendRecord('record-1')

    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/mail-tracking/status')
    expect(apiClient.get).toHaveBeenNthCalledWith(2, '/mail-send-records', { params: { page: 2, pageSize: 10 } })
    expect(apiClient.get).toHaveBeenNthCalledWith(3, '/mail-send-records/record-1')
  })
})
