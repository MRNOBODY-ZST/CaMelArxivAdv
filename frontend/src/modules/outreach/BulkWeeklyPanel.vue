<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsCheckbox from '@/components/design-skill/DsCheckbox.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { OutreachOptions } from './outreach.types'
import type { BulkOutreachCommand, BulkOutreachRun, BulkOutreachPage, WeeklyOutreachCommand, WeeklyOutreachSchedule, WeeklyOutreachPage } from './bulk-weekly.types'
import { bulkOutreachApi, weeklyOutreachApi } from './bulk-weekly.api'

type Command = BulkOutreachCommand | WeeklyOutreachCommand
type RecordView = BulkOutreachRun | WeeklyOutreachSchedule
type Action = 'cancel' | 'pause' | 'resume'
const props = defineProps<{ mode: 'bulk' | 'weekly'; options: OutreachOptions | null }>()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const isWeekly = props.mode === 'weekly'
const queryKey = isWeekly ? 'schedule' : 'bulkRun'
const maximum = isWeekly ? 5000 : 10000
const title = isWeekly ? '每周增量触达' : '首次批量触达'
const storageKey = `camel-${props.mode}-pending:${auth.user?.id ?? 'anonymous'}`
const form = reactive({ name: '', purpose: '', count: String(maximum), smtpAccountId: '', templateId: '', directions: 'Agent\nfinancial artificial intelligence\nmulti-agent\nlanguage model' })
const current = ref<RecordView | null>(null)
const history = ref<BulkOutreachPage | WeeklyOutreachPage | null>(null)
const pending = ref<Command | null>(null)
const review = ref<Command | null>(null)
const confirmationOpen = ref(false)
const sendConfirmed = ref(false)
const riskConfirmed = ref(false)
const saving = ref(false)
const loading = ref(false)
const loadingHistory = ref(false)
const error = ref('')
const progressError = ref('')
const historyError = ref('')
const actionTarget = ref<{ id: string; version: number; action: Action } | null>(null)
const acting = ref(false)
const bulkRun = computed(() => !isWeekly ? current.value as BulkOutreachRun | null : null)
const schedule = computed(() => isWeekly ? current.value as WeeklyOutreachSchedule | null : null)
const canCreate = computed(() => auth.hasEveryPermission(['paper:import', 'campaign:create', 'campaign:approve', 'campaign:send']))
const ready = computed(() => canCreate.value && !!props.options?.senders.some(s => s.available) && !!props.options?.templates.some(t => t.available))
const canPause = computed(() => auth.hasPermission('campaign:pause'))
const locked = computed(() => !canCreate.value || !props.options || saving.value || !!pending.value)
const senderOptions = computed(() => props.options?.senders.map(s => ({ value: s.id, label: `${s.name} · ${s.fromEmail}${s.available ? '' : `（${s.message ?? '不可用'}）`}`, disabled: !s.available })) ?? [])
const templateOptions = computed(() => props.options?.templates.map(t => ({ value: t.id, label: `${t.name} · v${t.currentVersion}${t.available ? '' : `（${t.message ?? '不可用'}）`}`, disabled: !t.available })) ?? [])
const reviewedSender = computed(() => props.options?.senders.find(s => s.id === review.value?.smtpAccountId))
const reviewedTemplate = computed(() => props.options?.templates.find(t => t.id === review.value?.templateId))
const terminal = new Set(['COMPLETED', 'FAILED', 'BLOCKED', 'CANCELED', 'CANCELLED'])
const counters = [
  { key: 'paperCount', label: '已冻结论文' }, { key: 'extractedCount', label: '提取完成论文' },
  { key: 'excludedCount', label: '排除论文（含安全拒绝）' }, { key: 'noContactCount', label: '无联系人论文' },
  { key: 'selectedCount', label: '已选择唯一收件人' }, { key: 'smtpAccepted', label: 'SMTP 已接受' },
  { key: 'bouncedCount', label: '退信' }, { key: 'failedCount', label: '失败' }, { key: 'unknownCount', label: '结果未知（不重放）' },
] as const
let disposed = false
let pollTimer: ReturnType<typeof globalThis.setTimeout> | undefined
let historyTimer: ReturnType<typeof globalThis.setTimeout> | undefined
let sequence = 0
let inflight: { id: string; sequence: number; promise: Promise<void> } | undefined

