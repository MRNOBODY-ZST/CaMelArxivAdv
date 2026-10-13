import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import { outreachApi } from '@/modules/outreach/outreach.api'
import type { OutreachCreateRequest } from '@/modules/outreach/outreach.types'

vi.mock('@/api/client', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }))

describe('outreach HTTP contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(apiClient.get).mockResolvedValue({ data: { id: 'run-1' } } as never)
    vi.mocked(apiClient.post).mockResolvedValue({ data: { id: 'run-1' } } as never)
  })

  it('uses safe options, paginated history and a persisted run lookup', async () => {
    await outreachApi.options()
    await outreachApi.list(2, 10)
    await outreachApi.get('run-1')

    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/outreach-runs/options')
    expect(apiClient.get).toHaveBeenNthCalledWith(2, '/outreach-runs', { params: { page: 2, pageSize: 10 } })
    expect(apiClient.get).toHaveBeenNthCalledWith(3, '/outreach-runs/run-1')
  })

  it('creates exactly one server workflow with the explicit confirmation and UUID', async () => {
    const request: OutreachCreateRequest = {
      name: 'Research invitation', purpose: 'Invite relevant researchers', targetMode: 'EMAILS', targetCount: 100,
      smtpAccountId: 'smtp-1', templateId: 'template-1',
      criteria: {
        categoryIds: ['cs.AI'], categoryMode: 'PRIMARY', titleKeywords: 'agent', abstractKeywords: '',
        authorKeywords: '', sortBy: 'SUBMITTED_DATE', sortOrder: 'DESCENDING', page: 1, pageSize: 20,
      },
      confirmation: 'AUTO_OUTREACH_SEND', idempotencyKey: '1c6d7bb9-5f02-4af1-a0a5-8d51ec7dc95e',
    }
    const run = await outreachApi.create(request)

    expect(apiClient.post).toHaveBeenCalledTimes(1)
    expect(apiClient.post).toHaveBeenCalledWith('/outreach-runs', request)
    expect(run.id).toBe('run-1')
  })

  it('cancels only the selected run using the current lock version', async () => {
    await outreachApi.cancel('run-1', 7)
    expect(apiClient.post).toHaveBeenCalledWith('/outreach-runs/run-1/cancel', { expectedLockVersion: 7 })
  })
})
