import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import BulkWeeklyPanel from '../BulkWeeklyPanel.vue'
import { bulkOutreachApi, weeklyOutreachApi } from '../bulk-weekly.api'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'
import type { OutreachOptions } from '../outreach.types'
import type { BulkOutreachRun, WeeklyOutreachSchedule } from '../bulk-weekly.types'

vi.mock('../bulk-weekly.api', () => ({
  bulkOutreachApi: { create: vi.fn(), list: vi.fn(), get: vi.fn(), cancel: vi.fn() },
  weeklyOutreachApi: { create: vi.fn(), list: vi.fn(), get: vi.fn(), pause: vi.fn(), resume: vi.fn() },
}))
const permissions: Permission[] = ['campaign:read', 'paper:import', 'campaign:create', 'campaign:approve', 'campaign:send', 'campaign:pause']
const choices: OutreachOptions = { senders: [{ id: 'smtp', name: 'Public sender', fromEmail: 'sender@example.invalid', replyToEmail: 'reply@example.invalid', available: true, message: null }], templates: [{ id: 'template', name: 'Neutral invitation', currentVersion: 3, available: true, message: null }], limits: { maxPapers: 100, maxEmails: 120, searchPaperBudget: 100 }, productionHoldReason: 'Historical warning' }
const bulk: BulkOutreachRun = { id: 'bulk-1', name: 'First', targetPapers: 10000, status: 'RUNNING', stage: 'EXTRACT', message: '搜索短缺 25 篇；安全拒绝 3 篇', paperCount: 9975, extractedCount: 500, excludedCount: 3, noContactCount: 40, selectedCount: 125, smtpAccepted: 90, bouncedCount: 2, failedCount: 1, unknownCount: 1, smtpAccountId: 'smtp', templateId: 'template', incremental: false, acceptDeliverabilityRisk: true, lockVersion: 7, createdAt: '2026-10-11T00:00:00Z', updatedAt: '2026-10-11T00:00:00Z', allowedActions: ['CANCEL'], children: [{ id: 'campaign-1', status: 'SENDING', selectedCount: 120, smtpAccepted: 90, bouncedCount: 2, failedCount: 1, unknownCount: 1 }] }
const weekly: WeeklyOutreachSchedule = { id: 'schedule-1', name: 'Weekly', purpose: 'Invite', maxPapers: 5000, smtpAccountId: 'smtp', templateId: 'template', directions: ['Agent'], enabled: true, timezone: 'Asia/Shanghai', dayOfWeek: 'MONDAY', localTime: '09:00', nextRunAt: '2026-10-12T01:00:00Z', lastRunId: 'bulk-1', message: '使用配置方向，未声称趋势', acceptDeliverabilityRisk: true, lockVersion: 8, createdAt: '2026-10-11T00:00:00Z', updatedAt: '2026-10-11T00:00:00Z', allowedActions: ['PAUSE'] }
const wrappers: VueWrapper[] = []
beforeEach(() => {
  sessionStorage.clear(); vi.clearAllMocks()
  for (const api of [bulkOutreachApi, weeklyOutreachApi]) vi.mocked(api.list).mockResolvedValue({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 0 })
  vi.mocked(bulkOutreachApi.get).mockResolvedValue(bulk); vi.mocked(bulkOutreachApi.create).mockResolvedValue(bulk)
  vi.mocked(weeklyOutreachApi.get).mockResolvedValue(weekly); vi.mocked(weeklyOutreachApi.create).mockResolvedValue(weekly)
  vi.mocked(bulkOutreachApi.cancel).mockResolvedValue({ ...bulk, status: 'CANCELED', lockVersion: 8, allowedActions: [] })
  vi.mocked(weeklyOutreachApi.pause).mockResolvedValue({ ...weekly, enabled: false, lockVersion: 9, allowedActions: ['RESUME'] })
  vi.mocked(weeklyOutreachApi.resume).mockResolvedValue(weekly)
})
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.useRealTimers(); vi.restoreAllMocks() })
async function render(mode: 'bulk' | 'weekly', query = '', allowed = permissions, options = choices) {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  auth.$patch({ accessToken: 'memory-fixture', user: { id: 'user', permissions: allowed } as never })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/email/automation', component: BulkWeeklyPanel }, { path: '/email/campaigns/:id', component: { template: '<div />' } }] })
  await router.push('/email/automation' + query); await router.isReady()
  const wrapper = mount(BulkWeeklyPanel, { props: { mode, options }, global: { plugins: [pinia, router], stubs: {
    DsModal: { props: ['open', 'title'], template: '<section v-if="open"><h2>{{ title }}</h2><slot /><slot name="actions" /></section>' },
  } } })
  wrappers.push(wrapper); await flushPromises()
  return { wrapper, router, auth }
}
async function review(wrapper: VueWrapper, mode = 'bulk') {
  await wrapper.get(`#${mode}-name`).setValue('Research')
  await wrapper.get(`#${mode}-purpose`).setValue('Neutral invitation')
  await wrapper.get(`#${mode}-form`).trigger('submit')
}