function dateTime(value: string | null): string {
  if (!value) return '暂无下一次执行时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间暂不可用'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}
function stopPolling(): void { if (pollTimer !== undefined) globalThis.clearTimeout(pollTimer); pollTimer = undefined }
function schedulePolling(): void {
  stopPolling()
  if (!disposed && !acting.value && current.value && (isWeekly || !terminal.has((current.value as BulkOutreachRun).status))) {
    pollTimer = globalThis.setTimeout(() => { if (current.value) void loadRecord(current.value.id, false) }, 3000)
  }
}
function loadRecord(id: string, showLoading = true): Promise<void> {
  if (inflight?.id === id) {
    // Re-selecting the same record adopts its pending read into this generation.
    inflight.sequence = sequence
    if (showLoading) loading.value = true
    return inflight.promise
  }
  stopPolling()
  const request = { id, sequence: ++sequence, promise: Promise.resolve() }
  if (showLoading) loading.value = true
  inflight = request
  request.promise = (async () => {
    try {
      const result = isWeekly ? await weeklyOutreachApi.get(id) : await bulkOutreachApi.get(id)
      if (disposed || request.sequence !== sequence) return
      current.value = result; progressError.value = ''
    } catch {
      if (disposed || request.sequence !== sequence) return
      progressError.value = '进度暂时无法更新；显示的是上次读取结果，不会重复创建或重试发送。'
    } finally {
      if (!disposed && request.sequence === sequence) { loading.value = false; schedulePolling() }
      if (inflight === request) inflight = undefined
    }
  })()
  return request.promise
}
async function loadHistory(page = history.value?.page ?? 1): Promise<void> {
  if (loadingHistory.value || disposed) return
  if (historyTimer !== undefined) globalThis.clearTimeout(historyTimer)
  loadingHistory.value = true
  try {
    const result = isWeekly ? await weeklyOutreachApi.list(page, 10) : await bulkOutreachApi.list(page, 10)
    if (!disposed) { history.value = result; historyError.value = '' }
  } catch { if (!disposed) historyError.value = '记录加载失败，请刷新；不会推测任务状态。' }
  finally {
    if (!disposed) {
      loadingHistory.value = false
      if (isWeekly || history.value?.items.some(item => !terminal.has((item as BulkOutreachRun).status))) {
        historyTimer = globalThis.setTimeout(() => { void loadHistory() }, 15000)
      }
    }
  }
}
async function selectRecord(record: RecordView): Promise<void> {
  ++sequence; stopPolling(); current.value = record; progressError.value = ''; loading.value = false
  await router.replace({ query: { ...route.query, [queryKey]: record.id } })
  await loadRecord(record.id, false)
}
function available(request: Command): boolean {
  return !!props.options?.senders.some(s => s.id === request.smtpAccountId && s.available)
    && !!props.options?.templates.some(t => t.id === request.templateId && t.available)
}
function reviewCreate(): void {
  if (!ready.value || saving.value) return
  error.value = ''
  if (pending.value) review.value = pending.value
  else {
    const count = Number(form.count)
    if (!Number.isInteger(count) || count < 1 || count > maximum) { error.value = `目标论文数必须是 1–${maximum} 的整数。`; return }
    if (!form.name.trim() || !form.purpose.trim()) { error.value = '请填写任务名称和用途。'; return }
    const directions = form.directions.split(/[\n,，]+/).map(s => s.trim()).filter(Boolean)
    const limit = isWeekly ? 8 : 12
    const phraseLength = isWeekly ? 120 : 160
    const invalidLiteral = isWeekly && directions.some(d => !/^[\p{L}\p{N}][\p{L}\p{N} .+/-]{0,119}$/u.test(d) || / (OR|AND|NOT) /.test(d))
    if (!directions.length || directions.length > limit || new Set(directions.map(d => d.toLowerCase())).size !== directions.length || directions.some(d => d.length > phraseLength) || invalidLiteral) {
      error.value = `请填写 1–${limit} 个不同研究方向，每个不超过 ${phraseLength} 字符，用换行分隔。周度方向仅含文字、数字、空格与 . + / -，不含查询运算符。`; return
    }
    const base = { name: form.name.trim(), purpose: form.purpose.trim(), smtpAccountId: form.smtpAccountId, templateId: form.templateId,
      acceptDeliverabilityRisk: true, confirmation: 'AUTO_OUTREACH_SEND' as const, idempotencyKey: globalThis.crypto.randomUUID() }
    review.value = isWeekly ? { ...base, maxPapers: count, directions } : {
      ...base, targetPapers: count, incremental: false,
      searchPlan: directions.map(phrase => ({ categoryIds: [], categoryMode: 'ANY' as const, titleKeywords: '', abstractKeywords: phrase,
        authorKeywords: '', sortBy: 'SUBMITTED_DATE' as const, sortOrder: 'DESCENDING' as const, page: 1, pageSize: 100 })),
    }
  }
  if (!review.value || !available(review.value)) { error.value = '请选择当前可用的发件邮箱和已发布模板。'; return }
  sendConfirmed.value = false; riskConfirmed.value = false; confirmationOpen.value = true
}
async function createRecord(): Promise<void> {
  if (saving.value || !ready.value || !sendConfirmed.value || !riskConfirmed.value || !review.value) return
  if (!available(review.value)) { error.value = '发件邮箱或模板已不可用，请刷新配置后确认。'; return }
  saving.value = true; error.value = ''
  try {
    if (!pending.value) {
      try { globalThis.sessionStorage.setItem(storageKey, JSON.stringify(review.value)) }
      catch { error.value = '会话存储不可用，无法安全保存重复请求标识；未创建任务。'; confirmationOpen.value = false; return }
      pending.value = review.value
    }
    const result = isWeekly ? await weeklyOutreachApi.create(pending.value as WeeklyOutreachCommand) : await bulkOutreachApi.create(pending.value as BulkOutreachCommand)
    pending.value = null
    try { globalThis.sessionStorage.removeItem(storageKey) } catch { /* An acknowledged request still has its stable key. */ }
    confirmationOpen.value = false
    if (!disposed) { await selectRecord(result); void loadHistory(1) }
  } catch (cause) {
    confirmationOpen.value = false
    if (axios.isAxiosError(cause) && cause.response?.status && cause.response.status < 500 && cause.response.status !== 408) {
      pending.value = null
      try { globalThis.sessionStorage.removeItem(storageKey) } catch { /* No new request is made. */ }
      error.value = cause.response.status === 409 ? '请求冲突；请刷新记录核对状态，未自动重试。' : '服务端拒绝创建，请核对权限、配置与检索条件。'
    } else error.value = '创建结果尚未确认。仅可再次确认同一请求，不能修改参数或自动重试发送。'
  } finally { saving.value = false }
}
function restorePending(): void {
  try {
    const stored = globalThis.sessionStorage.getItem(storageKey)
    if (!stored) return
    const request = JSON.parse(stored) as Command
    if (request.confirmation !== 'AUTO_OUTREACH_SEND' || request.acceptDeliverabilityRisk !== true
      || !/^[0-9a-f-]{36}$/i.test(request.idempotencyKey) || typeof request.name !== 'string' || typeof request.purpose !== 'string'
      || typeof request.smtpAccountId !== 'string' || typeof request.templateId !== 'string') return
    const count = isWeekly ? (request as WeeklyOutreachCommand).maxPapers : (request as BulkOutreachCommand).targetPapers
    const directions = isWeekly ? (request as WeeklyOutreachCommand).directions : (request as BulkOutreachCommand).searchPlan?.map(c => c.abstractKeywords)
    if (!Number.isInteger(count) || count < 1 || count > maximum || !Array.isArray(directions) || !directions.length || directions.some(d => typeof d !== 'string')) return
    pending.value = request
    Object.assign(form, { name: request.name, purpose: request.purpose, count: String(count), smtpAccountId: request.smtpAccountId, templateId: request.templateId, directions: directions.join('\n') })
  } catch { /* Corrupt storage never triggers a mutation. */ }
}
function allowed(action: Action): boolean { return canPause.value && !!current.value?.allowedActions.some(a => a.toLowerCase() === action) }
function reviewAction(action: Action): void {
  if (!acting.value && current.value && allowed(action)) actionTarget.value = { id: current.value.id, version: current.value.lockVersion, action }
}
async function performAction(): Promise<void> {
  const target = actionTarget.value
  if (!target || acting.value || !canPause.value || current.value?.id !== target.id || !allowed(target.action)) return
  acting.value = true; progressError.value = ''; stopPolling()
  try {
    // Settle a read already in flight before applying a state transition.
    if (inflight?.id === target.id) await inflight.promise
    if (disposed || current.value?.id !== target.id || !canPause.value || !allowed(target.action)) return
    const result = target.action === 'cancel' ? await bulkOutreachApi.cancel(target.id, target.version)
      : await weeklyOutreachApi[target.action](target.id, target.version)
    if (!disposed && current.value?.id === target.id) current.value = result
    if (!disposed) void loadHistory()
  } catch (cause) {
    if (disposed || current.value?.id !== target.id) return
    if (axios.isAxiosError(cause) && cause.response?.status === 409) {
      await loadRecord(target.id, false)
      if (!disposed && current.value?.id === target.id) progressError.value = '状态已变化，已刷新；请重新确认操作，不会自动重试。'
    } else progressError.value = '操作结果未确认，请刷新状态后核对；不会自动重试。'
  } finally { acting.value = false; actionTarget.value = null; schedulePolling() }
}
watch(() => props.options, options => {
  if (pending.value || !options) return
  if (!form.smtpAccountId) form.smtpAccountId = options.senders.find(s => s.available)?.id ?? ''
  if (!form.templateId) form.templateId = options.templates.find(t => t.available)?.id ?? ''
}, { immediate: true })
watch(() => route.query[queryKey], value => {
  const id = typeof value === 'string' ? value : null
  if (id && id !== current.value?.id) { current.value = null; void loadRecord(id) }
  else if (!id) { ++sequence; current.value = null; loading.value = false; stopPolling() }
}, { immediate: true })
onMounted(() => { restorePending(); void loadHistory(1) })
onUnmounted(() => { disposed = true; ++sequence; stopPolling(); if (historyTimer !== undefined) globalThis.clearTimeout(historyTimer) })
</script>

