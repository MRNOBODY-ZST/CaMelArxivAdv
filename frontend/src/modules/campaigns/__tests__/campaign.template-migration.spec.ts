import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import CampaignDetailView from '@/modules/campaigns/CampaignDetailView.vue'
import { campaignsApi } from '@/modules/campaigns/campaigns.api'
import CampaignTemplateMigrationPanel from '@/modules/campaigns/CampaignTemplateMigrationPanel.vue'
import { templateMigrationsApi } from '@/modules/campaigns/template-migrations.api'
import { emailApi } from '@/modules/email/email.api'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { CampaignView, CampaignRecipient } from '@/modules/campaigns/campaigns.types'
import type { TemplateView, TemplateVersionView } from '@/modules/email/email.types'
import { MIGRATION_RECOVERY_KEY } from '@/modules/campaigns/template-migrations.reconciliation'
vi.mock('@/modules/campaigns/template-migrations.api', () => ({ templateMigrationsApi: { preview: vi.fn(), migrate: vi.fn(), getByKey: vi.fn() } }))
vi.mock('@/modules/email/email.api', () => ({ emailApi: { listTemplates: vi.fn(), listTemplateVersions: vi.fn() } }))
vi.mock('@/modules/campaigns/campaigns.api', () => ({ campaignsApi: { getCampaign: vi.fn(), listRecipients: vi.fn(), preflightCampaign: vi.fn(), listSafetyRuns: vi.fn(), listDeliveries: vi.fn(), runtimeStatus: vi.fn() }, campaignErrorMessage: (_cause: unknown, fallback: string) => fallback }))
const source = '20000000-0000-0000-0000-000000000001'
const template = '30000000-0000-0000-0000-000000000001'
const version = '40000000-0000-0000-0000-000000000001'
const recipient = '50000000-0000-0000-0000-000000000001'
const actor = '10000000-0000-0000-0000-000000000001'
const campaign = { id: source, lockVersion: 3, status: 'PAUSED' } as CampaignView
const preview = { sourceCampaignId: source, expectedLockVersion: 3, targetTemplateId: template, targetTemplateVersionId: version, expectedTemplateLockVersion: 4, expectedMembershipDigest: 'a'.repeat(64), recipientIds: [recipient], counts: { historicalSelected: 1, actionableQueued: 1, transferred: 0, migrationBlocked: 0 }, ready: true, reasons: [] }
const result = { id: 'migration', sourceCampaignId: source, targetCampaignId: 'target', targetTemplateId: template, targetTemplateVersionId: version, selectedCount: 1, migratedCount: 1, status: 'MIGRATED', reasons: [] }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((r) => { resolve = r }); return { promise, resolve } }
async function setup(permissions = ['campaign:read', 'campaign:create']) {
  const pinia = createPinia(); setActivePinia(pinia)
  useAuthStore().acceptSession({ accessToken: 'synthetic', tokenType: 'Bearer', expiresInSeconds: 600, user: { id: actor, username: 'actor', displayName: 'Actor', roles: [], permissions: permissions as never, mustChangePassword: false } })
  const wrapper = mount(CampaignTemplateMigrationPanel, { props: { sourceCampaignId: source, campaign, recipients: [{ id: recipient, paperTitle: 'Frozen paper' } as CampaignRecipient], disabled: false }, global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
  await flushPromises(); await wrapper.get('[data-testid="migration-open"]').trigger('click'); await flushPromises()
  await wrapper.get('#migration-template').setValue(template); await flushPromises()
  await wrapper.get('#migration-version').setValue(version)
  await wrapper.get(`[data-testid="migration-select-${recipient}"]`).setValue(true)
  return wrapper
}
beforeEach(() => {
  vi.clearAllMocks()
  globalThis.localStorage.clear()
  let tail = Promise.resolve()
  vi.stubGlobal('navigator', { locks: { request: (_name: string, _options: unknown, callback: () => unknown) => {
    const work = tail.then(callback); tail = work.then(() => undefined, () => undefined); return work
  } } })
  vi.mocked(emailApi.listTemplates).mockResolvedValue({ items: [{ id: template, name: 'Frozen template', status: 'ACTIVE' } as TemplateView], page: 1, pageSize: 100, total: 1, totalPages: 1 })
  vi.mocked(emailApi.listTemplateVersions).mockResolvedValue([{ id: version, versionNumber: 2 } as TemplateVersionView])
  vi.mocked(templateMigrationsApi.preview).mockResolvedValue(preview)
  vi.mocked(templateMigrationsApi.migrate).mockResolvedValue(result as never)
  vi.mocked(templateMigrationsApi.getByKey).mockResolvedValue(result as never)
})
afterEach(() => vi.unstubAllGlobals())
describe('exact draft migration', () => {
  it('selects exact IDs across pages and creates only an independent draft', async () => {
    const w = await setup()
    const second = '50000000-0000-0000-0000-000000000002'
    await w.setProps({ recipients: [{ id: second, paperTitle: 'Second paper' } as CampaignRecipient] })
    await w.get(`[data-testid="migration-select-${second}"]`).setValue(true)
    vi.mocked(templateMigrationsApi.preview).mockResolvedValue({ ...preview, recipientIds: [recipient, second], counts: { ...preview.counts, historicalSelected: 2, actionableQueued: 2 } })
    await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    expect(templateMigrationsApi.preview).toHaveBeenCalledWith(source, { targetTemplateId: template, targetTemplateVersionId: version, recipientIds: [recipient, second] })
    vi.mocked(templateMigrationsApi.migrate).mockResolvedValue({ ...result, selectedCount: 2, migratedCount: 2 } as never)
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    expect(w.text()).toContain('独立草稿已创建')
    expect(w.emitted('completed')?.[0]?.[0]).toEqual(expect.objectContaining({ status: 'MIGRATED' }))
    w.unmount()
  })
  it('timeoutUsesOriginalKeyAndDoesNotResubmit', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.migrate).mockRejectedValue({ isAxiosError: true, code: 'ECONNABORTED' })
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    const original = vi.mocked(templateMigrationsApi.migrate).mock.calls[0]!
    await w.setProps({ sourceCampaignId: 'other-source', campaign: null, recipients: [] })
    await w.get('[data-testid="migration-readback"]').trigger('click'); await flushPromises()
    expect(templateMigrationsApi.getByKey).toHaveBeenCalledWith(source, original[1].idempotencyKey)
    expect(templateMigrationsApi.migrate).toHaveBeenCalledTimes(1)
    expect(w.text()).toContain('独立草稿已创建'); w.unmount()
  })
  it('timeout survives unmount and a fresh mounted panel without another POST', async () => {
    const first = await setup(); await first.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.migrate).mockRejectedValue({ isAxiosError: true, code: 'ECONNABORTED' })
    await first.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    const original = vi.mocked(templateMigrationsApi.migrate).mock.calls[0]!
    first.unmount()
    const freshPinia = createPinia(); setActivePinia(freshPinia)
    useAuthStore().acceptSession({ accessToken: 'new-session', tokenType: 'Bearer', expiresInSeconds: 600, user: { id: actor, username: 'actor', displayName: 'Actor', roles: [], permissions: ['campaign:read'], mustChangePassword: false } })
    const restored = mount(CampaignTemplateMigrationPanel, { props: { sourceCampaignId: '20000000-0000-0000-0000-000000000002', campaign: null, recipients: [] }, global: { plugins: [freshPinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(restored.text()).toContain('原提交待对账')
    expect(restored.find('[data-testid="migration-open"]').exists()).toBe(false)
    expect(templateMigrationsApi.migrate).toHaveBeenCalledTimes(1)
    expect(templateMigrationsApi.getByKey).not.toHaveBeenCalled()
    expect(restored.text()).toContain(original[1].idempotencyKey)
    await restored.get('[data-testid="migration-readback"]').trigger('click'); await flushPromises()
    expect(templateMigrationsApi.getByKey).toHaveBeenCalledWith(source, original[1].idempotencyKey)
    expect(restored.text()).toContain('独立草稿已创建'); expect(templateMigrationsApi.migrate).toHaveBeenCalledTimes(1)
    restored.unmount()
  })
  it('conflictInvalidatesPreview', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.migrate).mockRejectedValue({ isAxiosError: true, response: { status: 409 } })
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    expect(w.find('[data-testid="migration-commit"]').exists()).toBe(false)
    expect(w.text()).toContain('已变化'); w.unmount()
  })
  it('definite original-request conflict safely clears exact durable metadata for re-preflight', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.migrate).mockRejectedValue({ isAxiosError: true, response: { status: 409 } })
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBeNull()
    expect(w.find('[data-testid="migration-readback"]').exists()).toBe(false)
    expect(w.get('[data-testid="migration-preview"]').attributes('disabled')).toBeUndefined()
    expect(templateMigrationsApi.migrate).toHaveBeenCalledTimes(1); w.unmount()
  })
  it('failed durable write blocks before POST and never offers a replacement key', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    const failure = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    expect(templateMigrationsApi.migrate).not.toHaveBeenCalled()
    expect(w.text()).toContain('迁移已暂停'); expect(w.find('[data-testid="migration-commit"]').exists()).toBe(false)
    failure.mockRestore(); w.unmount()
  })
  it('corrupt persistence and unavailable locks are explicit holds without template or POST calls', async () => {
    globalThis.localStorage.setItem(MIGRATION_RECOVERY_KEY, '{broken')
    const pinia = createPinia(); setActivePinia(pinia)
    useAuthStore().acceptSession({ accessToken: 'test', tokenType: 'Bearer', expiresInSeconds: 600, user: { id: actor, username: 'actor', displayName: 'Actor', roles: [], permissions: ['campaign:read', 'campaign:create'], mustChangePassword: false } })
    const corrupt = mount(CampaignTemplateMigrationPanel, { props: { sourceCampaignId: source, campaign, recipients: [] }, global: { plugins: [pinia] } }); await flushPromises()
    expect(corrupt.text()).toContain('迁移已暂停'); expect(corrupt.find('[data-testid="migration-open"]').exists()).toBe(false)
    expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBe('{broken'); corrupt.unmount()
    globalThis.localStorage.clear(); vi.stubGlobal('navigator', {})
    const unsupported = mount(CampaignTemplateMigrationPanel, { props: { sourceCampaignId: source, campaign, recipients: [] }, global: { plugins: [pinia] } }); await flushPromises()
    expect(unsupported.text()).toContain('跨标签页锁不可用'); expect(templateMigrationsApi.migrate).not.toHaveBeenCalled(); expect(emailApi.listTemplates).not.toHaveBeenCalled(); unsupported.unmount()
  })
  it('another-tab record arriving before commit cannot be overwritten or submitted', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    const other = JSON.stringify({ protocol: 'campaign-template-migration', version: 1, actor: '10000000-0000-0000-0000-000000000002', source, command: { expectedLockVersion: 3, targetTemplateId: template, targetTemplateVersionId: version, expectedTemplateLockVersion: 4, recipientIds: [recipient], expectedMembershipDigest: 'a'.repeat(64), idempotencyKey: 'another_tab' } })
    globalThis.localStorage.setItem(MIGRATION_RECOVERY_KEY, other)
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    expect(templateMigrationsApi.migrate).not.toHaveBeenCalled(); expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBe(other)
    expect(w.get('[data-testid="migration-readback"]').attributes()).toHaveProperty('disabled'); expect(w.text()).toContain('原提交账号不同'); w.unmount()
  })
  it('storage events refresh another live tab without automatically adopting execution', async () => {
    const w = await setup()
    const other = JSON.stringify({ protocol: 'campaign-template-migration', version: 1, actor, source, command: { expectedLockVersion: 3, targetTemplateId: template, targetTemplateVersionId: version, expectedTemplateLockVersion: 4, recipientIds: [recipient], expectedMembershipDigest: 'a'.repeat(64), idempotencyKey: 'another_tab' } })
    globalThis.localStorage.setItem(MIGRATION_RECOVERY_KEY, other)
    globalThis.dispatchEvent(new StorageEvent('storage', { key: MIGRATION_RECOVERY_KEY, newValue: other })); await flushPromises()
    expect(w.text()).toContain('原提交待对账'); expect(w.text()).toContain('another_tab')
    expect(templateMigrationsApi.migrate).not.toHaveBeenCalled(); expect(templateMigrationsApi.getByKey).not.toHaveBeenCalled(); w.unmount()
  })
  it('by-key404 after an ambiguous submit never permits a new key', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.migrate).mockRejectedValue({ isAxiosError: true, code: 'ECONNABORTED' })
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    const original = globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)
    vi.mocked(templateMigrationsApi.getByKey).mockRejectedValue({ isAxiosError: true, response: { status: 404 } })
    await w.get('[data-testid="migration-readback"]').trigger('click'); await flushPromises()
    expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBe(original)
    expect(w.find('[data-testid="migration-commit"]').exists()).toBe(false); expect(templateMigrationsApi.migrate).toHaveBeenCalledTimes(1); w.unmount()
  })
  it('slot absence alone does not accept a POST result; explicit matching GET releases the known logical hold', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    const reply = deferred<typeof result>(); vi.mocked(templateMigrationsApi.migrate).mockReturnValue(reply.promise as never)
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    globalThis.localStorage.removeItem(MIGRATION_RECOVERY_KEY)
    reply.resolve(result); await flushPromises()
    expect(w.text()).not.toContain('独立草稿已创建'); expect(w.text()).toContain('仍待对账')
    const remove = vi.spyOn(Storage.prototype, 'removeItem'), write = vi.spyOn(Storage.prototype, 'setItem')
    await w.get('[data-testid="migration-readback"]').trigger('click'); await flushPromises()
    expect(w.text()).toContain('独立草稿已创建'); expect(templateMigrationsApi.migrate).toHaveBeenCalledTimes(1)
    expect(w.text()).not.toContain('迁移已暂停')
    expect(w.get('[data-testid="migration-preview"]').attributes('disabled')).toBeUndefined()
    expect(remove).not.toHaveBeenCalled(); expect(write).not.toHaveBeenCalled()
    remove.mockRestore(); write.mockRestore(); w.unmount()
  })
  it('changingRouteOrMembershipInvalidatesPreview', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    await w.get(`[data-testid="migration-select-${recipient}"]`).setValue(false)
    expect(w.find('[data-testid="migration-commit"]').exists()).toBe(false)
    await w.get(`[data-testid="migration-select-${recipient}"]`).setValue(true)
    await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    await w.setProps({ sourceCampaignId: 'other', campaign: null })
    expect(w.find('[data-testid="migration-commit"]').exists()).toBe(false); w.unmount()
  })
  it('ignores delayed preview after template or route changes', async () => {
    const w = await setup(); const late = deferred<typeof preview>(); vi.mocked(templateMigrationsApi.preview).mockReturnValue(late.promise)
    await w.get('[data-testid="migration-preview"]').trigger('click')
    await w.setProps({ sourceCampaignId: 'other', campaign: null }); late.resolve(preview); await flushPromises()
    expect(w.find('[data-testid="migration-commit"]').exists()).toBe(false); w.unmount()
  })
  it('changing a frozen version invalidates the existing preview', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    await w.get('#migration-version').setValue('')
    expect(w.find('[data-testid="migration-commit"]').exists()).toBe(false); w.unmount()
  })
  it('blocked preview shows reasons and never offers commit', async () => {
    const w = await setup()
    vi.mocked(templateMigrationsApi.preview).mockResolvedValue({ ...preview, ready: false, counts: { historicalSelected: 1, actionableQueued: 0, transferred: 0, migrationBlocked: 1 }, reasons: [{ code: 'ACTIVE_SAFETY', count: 1 }] })
    await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    expect(w.text()).toContain('ACTIVE_SAFETY'); expect(w.find('[data-testid="migration-commit"]').exists()).toBe(false)
    expect(templateMigrationsApi.migrate).not.toHaveBeenCalled(); w.unmount()
  })
  it.each(['NO_MAIL', 'CONFIG_UPDATED', 'BLOCKED'] as const)('%s is not reported as migrated', async (status) => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.migrate).mockResolvedValue({ ...result, targetCampaignId: null, migratedCount: 0, status } as never)
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    expect(w.text()).not.toContain('独立草稿已创建'); expect(w.findAll('a').length).toBe(0)
    expect(w.emitted('completed')?.[0]?.[0]).toEqual(expect.objectContaining({ status, migratedCount: 0 })); w.unmount()
  })
  it('readbackMismatchNeverReportsSuccess and stays unreconciled', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.migrate).mockRejectedValue({ isAxiosError: true, code: 'ECONNABORTED' })
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.getByKey).mockResolvedValue({ ...result, sourceCampaignId: 'other' } as never)
    await w.get('[data-testid="migration-readback"]').trigger('click'); await flushPromises()
    expect(w.text()).not.toContain('独立草稿已创建')
    expect(w.text()).toContain('不匹配'); expect(w.emitted('completed')).toBeUndefined(); expect(templateMigrationsApi.migrate).toHaveBeenCalledTimes(1); w.unmount()
  })
  it('actor change never reads or resubmits the original pending operation', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    vi.mocked(templateMigrationsApi.migrate).mockRejectedValue({ isAxiosError: true, code: 'ECONNABORTED' })
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    useAuthStore().acceptSession({ accessToken: 'second', tokenType: 'Bearer', expiresInSeconds: 600, user: { id: '10000000-0000-0000-0000-000000000002', username: 'second', displayName: 'Second', roles: [], permissions: ['campaign:read', 'campaign:create'], mustChangePassword: false } }); await flushPromises()
    expect(w.get('[data-testid="migration-readback"]').attributes()).toHaveProperty('disabled')
    await w.get('[data-testid="migration-readback"]').trigger('click'); await flushPromises()
    expect(templateMigrationsApi.getByKey).not.toHaveBeenCalled(); expect(templateMigrationsApi.migrate).toHaveBeenCalledTimes(1)
    expect(w.text()).toContain('原提交账号不同'); w.unmount()
  })
  it('keeps confirmed draft evidence while the source detail refreshes its lock version', async () => {
    const w = await setup(); await w.get('[data-testid="migration-preview"]').trigger('click'); await flushPromises()
    await w.get('[data-testid="migration-commit"]').trigger('click'); await flushPromises()
    await w.setProps({ campaign: { ...campaign, lockVersion: 4 } })
    expect(w.text()).toContain('独立草稿已创建'); w.unmount()
  })
  it('explains missing create authority without any template or mutation request', async () => {
    const pinia = createPinia(); setActivePinia(pinia)
    useAuthStore().acceptSession({ accessToken: 'test', tokenType: 'Bearer', expiresInSeconds: 600, user: { id: 'reader', username: 'reader', displayName: 'Reader', roles: [], permissions: ['campaign:read'], mustChangePassword: false } })
    const w = mount(CampaignTemplateMigrationPanel, { props: { sourceCampaignId: source, campaign, recipients: [] }, global: { plugins: [pinia] } })
    expect(w.text()).toContain('缺少活动创建权限'); expect(w.find('[data-testid="migration-open"]').exists()).toBe(false)
    expect(emailApi.listTemplates).not.toHaveBeenCalled(); expect(templateMigrationsApi.migrate).not.toHaveBeenCalled(); w.unmount()
  })
})