it('creates the first 10000-paper run only with both explicit consents and literal separate search phrases', async () => {
  const { wrapper, router } = await render('bulk')
  expect(wrapper.get<HTMLInputElement>('#bulk-count').element.value).toBe('10000')
  await review(wrapper)
  expect(wrapper.get('[data-testid="bulk-confirm"]').attributes()).toHaveProperty('disabled')
  await wrapper.get('#bulk-send').setValue(true)
  expect(wrapper.get('[data-testid="bulk-confirm"]').attributes()).toHaveProperty('disabled')
  await wrapper.get('#bulk-risk').setValue(true)
  await wrapper.get('[data-testid="bulk-confirm"]').trigger('click'); await flushPromises()
  const request = vi.mocked(bulkOutreachApi.create).mock.calls[0]![0]
  expect(request).toMatchObject({ targetPapers: 10000, incremental: false, acceptDeliverabilityRisk: true, confirmation: 'AUTO_OUTREACH_SEND', smtpAccountId: 'smtp', templateId: 'template' })
  expect(request.searchPlan.map(c => c.abstractKeywords)).toEqual(['Agent', 'financial artificial intelligence', 'multi-agent', 'language model'])
  expect(request.searchPlan.every(c => !('submittedFrom' in c) && !('submittedTo' in c))).toBe(true)
  expect(JSON.stringify(request)).not.toContain('apiKey')
  expect(router.currentRoute.value.query.bulkRun).toBe('bulk-1')
  expect(wrapper.text()).toContain('SMTP 接受不代表送达')
})
it.each([['bulk', '10001'], ['weekly', '5001'], ['bulk', '0'], ['weekly', '1.5']] as const)('rejects invalid %s paper target %s', async (mode, count) => {
  const { wrapper } = await render(mode)
  await wrapper.get(`#${mode}-count`).setValue(count); await review(wrapper, mode)
  expect(wrapper.find(`[data-testid="${mode}-confirmation"]`).exists()).toBe(false)
  expect(wrapper.text()).toContain('目标论文数必须')
})
it('creates weekly 5000 with a fixed schedule and shows next occurrence and last parent link', async () => {
  const { wrapper, router } = await render('weekly')
  expect(wrapper.get<HTMLInputElement>('#weekly-count').element.value).toBe('5000')
  expect(wrapper.text()).toContain('周一 09:00 · Asia/Shanghai')
  await review(wrapper, 'weekly'); await wrapper.get('#weekly-send').setValue(true); await wrapper.get('#weekly-risk').setValue(true)
  await wrapper.get('[data-testid="weekly-confirm"]').trigger('click'); await flushPromises()
  expect(weeklyOutreachApi.create).toHaveBeenCalledWith(expect.objectContaining({ maxPapers: 5000, directions: ['Agent', 'financial artificial intelligence', 'multi-agent', 'language model'] }))
  expect(router.currentRoute.value.query.schedule).toBe('schedule-1')
  expect(wrapper.text()).toContain('2026/10/12 09:00')
  expect(wrapper.get('[data-testid="weekly-last-run"]').attributes('href')).toBe('/email/automation?bulkRun=bulk-1')
})
it('renders server exclusions, no contacts, shortfall, accepted counts and child campaign links distinctly', async () => {
  const { wrapper } = await render('bulk', '?bulkRun=bulk-1&run=old')
  expect(wrapper.get('[data-testid="bulk-excludedCount"]').text()).toContain('3')
  expect(wrapper.get('[data-testid="bulk-noContactCount"]').text()).toContain('40')
  expect(wrapper.get('[data-testid="bulk-smtpAccepted"]').text()).toContain('90')
  expect(wrapper.text()).toContain('安全拒绝 3 篇')
  expect(wrapper.get('[data-testid="child-campaign-1"]').attributes('href')).toBe('/email/campaigns/campaign-1')
  expect(bulkOutreachApi.create).not.toHaveBeenCalled()
})
it('gates create independently of pause authority and blocks unavailable public options', async () => {
  const { wrapper } = await render('bulk', '?bulkRun=bulk-1', ['campaign:read', 'campaign:pause'])
  await review(wrapper)
  expect(bulkOutreachApi.create).not.toHaveBeenCalled()
  expect(wrapper.get('[data-testid="bulk-review"]').attributes()).toHaveProperty('disabled')
  expect(wrapper.find('[data-testid="bulk-cancel"]').exists()).toBe(true)
  const other = await render('weekly', '', permissions, { ...choices, senders: [{ ...choices.senders[0]!, available: false, message: '测试未通过' }] })
  expect(other.wrapper.get('#weekly-sender option[value="smtp"]').attributes()).toHaveProperty('disabled')
  expect(other.wrapper.get('[data-testid="weekly-review"]').attributes()).toHaveProperty('disabled')
})
it('persists the same request across ambiguity and prevents duplicate clicks', async () => {
  let finish!: (value: BulkOutreachRun) => void
  vi.mocked(bulkOutreachApi.create).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const { wrapper } = await render('bulk')
  await review(wrapper); await wrapper.get('#bulk-send').setValue(true); await wrapper.get('#bulk-risk').setValue(true)
  await wrapper.get('[data-testid="bulk-confirm"]').trigger('click'); await wrapper.get('[data-testid="bulk-confirm"]').trigger('click')
  expect(bulkOutreachApi.create).toHaveBeenCalledTimes(1)
  finish(bulk); await flushPromises()
})
it('retains the idempotent payload on network error and after remount', async () => {
  vi.mocked(bulkOutreachApi.create).mockRejectedValueOnce(new Error('lost response'))
  const first = await render('bulk'); await review(first.wrapper)
  await first.wrapper.get('#bulk-send').setValue(true); await first.wrapper.get('#bulk-risk').setValue(true)
  await first.wrapper.get('[data-testid="bulk-confirm"]').trigger('click'); await flushPromises()
  const request = vi.mocked(bulkOutreachApi.create).mock.calls[0]![0]
  expect(first.wrapper.text()).toContain('创建结果尚未确认'); first.wrapper.unmount()
  const second = await render('bulk'); await second.wrapper.get('#bulk-form').trigger('submit')
  await second.wrapper.get('#bulk-send').setValue(true); await second.wrapper.get('#bulk-risk').setValue(true)
  await second.wrapper.get('[data-testid="bulk-confirm"]').trigger('click'); await flushPromises()
  expect(bulkOutreachApi.create).toHaveBeenNthCalledWith(2, request)
})
it('polls without overlap, stops at terminal state and never creates during readback', async () => {
  vi.useFakeTimers()
  await render('bulk', '?bulkRun=bulk-1')
  vi.mocked(bulkOutreachApi.get).mockResolvedValue({ ...bulk, status: 'COMPLETED', allowedActions: [] })
  await vi.advanceTimersByTimeAsync(3000); await flushPromises()
  await vi.advanceTimersByTimeAsync(12000)
  expect(bulkOutreachApi.get).toHaveBeenCalledTimes(2)
  expect(bulkOutreachApi.create).not.toHaveBeenCalled()
})
it('confirms cancellation and refreshes a version conflict without retrying the command', async () => {
  vi.mocked(bulkOutreachApi.cancel).mockRejectedValue({ isAxiosError: true, response: { status: 409 } })
  const { wrapper } = await render('bulk', '?bulkRun=bulk-1')
  await wrapper.get('[data-testid="bulk-cancel"]').trigger('click')
  expect(bulkOutreachApi.cancel).not.toHaveBeenCalled()
  await wrapper.get('[data-testid="bulk-action-confirm"]').trigger('click'); await flushPromises()
  expect(bulkOutreachApi.cancel).toHaveBeenCalledWith('bulk-1', 7)
  expect(bulkOutreachApi.cancel).toHaveBeenCalledTimes(1)
  expect(bulkOutreachApi.get).toHaveBeenCalledTimes(2)
  expect(wrapper.text()).toContain('状态已变化')
})
it('pauses and resumes the schedule with the displayed version', async () => {
  const { wrapper } = await render('weekly', '?schedule=schedule-1')
  await wrapper.get('[data-testid="weekly-pause"]').trigger('click'); await wrapper.get('[data-testid="weekly-action-confirm"]').trigger('click'); await flushPromises()
  expect(weeklyOutreachApi.pause).toHaveBeenCalledWith('schedule-1', 8)
  await wrapper.get('[data-testid="weekly-resume"]').trigger('click'); await wrapper.get('[data-testid="weekly-action-confirm"]').trigger('click'); await flushPromises()
  expect(weeklyOutreachApi.resume).toHaveBeenCalledWith('schedule-1', 9)
})

