import { beforeEach, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import { bulkOutreachApi, weeklyOutreachApi } from '../bulk-weekly.api'

vi.mock('@/api/client', () => ({ apiClient: { get: vi.fn(), post: vi.fn() } }))
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(apiClient.get).mockResolvedValue({ data: { id: 'readback' } } as never)
  vi.mocked(apiClient.post).mockResolvedValue({ data: { id: 'created' } } as never)
})

it('uses the independent bulk resource and expectedVersion cancellation contract', async () => {
  const command = { name: 'First', purpose: 'Invite', targetPapers: 10000, smtpAccountId: 'smtp', templateId: 'template',
    searchPlan: [{ categoryIds: [], categoryMode: 'ANY' as const, titleKeywords: '', abstractKeywords: 'Agent', authorKeywords: '', sortBy: 'SUBMITTED_DATE' as const, sortOrder: 'DESCENDING' as const, page: 1, pageSize: 100 }],
    incremental: false, acceptDeliverabilityRisk: true, confirmation: 'AUTO_OUTREACH_SEND' as const, idempotencyKey: 'key' }
  expect((await bulkOutreachApi.create(command)).id).toBe('created')
  await bulkOutreachApi.list(2, 10)
  expect((await bulkOutreachApi.get('bulk-1')).id).toBe('readback')
  await bulkOutreachApi.cancel('bulk-1', 9)
  expect(apiClient.post).toHaveBeenNthCalledWith(1, '/outreach-bulk-runs', command)
  expect(apiClient.get).toHaveBeenNthCalledWith(1, '/outreach-bulk-runs', { params: { page: 2, pageSize: 10 } })
  expect(apiClient.get).toHaveBeenNthCalledWith(2, '/outreach-bulk-runs/bulk-1')
  expect(apiClient.post).toHaveBeenNthCalledWith(2, '/outreach-bulk-runs/bulk-1/cancel', { expectedVersion: 9 })
})

it('uses schedule views and optimistic-lock pause/resume without adding a configurable cron', async () => {
  const command = { name: 'Weekly', purpose: 'Invite', maxPapers: 5000, smtpAccountId: 'smtp', templateId: 'template', directions: ['Agent'], acceptDeliverabilityRisk: true, confirmation: 'AUTO_OUTREACH_SEND' as const, idempotencyKey: 'key' }
  expect((await weeklyOutreachApi.create(command)).id).toBe('created')
  await weeklyOutreachApi.list(3, 10)
  await weeklyOutreachApi.get('schedule-1')
  await weeklyOutreachApi.pause('schedule-1', 4)
  await weeklyOutreachApi.resume('schedule-1', 5)
  expect(apiClient.post).toHaveBeenNthCalledWith(1, '/outreach-schedules', command)
  expect(apiClient.get).toHaveBeenNthCalledWith(1, '/outreach-schedules', { params: { page: 3, pageSize: 10 } })
  expect(apiClient.get).toHaveBeenNthCalledWith(2, '/outreach-schedules/schedule-1')
  expect(apiClient.post).toHaveBeenNthCalledWith(2, '/outreach-schedules/schedule-1/pause', { expectedVersion: 4 })
  expect(apiClient.post).toHaveBeenNthCalledWith(3, '/outreach-schedules/schedule-1/resume', { expectedVersion: 5 })
})