<template>
  <section
    :data-testid="`${mode}-panel`"
    :aria-labelledby="`${mode}-title`"
    class="min-w-0 space-y-4"
  >
    <header class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2
          :id="`${mode}-title`"
          class="text-xl font-semibold tracking-tight text-slate-950"
        >
          {{ title }}
        </h2>
        <p class="mt-1 text-sm/6 text-slate-600">
          {{ isWeekly ? '周一 09:00 · Asia/Shanghai；每次最多 5,000 篇新论文，无固定邮件数量上限。' : '最多 10,000 篇唯一论文，拆分为有独立审核门禁的子活动；论文数量不是邮件数量。' }}
        </p>
      </div>
      <DsBadge tone="info">
        {{ isWeekly ? '服务端定时执行' : '非增量首次运行' }}
      </DsBadge>
    </header>
    <div class="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <DsCard>
        <form
          :id="`${mode}-form`"
          class="space-y-5"
          @submit.prevent="reviewCreate"
        >
          <DsAlert
            v-if="error"
            tone="danger"
          >
            {{ error }}
          </DsAlert>
          <DsAlert
            v-if="pending"
            tone="warning"
          >
            创建结果尚未确认。原请求已保存在此浏览器会话，重新确认只提交同一请求。
          </DsAlert>
          <DsAlert
            v-if="!canCreate"
            tone="info"
          >
            创建需要论文导入、活动创建、审批与发送全部权限。已创建记录仍可查看。
          </DsAlert>
          <fieldset
            :disabled="locked"
            class="space-y-5 disabled:opacity-70"
          >
            <legend class="sr-only">
              {{ title }}配置
            </legend>
            <div class="grid gap-4 sm:grid-cols-2">
              <DsInput
                :id="`${mode}-name`"
                v-model="form.name"
                label="任务名称"
              />
              <DsInput
                :id="`${mode}-purpose`"
                v-model="form.purpose"
                label="用途"
              />
              <DsInput
                :id="`${mode}-count`"
                v-model="form.count"
                type="number"
                label="目标论文数"
                :description="`1–${maximum.toLocaleString('en-US')} 篇；不是收件人数或邮件上限`"
              />
            </div>
            <div class="grid gap-4 sm:grid-cols-2">
              <DsSelect
                :id="`${mode}-sender`"
                v-model="form.smtpAccountId"
                label="发件邮箱"
                :options="senderOptions"
              />
              <DsSelect
                :id="`${mode}-template`"
                v-model="form.templateId"
                label="已发布模板"
                :options="templateOptions"
              />
            </div>
            <div>
              <label
                :for="`${mode}-directions`"
                class="block text-sm/6 font-medium text-slate-900"
              >研究方向（每行一个短语）</label>
              <p
                :id="`${mode}-directions-help`"
                class="mt-1 text-xs/5 text-slate-500"
              >
                分别搜索字面摘要短语，不作为原始查询语法执行。{{ isWeekly ? '最多 8 个方向，每个 120 字符；周度日期窗口由服务端生成，缺少趋势证据时如实使用配置方向。' : '初次搜索不限制提交日期；最多 12 个不同方向。' }}
              </p>
              <textarea
                :id="`${mode}-directions`"
                v-model="form.directions"
                :aria-describedby="`${mode}-directions-help`"
                rows="4"
                class="mt-2 block w-full min-w-0 rounded-md px-3 py-2 text-sm/6 text-slate-900 shadow-xs outline-1 -outline-offset-1 outline-slate-300 focus:outline-2 focus:outline-brand-500"
              />
            </div>
          </fieldset>
          <p
            v-if="options && !ready && canCreate"
            class="text-sm/6 text-amber-700"
          >
            没有可用发件邮箱或模板。请先启用并测试邮箱、发布通过检查的模板。
          </p>
          <p
            v-if="!options"
            class="text-sm/6 text-slate-500"
          >
            发件邮箱和模板尚未加载；未开放创建。
          </p>
          <p class="rounded-md bg-slate-50 p-3 text-xs/6 text-slate-600">
            关闭页面不影响服务端执行。联系人去重、退订、抑制与安全门禁仍保留；结果未知的发送不会重放。{{ isWeekly ? '暂停计划只停止新的定时触发，已有运行需单独取消。' : '检索或来源失败会显示为排除，目标短缺会如实展示。' }}
          </p>
          <DsButton
            type="submit"
            :data-testid="`${mode}-review`"
            :disabled="!ready || saving"
          >
            {{ pending ? '重新确认同一请求' : '检查并确认' }}
          </DsButton>
        </form>
      </DsCard>
      <div
        class="min-w-0 space-y-4"
        aria-live="polite"
      >
        <DsCard>
          <div class="flex items-center justify-between gap-3">
            <h3 class="text-base font-semibold text-slate-900">
              {{ isWeekly ? '计划状态' : '运行进度' }}
            </h3>
            <DsButton
              v-if="current"
              variant="secondary"
              size="sm"
              :disabled="loading || acting"
              @click="loadRecord(current.id)"
            >
              刷新状态
            </DsButton>
            <DsButton
              v-else-if="typeof route.query[queryKey] === 'string'"
              variant="secondary"
              size="sm"
              :disabled="loading"
              @click="loadRecord(route.query[queryKey] as string)"
            >
              重试读取
            </DsButton>
          </div>
          <DsAlert
            v-if="progressError"
            tone="warning"
            class="mt-4"
          >
            {{ progressError }}
          </DsAlert>
          <p
            v-if="loading && !current"
            class="mt-4 text-sm text-slate-500"
          >
            正在读取服务端状态…
          </p>
          <p
            v-else-if="!current"
            class="mt-4 text-sm/6 text-slate-500"
          >
            创建或选择历史记录后显示真实进度；此处没有估算数据。
          </p>
          <template v-if="current">
            <div class="mt-4 flex flex-wrap items-center gap-2">
              <h4 class="break-all text-sm font-semibold text-slate-900">
                {{ current.name }}
              </h4>
              <DsBadge tone="info">
                {{ schedule ? (schedule.enabled ? '已启用' : '已暂停') : bulkRun?.status }}
              </DsBadge>
              <DsBadge
                v-if="bulkRun"
                tone="neutral"
              >
                {{ bulkRun.incremental ? '周度增量运行' : '首次非增量运行' }}
              </DsBadge>
            </div>
            <p
              v-if="current.message"
              class="mt-3 break-words rounded-md bg-amber-50 p-3 text-sm/6 text-amber-900"
            >
              {{ current.message }}
            </p>
            <template v-if="bulkRun">
              <p class="mt-3 text-sm/6 text-slate-600">
                目标 {{ bulkRun.targetPapers.toLocaleString('en-US') }} 篇 · 阶段 {{ bulkRun.stage }}
              </p>
              <p
                v-if="terminal.has(bulkRun.status) && bulkRun.paperCount < bulkRun.targetPapers"
                class="text-sm/6 text-amber-700"
              >
                论文短缺 {{ (bulkRun.targetPapers - bulkRun.paperCount).toLocaleString('en-US') }} 篇；未将目标当作实际数量。
              </p>
              <dl class="mt-4 grid grid-cols-2 gap-x-4 gap-y-4 sm:grid-cols-3">
                <div
                  v-for="counter in counters"
                  :key="counter.key"
                  :data-testid="`${mode}-${counter.key}`"
                  class="min-w-0"
                >
                  <dt class="text-xs/5 text-slate-500">
                    {{ counter.label }}
                  </dt>
                  <dd class="mt-1 text-xl font-semibold tabular-nums text-slate-900">
                    {{ bulkRun[counter.key].toLocaleString('en-US') }}
                  </dd>
                </div>
              </dl>
              <p class="mt-4 text-xs/6 text-slate-500">
                SMTP 接受不代表送达。排除论文（例如安全拒绝、提取失败）与成功提取但无联系人分开统计；收件人数与目标论文数不是同一指标。
              </p>
              <h4 class="mt-5 text-sm font-semibold text-slate-900">
                子活动（{{ bulkRun.children.length }}）
              </h4>
              <ul class="mt-2 divide-y divide-slate-100">
                <li
                  v-for="child in bulkRun.children"
                  :key="child.id"
                  class="py-3 text-xs/6 text-slate-600"
                >
                  <RouterLink
                    :data-testid="`child-${child.id}`"
                    :to="`/email/campaigns/${encodeURIComponent(child.id)}`"
                    class="break-all font-medium text-brand-700 hover:underline"
                  >
                    {{ child.id }}
                  </RouterLink>
                  <p>{{ child.status }} · 选择 {{ child.selectedCount }} · SMTP 接受 {{ child.smtpAccepted }} · 退信 {{ child.bouncedCount }} · 失败 {{ child.failedCount }} · 未知 {{ child.unknownCount }}</p>
                </li>
              </ul>
              <p
                v-if="!bulkRun.children.length"
                class="mt-2 text-xs/6 text-slate-500"
              >
                尚未创建子活动。
              </p>
            </template>
            <dl
              v-if="schedule"
              class="mt-4 space-y-3 text-sm/6"
            >
              <div>
                <dt class="text-slate-500">
                  固定计划
                </dt><dd class="font-medium text-slate-900">
                  周一 09:00 · {{ schedule.timezone }}
                </dd>
              </div>
              <div>
                <dt class="text-slate-500">
                  下次执行（Asia/Shanghai）
                </dt><dd class="font-medium text-slate-900">
                  {{ dateTime(schedule.nextRunAt) }}
                </dd>
              </div>
              <div>
                <dt class="text-slate-500">
                  每次论文上限
                </dt><dd class="text-slate-900">
                  {{ schedule.maxPapers.toLocaleString('en-US') }} 篇新论文；无固定邮件数量上限
                </dd>
              </div>
              <div>
                <dt class="text-slate-500">
                  配置方向
                </dt><dd class="break-words text-slate-900">
                  {{ schedule.directions.join(' / ') }}
                </dd>
              </div>
              <div v-if="schedule.lastRunId">
                <dt class="text-slate-500">
                  最近运行
                </dt><dd>
                  <RouterLink
                    data-testid="weekly-last-run"
                    :to="{ path: '/email/automation', query: { bulkRun: schedule.lastRunId } }"
                    class="break-all text-brand-700 hover:underline"
                  >
                    {{ schedule.lastRunId }}
                  </RouterLink>
                </dd>
              </div>
            </dl>
            <p class="mt-4 text-xs/6 text-slate-500">
              最近读取更新：{{ dateTime(current.updatedAt) }} · 历史风险{{ current.acceptDeliverabilityRisk ? '已显式接受（不等于历史问题已解决）' : '未接受' }}
            </p>
            <div class="mt-4 flex flex-wrap gap-2">
              <DsButton
                v-if="allowed('cancel')"
                :data-testid="`${mode}-cancel`"
                variant="danger"
                size="sm"
                :disabled="acting"
                @click="reviewAction('cancel')"
              >
                取消运行
              </DsButton>
              <DsButton
                v-if="allowed('pause')"
                :data-testid="`${mode}-pause`"
                variant="secondary"
                size="sm"
                :disabled="acting"
                @click="reviewAction('pause')"
              >
                暂停计划
              </DsButton>
              <DsButton
                v-if="allowed('resume')"
                :data-testid="`${mode}-resume`"
                variant="secondary"
                size="sm"
                :disabled="acting"
                @click="reviewAction('resume')"
              >
                恢复计划
              </DsButton>
            </div>
          </template>
        </DsCard>
        <DsCard>
          <div class="flex items-center justify-between gap-3">
            <h3 class="text-sm font-semibold text-slate-900">
              {{ isWeekly ? '周度计划记录' : '批量运行记录' }}
            </h3><DsButton
              variant="secondary"
              size="sm"
              :disabled="loadingHistory"
              @click="loadHistory()"
            >
              刷新记录
            </DsButton>
          </div>
          <DsAlert
            v-if="historyError"
            tone="warning"
            class="mt-3"
          >
            {{ historyError }}
          </DsAlert>
          <p
            v-if="loadingHistory && !history"
            class="mt-3 text-sm text-slate-500"
          >
            正在加载记录…
          </p>
          <ul
            v-else-if="history?.items.length"
            class="mt-3 divide-y divide-slate-100"
          >
            <li
              v-for="item in history.items"
              :key="item.id"
              class="py-3"
            >
              <button
                type="button"
                :data-testid="`${mode}-history-${item.id}`"
                class="w-full break-words text-left text-sm/6 font-medium text-brand-700 hover:underline focus-visible:outline-2 focus-visible:outline-brand-500"
                @click="selectRecord(item)"
              >
                {{ item.name }}
              </button>
              <p class="text-xs/6 text-slate-500">
                {{ isWeekly ? ((item as WeeklyOutreachSchedule).enabled ? '已启用' : '已暂停') : (item as BulkOutreachRun).status }} · {{ dateTime(item.updatedAt) }}
              </p>
            </li>
          </ul>
          <p
            v-else-if="!historyError"
            class="mt-3 text-sm/6 text-slate-500"
          >
            暂无{{ isWeekly ? '周度计划' : '批量运行' }}记录。
          </p>
          <DsPagination
            v-if="history && history.totalPages > 1"
            :page="history.page"
            :total-pages="history.totalPages"
            :total="history.total"
            :page-size="history.pageSize"
            @change="loadHistory"
          />
        </DsCard>
      </div>
    </div>
    <DsModal
      :open="confirmationOpen"
      :title="`确认${title}`"
      :description="isWeekly ? '保存后由服务端每周自动执行，关闭浏览器不影响计划。' : '确认后会真实审批与发送；不是演练。'"
      @close="!saving && (confirmationOpen = false)"
    >
      <div
        :data-testid="`${mode}-confirmation`"
        class="space-y-4 text-sm/6 text-slate-700"
      >
        <p>{{ review?.name }} · {{ isWeekly ? (review as WeeklyOutreachCommand)?.maxPapers : (review as BulkOutreachCommand)?.targetPapers }} 篇论文；邮件数量取决于合格、去重后的联系人。</p>
        <p class="break-all">
          发件：{{ reviewedSender?.fromEmail }} · Reply-To：{{ reviewedSender?.replyToEmail ?? '沿用账户配置' }}
        </p>
        <p>模板：{{ reviewedTemplate?.name }} · v{{ reviewedTemplate?.currentVersion }}</p>
        <p v-if="isWeekly">
          周一 09:00 · Asia/Shanghai，最多 5,000 篇新论文，无固定邮件数量上限。
        </p>
        <DsAlert tone="warning">
          {{ options?.productionHoldReason ? `${options.productionHoldReason}。` : '' }}历史投递风险接受将被审计，不代表旧退信或诊断已经解决。仅适用于新批量/周度流程的软警告；所有硬性内容、退订和安全门禁仍然有效。
        </DsAlert>
        <DsCheckbox
          :id="`${mode}-send`"
          v-model="sendConfirmed"
          label="我确认自动审批并真实发送，不是测试或演练"
          :disabled="saving"
        />
        <DsCheckbox
          :id="`${mode}-risk`"
          v-model="riskConfirmed"
          label="我明确接受历史投递风险，并同意记录审计证据"
          :disabled="saving"
        />
      </div>
      <template #actions>
        <DsButton
          variant="secondary"
          :disabled="saving"
          @click="confirmationOpen = false"
        >
          返回检查
        </DsButton><DsButton
          :data-testid="`${mode}-confirm`"
          :disabled="!sendConfirmed || !riskConfirmed || !ready || saving"
          @click="createRecord"
        >
          {{ saving ? '正在提交…' : '确认并创建' }}
        </DsButton>
      </template>
    </DsModal>
    <DsModal
      :open="!!actionTarget"
      title="确认状态操作"
      description="操作使用当前记录版本；状态冲突将要求重新确认。"
      @close="!acting && (actionTarget = null)"
    >
      <p class="text-sm/6 text-slate-600">
        {{ actionTarget?.action === 'cancel' ? '停止后续处理与发送；已经发送的邮件无法撤回，未知结果不会重放。' : actionTarget?.action === 'pause' ? '暂停新的周度触发，已经创建的运行不会自动取消。' : '恢复每周固定时间触发；服务端仍检查并发运行与安全门禁。' }}
      </p>
      <template #actions>
        <DsButton
          variant="secondary"
          :disabled="acting"
          @click="actionTarget = null"
        >
          返回
        </DsButton><DsButton
          :data-testid="`${mode}-action-confirm`"
          :disabled="acting || !canPause"
          @click="performAction"
        >
          确认{{ actionTarget?.action === 'cancel' ? '取消' : actionTarget?.action === 'pause' ? '暂停' : '恢复' }}
        </DsButton>
      </template>
    </DsModal>
  </section>
</template>