describe('reused campaign detail route', () => {
  function view(id: string, name: string): CampaignView {
    return { ...campaign, id, name, purpose: 'Synthetic campaign only', generationStatus: 'NOT_REQUESTED', recipientCounts: { queued: 0, running: 0, generated: 0, failed: 0, total: 1 }, deliveryCounts: { queued: 0, connecting: 0, smtpAccepted: 0, temporaryFailure: 0, permanentFailure: 0, bounced: 0, suppressed: 0, unsubscribed: 0, canceled: 0, outcomeUnknown: 0, total: 0 } } as CampaignView
  }
  async function mountDetail() {
    const pinia = createPinia(); setActivePinia(pinia)
    useAuthStore().acceptSession({ accessToken: 'test', tokenType: 'Bearer', expiresInSeconds: 600, user: { id: 'actor', username: 'actor', displayName: 'Actor', roles: [], permissions: ['campaign:read', 'campaign:create'], mustChangePassword: false } })
    vi.mocked(campaignsApi.getCampaign).mockImplementation(async (id) => view(id, id === source ? 'Original source' : 'New source'))
    vi.mocked(campaignsApi.listRecipients).mockResolvedValue({ items: [{ id: recipient, paperTitle: 'Frozen paper' } as CampaignRecipient], page: 1, pageSize: 20, total: 1, totalPages: 1 })
    vi.mocked(campaignsApi.preflightCampaign).mockResolvedValue({ ready: false, checks: {}, counts: {}, estimatedMinutes: 0, digest: 'synthetic' })
    vi.mocked(campaignsApi.listSafetyRuns).mockResolvedValue([])
    vi.mocked(campaignsApi.listDeliveries).mockResolvedValue({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 0 })
    vi.mocked(campaignsApi.runtimeStatus).mockResolvedValue({ personalizationEnabled: false, provider: 'disabled', model: 'disabled', rayConfigured: false, kafkaConfigured: false, liveSmtpAllowed: false, publicMailboxAllowed: false, generationReady: false })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/email/campaigns/:id', component: CampaignDetailView }] })
    await router.push(`/email/campaigns/${source}`); await router.isReady()
    const w = mount(RouterView, { global: { plugins: [pinia, router] } }); await flushPromises()
    return { w, router }
  }
  it('reloads reused route and ignores a delayed old page response', async () => {
    const { w, router } = await mountDetail()
    const delayed = deferred<CampaignView>(); vi.mocked(campaignsApi.getCampaign).mockReturnValueOnce(delayed.promise)
    const refresh = w.findAll('button').find((b) => b.text() === '刷新')!; await refresh.trigger('click')
    await router.push('/email/campaigns/20000000-0000-0000-0000-000000000002'); await flushPromises()
    expect(w.get('#campaign-detail-title').text()).toBe('New source')
    delayed.resolve(view(source, 'Delayed original')); await flushPromises()
    expect(w.get('#campaign-detail-title').text()).toBe('New source')
    expect(w.findComponent(CampaignTemplateMigrationPanel).props('campaign')?.id).toBe('20000000-0000-0000-0000-000000000002'); w.unmount()
  })
  it('requires loaded source identity before allowing migration', async () => {
    const { w, router } = await mountDetail()
    const delayed = deferred<CampaignView>(); vi.mocked(campaignsApi.getCampaign).mockReturnValueOnce(delayed.promise)
    await router.push('/email/campaigns/20000000-0000-0000-0000-000000000002'); await flushPromises()
    expect(w.get('[data-testid="migration-open"]').attributes()).toHaveProperty('disabled')
    delayed.resolve(view(source, 'Wrong identity')); await flushPromises()
    expect(w.get('[data-testid="migration-open"]').attributes()).toHaveProperty('disabled')
    expect(w.text()).toContain('活动详情加载失败'); w.unmount()
  })
})
