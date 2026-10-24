import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'
import OutreachView from '@/modules/outreach/OutreachView.vue'
import { outreachApi } from '@/modules/outreach/outreach.api'
import type { OutreachOptions, OutreachRun } from '@/modules/outreach/outreach.types'

vi.mock('@/modules/outreach/outreach.api', () => ({
  outreachApi: { options: vi.fn(), list: vi.fn(), get: vi.fn(), create: vi.fn(), cancel: vi.fn() },
}))

const mutationPermissions: Permission[] = ['campaign:read', 'paper:import', 'campaign:create', 'campaign:approve', 'campaign:send', 'campaign:pause']
const wrappers: VueWrapper[] = []

describe('one-click outreach', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.clearAllMocks()
    vi.mocked(outreachApi.options).mockResolvedValue(options())
    vi.mocked(outreachApi.list).mockResolvedValue({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 0 })
    vi.mocked(outreachApi.get).mockResolvedValue(run())
    vi.mocked(outreachApi.create).mockResolvedValue(run())
    vi.mocked(outreachApi.cancel).mockResolvedValue(run({ status: 'CANCELED', allowedActions: [], lockVersion: 8 }))
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('requires a review of the selected sender, template and exact email target before creating', async () => {
    const { wrapper, router } = await mountView()
    await fillForm(wrapper)
    await wrapper.get('#outreach-form').trigger('submit')

    expect(outreachApi.create).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="outreach-confirmation"]').text()).toContain('sender@example.invalid')
    expect(wrapper.get('[data-testid="outreach-confirmation"]').text()).toContain('论文邀请 · v3')
    expect(wrapper.get('[data-testid="outreach-confirmation"]').text()).toContain('100 个唯一可发送地址')
    expect(wrapper.get('[data-testid="confirm-create"]').attributes()).toHaveProperty('disabled')

    await wrapper.get('#outreach-send-confirmation').setValue(true)
    await wrapper.get('[data-testid="confirm-create"]').trigger('click')
    await flushPromises()

    expect(outreachApi.create).toHaveBeenCalledTimes(1)
    expect(outreachApi.create).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Agent invitation', purpose: 'Invite researchers', targetMode: 'EMAILS', targetCount: 100,
      smtpAccountId: 'smtp-1', templateId: 'template-1', confirmation: 'AUTO_OUTREACH_SEND',
      idempotencyKey: expect.stringMatching(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i),
      criteria: expect.objectContaining({ categoryIds: ['cs.AI', 'cs.LG'], titleKeywords: 'agent', page: 1 }),
    }))
    expect(router.currentRoute.value.query.run).toBe('run-1')
    expect(sessionStorage.length).toBe(0)
  })

  it('keeps paper targets distinct from the email cap and search budget', async () => {
    const { wrapper } = await mountView()
    await fillForm(wrapper)
    await wrapper.get('#outreach-target-mode').setValue('PAPERS')
    await wrapper.get('#outreach-target-count').setValue('75')
    await wrapper.get('#outreach-form').trigger('submit')

    const summary = wrapper.get('[data-testid="outreach-confirmation"]').text()
    expect(summary).toContain('75 篇符合条件的论文')
    expect(summary).toContain('最多 120 个唯一地址')
    expect(summary).toContain('不会隐藏裁剪')
    expect(wrapper.text()).toContain('最多搜索 100 篇论文')
  })

  it.each([['EMAILS', '121'], ['PAPERS', '101'], ['EMAILS', '0'], ['PAPERS', '1.5']])(
    'rejects an out-of-scope %s target %s without opening confirmation', async (mode, count) => {
      const { wrapper } = await mountView()
      await fillForm(wrapper)
      await wrapper.get('#outreach-target-mode').setValue(mode)
      await wrapper.get('#outreach-target-count').setValue(count)
      await wrapper.get('#outreach-form').trigger('submit')
      expect(wrapper.find('[data-testid="outreach-confirmation"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('目标数量必须是')
      expect(outreachApi.create).not.toHaveBeenCalled()
    },
  )

  it('prevents a second confirmation click while the create request is in flight', async () => {
    let finish!: (value: OutreachRun) => void
    vi.mocked(outreachApi.create).mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const { wrapper } = await mountView()
    await fillForm(wrapper)
    await wrapper.get('#outreach-form').trigger('submit')
    await wrapper.get('#outreach-send-confirmation').setValue(true)
    await wrapper.get('[data-testid="confirm-create"]').trigger('click')
    await wrapper.get('[data-testid="confirm-create"]').trigger('click')

    expect(outreachApi.create).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="confirm-create"]').attributes()).toHaveProperty('disabled')
    finish(run())
    await flushPromises()
  })

  it('retains the same idempotent payload after an ambiguous failure and a page refresh', async () => {
    vi.mocked(outreachApi.create).mockRejectedValueOnce(new Error('network response lost'))
    const first = await mountView()
    await fillForm(first.wrapper)
    await first.wrapper.get('#outreach-form').trigger('submit')
    await first.wrapper.get('#outreach-send-confirmation').setValue(true)
    await first.wrapper.get('[data-testid="confirm-create"]').trigger('click')
    await flushPromises()
    const original = vi.mocked(outreachApi.create).mock.calls[0]![0]
    expect(sessionStorage.length).toBe(1)
    expect(sessionStorage.getItem(sessionStorage.key(0)!)).not.toContain('memory-token')
    expect(first.wrapper.text()).toContain('创建结果尚未确认')
    first.wrapper.unmount()

    const held = options()
    held.productionHoldReason = '仅限制生产发送启动'
    vi.mocked(outreachApi.options).mockResolvedValue(held)
    const second = await mountView()
    expect(second.wrapper.get<HTMLInputElement>('#outreach-name').element.value).toBe('Agent invitation')
    expect(outreachApi.create).toHaveBeenCalledTimes(1)
    await second.wrapper.get('#outreach-form').trigger('submit')
    await second.wrapper.get('#outreach-send-confirmation').setValue(true)
    await second.wrapper.get('[data-testid="confirm-create"]').trigger('click')
    await flushPromises()
    expect(outreachApi.create).toHaveBeenNthCalledWith(2, original)
  })

  it('restores safe progress from the URL without repeating any mutation', async () => {
    const { wrapper } = await mountView('/email/automation?run=run-1')
    expect(outreachApi.get).toHaveBeenCalledWith('run-1')
    expect(outreachApi.create).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="progress-paperCount"]').text()).toContain('84')
    expect(wrapper.get('[data-testid="progress-smtpAccepted"]').text()).toContain('60')
    expect(wrapper.get('[data-testid="progress-unknownCount"]').text()).toContain('1')
    expect(wrapper.text()).toContain('SMTP 接受不代表送达')
    expect(wrapper.get('[data-testid="outreach-campaign-link"]').attributes('href')).toBe('/email/campaigns/campaign-1')
  })

  it('polls an active run every three seconds and stops at a terminal state', async () => {
    vi.useFakeTimers()
    const { wrapper } = await mountView('/email/automation?run=run-1')
    await vi.advanceTimersByTimeAsync(2999)
    expect(outreachApi.get).toHaveBeenCalledTimes(1)
    vi.mocked(outreachApi.get).mockResolvedValue(run({ status: 'BLOCKED', stage: 'PREPARE', message: '有效联系人不足', allowedActions: [] }))
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(outreachApi.get).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('有效联系人不足')
    await vi.advanceTimersByTimeAsync(9000)
    expect(outreachApi.get).toHaveBeenCalledTimes(2)
  })

  it('requires cancellation confirmation and uses the displayed lock version', async () => {
    const { wrapper } = await mountView('/email/automation?run=run-1')
    await wrapper.get('[data-testid="review-cancel"]').trigger('click')
    expect(outreachApi.cancel).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已经发送的邮件无法撤回')
    await wrapper.get('[data-testid="confirm-cancel"]').trigger('click')
    await flushPromises()
    expect(outreachApi.cancel).toHaveBeenCalledWith('run-1', 7)
    expect(wrapper.text()).toContain('已取消')
  })

  it('refreshes a stale cancellation conflict without blindly retrying the mutation', async () => {
    vi.mocked(outreachApi.cancel).mockRejectedValue({ isAxiosError: true, response: { status: 409 } })
    const { wrapper } = await mountView('/email/automation?run=run-1')
    await wrapper.get('[data-testid="review-cancel"]').trigger('click')
    await wrapper.get('[data-testid="confirm-cancel"]').trigger('click')
    await flushPromises()
    expect(outreachApi.cancel).toHaveBeenCalledTimes(1)
    expect(outreachApi.get).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('状态已变化')
  })

  it.each(['paper:import', 'campaign:create', 'campaign:approve', 'campaign:send'] as Permission[])(
    'disables creation but still permits authorized cancellation when %s is missing', async (missing) => {
      const { wrapper } = await mountView('/email/automation?run=run-1', mutationPermissions.filter((permission) => permission !== missing))
      expect(wrapper.get('[data-testid="review-create"]').attributes()).toHaveProperty('disabled')
      expect(wrapper.find('[data-testid="review-cancel"]').exists()).toBe(true)
      await wrapper.get('#outreach-form').trigger('submit')
      expect(outreachApi.create).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('创建任务需要')
    },
  )

  it('shows unavailable option explanations but permits preparation under a production sending hold', async () => {
    const held = options()
    held.productionHoldReason = '生产投递暂停：待解除诊断 hold'
    held.senders.push({ id: 'smtp-disabled', name: '未测试邮箱', fromEmail: 'disabled@example.invalid', replyToEmail: null, available: false, message: '账户未通过测试' })
    held.templates.push({ id: 'template-disabled', name: '待检查模板', currentVersion: 2, available: false, message: '模板检查未通过' })
    vi.mocked(outreachApi.options).mockResolvedValue(held)
    const { wrapper } = await mountView()
    expect(wrapper.get('#outreach-sender option[value="smtp-disabled"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.get('#outreach-template option[value="template-disabled"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.text()).toContain('账户未通过测试')
    expect(wrapper.text()).toContain('模板检查未通过')
    expect(wrapper.text()).toContain(held.productionHoldReason)
    expect(wrapper.get('[data-testid="review-create"]').attributes()).not.toHaveProperty('disabled')
    expect(wrapper.text()).toContain('仍可创建任务并执行搜索与准备')
    await fillForm(wrapper)
    await wrapper.get('#outreach-form').trigger('submit')
    expect(wrapper.get('[data-testid="outreach-confirmation"]').text()).toContain('服务端会在发送启动前阻止')
    await wrapper.get('#outreach-send-confirmation').setValue(true)
    await wrapper.get('[data-testid="confirm-create"]').trigger('click')
    await flushPromises()
    expect(outreachApi.create).toHaveBeenCalledTimes(1)
  })

  it('allows a campaign reader with only pause authority to cancel but not create', async () => {
    const { wrapper } = await mountView('/email/automation?run=run-1', ['campaign:read', 'campaign:pause'])
    expect(wrapper.get('[data-testid="review-create"]').attributes()).toHaveProperty('disabled')
    await wrapper.get('[data-testid="review-cancel"]').trigger('click')
    await wrapper.get('[data-testid="confirm-cancel"]').trigger('click')
    await flushPromises()
    expect(outreachApi.cancel).toHaveBeenCalledWith('run-1', 7)
    expect(outreachApi.create).not.toHaveBeenCalled()
  })

  it('requires pause authority for cancellation even when the user can create runs', async () => {
    const { wrapper } = await mountView('/email/automation?run=run-1', mutationPermissions.filter((permission) => permission !== 'campaign:pause'))
    expect(wrapper.get('[data-testid="review-create"]').attributes()).not.toHaveProperty('disabled')
    expect(wrapper.find('[data-testid="review-cancel"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('取消任务需要活动暂停权限')
  })

  it('rechecks pause authority before the cancellation POST', async () => {
    const { wrapper } = await mountView('/email/automation?run=run-1')
    await wrapper.get('[data-testid="review-cancel"]').trigger('click')
    useAuthStore().user!.permissions = mutationPermissions.filter((permission) => permission !== 'campaign:pause')
    await wrapper.get('[data-testid="confirm-cancel"]').trigger('click')
    expect(outreachApi.cancel).not.toHaveBeenCalled()
  })

  it('restores a historical run by selection and keeps server-provided terminal messages', async () => {
    vi.mocked(outreachApi.list).mockResolvedValue({ items: [run({ status: 'COMPLETED', allowedActions: [] })], page: 1, pageSize: 10, total: 1, totalPages: 1 })
    vi.mocked(outreachApi.get).mockResolvedValue(run({ status: 'COMPLETED', allowedActions: [] }))
    const { wrapper, router } = await mountView()
    await wrapper.get('[data-testid="history-run-run-1"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.run).toBe('run-1')
    expect(outreachApi.get).toHaveBeenCalledWith('run-1')
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.find('[data-testid="review-cancel"]').exists()).toBe(false)
  })

  it('refuses creation if the stable retry key cannot be persisted across refresh', async () => {
    const { wrapper } = await mountView()
    await fillForm(wrapper)
    await wrapper.get('#outreach-form').trigger('submit')
    await wrapper.get('#outreach-send-confirmation').setValue(true)
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Storage unavailable') })
    await wrapper.get('[data-testid="confirm-create"]').trigger('click')
    await flushPromises()
    expect(outreachApi.create).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('会话存储不可用')
    storage.mockRestore()
  })

  it('summarizes and submits every selected structured search constraint', async () => {
    const { wrapper } = await mountView()
    await fillForm(wrapper)
    await wrapper.get('#outreach-category-mode').setValue('PRIMARY')
    await wrapper.get('#outreach-submittedFrom').setValue('2026-09-01')
    await wrapper.get('#outreach-submittedTo').setValue('2026-10-01')
    await wrapper.get('#outreach-updatedFrom').setValue('2026-09-10')
    await wrapper.get('#outreach-doi').setValue('false')
    await wrapper.get('#outreach-journal').setValue('true')
    await wrapper.get('#outreach-source').setValue('true')
    await wrapper.get('#outreach-sort').setValue('SUBMITTED_DATE')
    await wrapper.get('#outreach-order').setValue('ASCENDING')
    await wrapper.get('#outreach-form').trigger('submit')
    const summary = wrapper.get('[data-testid="outreach-confirmation"]').text()
    expect(summary).toContain('主分类')
    expect(summary).toContain('2026-09-01')
    expect(summary).toContain('2026-10-01')
    expect(summary).toContain('2026-09-10')
    expect(summary).toContain('DOI：否')
    expect(summary).toContain('期刊引用：是')
    expect(summary).toContain('来源可用：是')
    expect(summary).toContain('提交日期 · 升序')
    await wrapper.get('#outreach-send-confirmation').setValue(true)
    await wrapper.get('[data-testid="confirm-create"]').trigger('click')
    await flushPromises()
    expect(outreachApi.create).toHaveBeenCalledWith(expect.objectContaining({ criteria: {
      categoryIds: ['cs.AI', 'cs.LG'], categoryMode: 'PRIMARY', titleKeywords: 'agent', abstractKeywords: '', authorKeywords: '',
      submittedFrom: '2026-09-01', submittedTo: '2026-10-01', updatedFrom: '2026-09-10', hasDoi: false,
      hasJournalReference: true, sourceAvailable: true, sortBy: 'SUBMITTED_DATE', sortOrder: 'ASCENDING', page: 1, pageSize: 20,
    } }))
  })

  it('does not accept update dates alone when the search contract needs an official filter', async () => {
    const { wrapper } = await mountView()
    await wrapper.get('#outreach-name').setValue('Recent papers')
    await wrapper.get('#outreach-purpose').setValue('Research invitation')
    await wrapper.get('#outreach-updatedFrom').setValue('2026-09-01')
    await wrapper.get('#outreach-form').trigger('submit')
    expect(wrapper.find('[data-testid="outreach-confirmation"]').exists()).toBe(false)
    expect(outreachApi.create).not.toHaveBeenCalled()
  })

  it.each([
    { status: 'ACTIVE', stage: 'IMPORT', expected: '运行中' },
    { status: 'COMPLETED', stage: 'DONE', expected: '流程结束' },
  ])('localizes backend status $status and stage $stage in progress', async ({ status, stage, expected }) => {
    vi.mocked(outreachApi.get).mockResolvedValue(run({ status, stage }))
    const { wrapper } = await mountView('/email/automation?run=run-1')
    const progress = wrapper.get('[aria-labelledby="outreach-progress-title"]').text()
    expect(progress).toContain(expected)
    expect(progress).not.toContain('ACTIVE')
    expect(progress).not.toContain('DONE')
  })

  it.each(['success', 'conflict', 'network failure'])(
    'preserves selected run B when cancellation of run A completes with %s', async (outcome) => {
      const pending = deferred<OutreachRun>()
      vi.mocked(outreachApi.cancel).mockReturnValue(pending.promise)
      vi.mocked(outreachApi.get).mockImplementation(async (id) => run({ id, name: id === 'run-2' ? 'Run B' : 'Run A' }))
      const { wrapper, router } = await mountView('/email/automation?run=run-1')
      await wrapper.get('[data-testid="review-cancel"]').trigger('click')
      await wrapper.get('[data-testid="confirm-cancel"]').trigger('click')
      await router.replace('/email/automation?run=run-2')
      await flushPromises()
      if (outcome === 'success') pending.resolve(run({ name: 'Run A', status: 'CANCELED' }))
      else pending.reject(outcome === 'conflict' ? { isAxiosError: true, response: { status: 409 } } : new Error('network failed'))
      await flushPromises()

      const progress = wrapper.get('[aria-labelledby="outreach-progress-title"]').text()
      expect(router.currentRoute.value.query.run).toBe('run-2')
      expect(progress).toContain('Run B')
      expect(progress).not.toContain('Run A')
      expect(progress).not.toContain('取消结果尚未确认')
      expect(progress).not.toContain('状态已变化')
      expect(outreachApi.get).toHaveBeenCalledTimes(2)
    },
  )

  it.each(['success', 'failure'])('does not load or update data after an unmounted cancellation %s', async (outcome) => {
    const pending = deferred<OutreachRun>()
    vi.mocked(outreachApi.cancel).mockReturnValue(pending.promise)
    const { wrapper } = await mountView('/email/automation?run=run-1')
    await wrapper.get('[data-testid="review-cancel"]').trigger('click')
    await wrapper.get('[data-testid="confirm-cancel"]').trigger('click')
    wrapper.unmount()
    if (outcome === 'success') pending.resolve(run({ status: 'CANCELED' }))
    else pending.reject(new Error('network failed'))
    await flushPromises()
    expect(outreachApi.get).toHaveBeenCalledTimes(1)
    expect(outreachApi.list).toHaveBeenCalledTimes(1)
  })

  it.each(['success', 'failure'])('discards a stale history response after a newer request %s', async (outcome) => {
    const pending = deferred<Awaited<ReturnType<typeof outreachApi.list>>>()
    vi.mocked(outreachApi.list).mockReturnValueOnce(pending.promise).mockResolvedValueOnce({
      items: [run({ id: 'new-run', name: 'Latest history run' })], page: 1, pageSize: 10, total: 1, totalPages: 1,
    })
    const { wrapper } = await mountView()
    await fillForm(wrapper)
    await wrapper.get('#outreach-form').trigger('submit')
    await wrapper.get('#outreach-send-confirmation').setValue(true)
    await wrapper.get('[data-testid="confirm-create"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="history-run-new-run"]').exists()).toBe(true)
    if (outcome === 'success') pending.resolve({
      items: [run({ id: 'old-run', name: 'Stale history run' })], page: 2, pageSize: 10, total: 11, totalPages: 2,
    })
    else pending.reject(new Error('stale history failure'))
    await flushPromises()
    expect(wrapper.find('[data-testid="history-run-new-run"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="history-run-old-run"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('历史任务加载失败')
  })
})

async function mountView(path = '/email/automation', permissions = mutationPermissions) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'memory-token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: { id: 'outreach-user', username: 'operator', displayName: 'Operator', roles: ['OPERATOR'], permissions, mustChangePassword: false },
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/email/automation', component: OutreachView },
    { path: '/email/campaigns/:id', component: { template: '<div />' } },
    { path: '/jobs/:id', component: { template: '<div />' } },
  ] })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(OutreachView, { global: { plugins: [pinia, router], stubs: {
    DsModal: { props: ['open', 'title'], template: '<section v-if="open"><h2>{{ title }}</h2><slot /><slot name="actions" /></section>' },
  } } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

async function fillForm(wrapper: VueWrapper) {
  await wrapper.get('#outreach-name').setValue('  Agent invitation  ')
  await wrapper.get('#outreach-purpose').setValue('  Invite researchers  ')
  await wrapper.get('#outreach-categories').setValue(' cs.AI, cs.LG ')
  await wrapper.get('#outreach-title-keywords').setValue('agent')
  await wrapper.get('#outreach-target-count').setValue('100')
}

function options(): OutreachOptions {
  return {
    senders: [{ id: 'smtp-1', name: 'Research sender', fromEmail: 'sender@example.invalid', replyToEmail: 'reply@example.invalid', available: true, message: null }],
    templates: [{ id: 'template-1', name: '论文邀请', currentVersion: 3, available: true, message: null }],
    limits: { maxEmails: 120, maxPapers: 100, searchPaperBudget: 100 }, productionHoldReason: null,
  }
}

function run(overrides: Partial<OutreachRun> = {}): OutreachRun {
  return {
    id: 'run-1', name: 'Agent invitation', targetMode: 'EMAILS', targetCount: 100, status: 'SENDING', stage: 'SENDING', message: '等待发送额度',
    paperCount: 84, extractedCount: 80, eligibleCount: 102, selectedCount: 100, smtpAccepted: 60, failedCount: 2, unknownCount: 1,
    campaignId: 'campaign-1', importJobId: 'import-1', extractionJobId: 'extract-1', smtpAccountId: 'smtp-1', templateId: 'template-1',
    lockVersion: 7, createdAt: '2026-10-07T02:00:00Z', updatedAt: '2026-10-07T02:03:00Z', allowedActions: ['CANCEL'], ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail })
  return { promise, resolve, reject }
}