it.each(['success', 'conflict', 'network'] as const)('preserves new selection when an old cancellation finishes with %s', async outcome => {
  let resolve!: (value: BulkOutreachRun) => void
  let reject!: (error: unknown) => void
  vi.mocked(bulkOutreachApi.cancel).mockReturnValue(new Promise((yes, no) => { resolve = yes; reject = no }))
  vi.mocked(bulkOutreachApi.get).mockImplementation(async id => ({ ...bulk, id, name: id === 'bulk-2' ? 'New selection' : 'First' }))
  const { wrapper, router } = await render('bulk', '?bulkRun=bulk-1')
  await wrapper.get('[data-testid="bulk-cancel"]').trigger('click'); await wrapper.get('[data-testid="bulk-action-confirm"]').trigger('click')
  await router.replace('/email/automation?bulkRun=bulk-2'); await flushPromises()
  if (outcome === 'success') resolve({ ...bulk, status: 'CANCELED' })
  else reject(outcome === 'conflict' ? { isAxiosError: true, response: { status: 409 } } : new Error('offline'))
  await flushPromises()
  expect(wrapper.text()).toContain('New selection')
  expect(wrapper.text()).not.toContain('状态已变化')
  expect(wrapper.text()).not.toContain('操作结果未确认')
  expect(bulkOutreachApi.get).toHaveBeenCalledTimes(2)
})

