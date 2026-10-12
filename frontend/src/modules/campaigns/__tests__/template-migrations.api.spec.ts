import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import { templateMigrationsApi } from '@/modules/campaigns/template-migrations.api'
vi.mock('@/api/client', () => ({ apiClient: { post: vi.fn(), get: vi.fn() } }))
beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks() })
it('uses source-scoped exact protocol and encoded original key without automatic replay', async () => {
  vi.mocked(apiClient.post).mockResolvedValue({ data: { status: 'NO_MAIL' } })
  vi.mocked(apiClient.get).mockResolvedValue({ data: { status: 'NO_MAIL' } })
  const preview = { targetTemplateId: 'template', targetTemplateVersionId: 'version', recipientIds: ['second', 'first'] }
  const command = { ...preview, expectedLockVersion: 3, expectedTemplateLockVersion: 4, expectedMembershipDigest: 'a'.repeat(64), idempotencyKey: 'original-key' }
  await templateMigrationsApi.preview('source', preview)
  await templateMigrationsApi.migrate('source', command)
  await templateMigrationsApi.get('source', 'migration')
  await templateMigrationsApi.getByKey('source', 'original-key')
  expect(apiClient.post).toHaveBeenNthCalledWith(1, '/campaigns/source/template-migrations/preview', preview, expect.objectContaining({ _retry: true }))
  expect(apiClient.post).toHaveBeenNthCalledWith(2, '/campaigns/source/template-migrations', command, expect.objectContaining({ _retry: true }))
  expect(apiClient.get).toHaveBeenNthCalledWith(1, '/campaigns/source/template-migrations/migration', expect.objectContaining({ _retry: true }))
  expect(apiClient.get).toHaveBeenNthCalledWith(2, '/campaigns/source/template-migrations/by-key', expect.objectContaining({ params: { key: 'original-key' }, _retry: true }))
})