it('does not overlap a slow progress poll with manual refresh', async () => {
  vi.useFakeTimers()
  const { wrapper } = await render('bulk', '?bulkRun=bulk-1')
  let finish!: (value: BulkOutreachRun) => void
  vi.mocked(bulkOutreachApi.get).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  await vi.advanceTimersByTimeAsync(3000)
  await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
  await vi.advanceTimersByTimeAsync(9000)
  expect(bulkOutreachApi.get).toHaveBeenCalledTimes(2)
  finish({ ...bulk, status: 'COMPLETED', allowedActions: [] }); await flushPromises()
})

it('rechecks send permission and current sender availability after review', async () => {
  const { wrapper, auth } = await render('bulk')
  await review(wrapper); await wrapper.get('#bulk-send').setValue(true); await wrapper.get('#bulk-risk').setValue(true)
  auth.user!.permissions = permissions.filter(p => p !== 'campaign:send')
  await wrapper.get('[data-testid="bulk-confirm"]').trigger('click')
  expect(bulkOutreachApi.create).not.toHaveBeenCalled()
  auth.user!.permissions = permissions
  await wrapper.setProps({ options: { ...choices, senders: [{ ...choices.senders[0]!, available: false }] } })
  await wrapper.get('[data-testid="bulk-confirm"]').trigger('click')
  expect(bulkOutreachApi.create).not.toHaveBeenCalled()
})

it('shows read errors without fabricating counters or mutation retries', async () => {
  vi.mocked(bulkOutreachApi.get).mockRejectedValue(new Error('offline'))
  const { wrapper } = await render('bulk', '?bulkRun=bulk-1')
  expect(wrapper.text()).toContain('进度暂时无法更新')
  expect(wrapper.find('[data-testid="bulk-smtpAccepted"]').exists()).toBe(false)
  expect(bulkOutreachApi.create).not.toHaveBeenCalled()
})

it('refuses a create when stable key persistence is unavailable', async () => {
  const { wrapper } = await render('bulk')
  await review(wrapper); await wrapper.get('#bulk-send').setValue(true); await wrapper.get('#bulk-risk').setValue(true)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Unavailable') })
  await wrapper.get('[data-testid="bulk-confirm"]').trigger('click'); await flushPromises()
  expect(bulkOutreachApi.create).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('会话存储不可用')
})

it.each(['A'.repeat(121), 'abs:Agent', 'Agent OR Finance'])('rejects a weekly direction outside the literal phrase contract: %s', async phrase => {
  const { wrapper } = await render('weekly')
  await wrapper.get('#weekly-directions').setValue(phrase)
  await review(wrapper, 'weekly')
  expect(wrapper.find('[data-testid="weekly-confirmation"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('研究方向')
  expect(weeklyOutreachApi.create).not.toHaveBeenCalled()
})

it('keeps the same-record pending read valid when its history entry is selected again', async () => {
  vi.useFakeTimers()
  vi.mocked(bulkOutreachApi.list).mockResolvedValue({ items: [bulk], page: 1, pageSize: 10, total: 1, totalPages: 1 })
  const { wrapper } = await render('bulk', '?bulkRun=bulk-1')
  let finish!: (value: BulkOutreachRun) => void
  vi.mocked(bulkOutreachApi.get).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  await vi.advanceTimersByTimeAsync(3000)
  await wrapper.get('[data-testid="bulk-history-bulk-1"]').trigger('click'); await flushPromises()
  expect(bulkOutreachApi.get).toHaveBeenCalledTimes(2)
  finish({ ...bulk, smtpAccepted: 91 }); await flushPromises()
  expect(wrapper.get('[data-testid="bulk-smtpAccepted"]').text()).toContain('91')
  vi.mocked(bulkOutreachApi.get).mockResolvedValue({ ...bulk, smtpAccepted: 92, status: 'COMPLETED', allowedActions: [] })
  await vi.advanceTimersByTimeAsync(3000); await flushPromises()
  expect(bulkOutreachApi.get).toHaveBeenCalledTimes(3)
  expect(wrapper.get('[data-testid="bulk-smtpAccepted"]').text()).toContain('92')
})

it('restores readback and polling after the same deep link is removed and re-added during a pending GET', async () => {
  vi.useFakeTimers()
  const { wrapper, router } = await render('bulk', '?bulkRun=bulk-1')
  let finish!: (value: BulkOutreachRun) => void
  vi.mocked(bulkOutreachApi.get).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  await vi.advanceTimersByTimeAsync(3000)
  await router.replace('/email/automation'); await flushPromises()
  expect(wrapper.find('[data-testid="bulk-smtpAccepted"]').exists()).toBe(false)
  await router.replace('/email/automation?bulkRun=bulk-1'); await flushPromises()
  expect(bulkOutreachApi.get).toHaveBeenCalledTimes(2)
  finish({ ...bulk, smtpAccepted: 91 }); await flushPromises()
  expect(wrapper.get('[data-testid="bulk-smtpAccepted"]').text()).toContain('91')
  vi.mocked(bulkOutreachApi.get).mockResolvedValue({ ...bulk, smtpAccepted: 92, status: 'COMPLETED', allowedActions: [] })
  await vi.advanceTimersByTimeAsync(3000); await flushPromises()
  expect(bulkOutreachApi.get).toHaveBeenCalledTimes(3)
  expect(wrapper.get('[data-testid="bulk-smtpAccepted"]').text()).toContain('92')
})
