<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowPathIcon, PaperAirplaneIcon } from '@heroicons/vue/24/outline'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsCheckbox from '@/components/design-skill/DsCheckbox.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { SearchCriteriaRequest } from '@/modules/arxiv/arxiv.types'
import { outreachApi } from './outreach.api'
import type { OutreachCreateRequest, OutreachOptions, OutreachRun, OutreachRunPage, OutreachTargetMode } from './outreach.types'
import BulkWeeklyPanel from './BulkWeeklyPanel.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const options = ref<OutreachOptions | null>(null)
const history = ref<OutreachRunPage | null>(null)
const currentRun = ref<OutreachRun | null>(null)
const loadingOptions = ref(true)
const loadingHistory = ref(true)
const loadingRun = ref(false)
const saving = ref(false)
const canceling = ref(false)
const error = ref('')
const historyError = ref('')
const progressError = ref('')
const confirmationOpen = ref(false)
const sendConfirmed = ref(false)
const cancelTarget = ref<OutreachRun | null>(null)
const reviewRequest = ref<Omit<OutreachCreateRequest, 'idempotencyKey'> | null>(null)
const pendingRequest = ref<OutreachCreateRequest | null>(null)
const form = reactive({
  name: '', purpose: '', targetMode: 'EMAILS', targetCount: '100', smtpAccountId: '', templateId: '', categories: '',
  categoryMode: 'ANY', titleKeywords: '', abstractKeywords: '', authorKeywords: '',
  submittedFrom: '', submittedTo: '', updatedFrom: '', updatedTo: '',
  hasDoi: '', hasJournalReference: '', sourceAvailable: '', sortBy: 'RELEVANCE', sortOrder: 'DESCENDING',
})

const terminalStatuses = new Set(['BLOCKED', 'FAILED', 'CANCELED', 'COMPLETED'])
const createPermissions = ['paper:import', 'campaign:create', 'campaign:approve', 'campaign:send'] as const
const canCreate = computed(() => auth.hasEveryPermission(createPermissions))
const canPause = computed(() => auth.hasPermission('campaign:pause'))
const creationReady = computed(() => canCreate.value && !!options.value
  && options.value.senders.some((item) => item.available) && options.value.templates.some((item) => item.available))
const formLocked = computed(() => !canCreate.value || saving.value || !!pendingRequest.value)
const selectedSender = computed(() => options.value?.senders.find((item) => item.id === (reviewRequest.value?.smtpAccountId ?? form.smtpAccountId)))
const selectedTemplate = computed(() => options.value?.templates.find((item) => item.id === (reviewRequest.value?.templateId ?? form.templateId)))
const senderOptions = computed(() => options.value?.senders.map((item) => ({
  value: item.id, label: `${item.name} · ${item.fromEmail}${!item.available ? `（${item.message ?? '当前不可用'}）` : ''}`, disabled: !item.available,
})) ?? [])
const templateOptions = computed(() => options.value?.templates.map((item) => ({
  value: item.id, label: `${item.name} · v${item.currentVersion}${!item.available ? `（${item.message ?? '当前不可用'}）` : ''}`, disabled: !item.available,
})) ?? [])
const canCancel = computed(() => canPause.value && !!currentRun.value && !isTerminal(currentRun.value)
  && currentRun.value.allowedActions.some((action) => action.toUpperCase() === 'CANCEL'))
const storageKey = `camel-outreach-pending:${auth.user?.id ?? 'anonymous'}`
const triStateOptions = [{ value: '', label: '不限' }, { value: 'true', label: '是' }, { value: 'false', label: '否' }]
const countFields = [
  { key: 'paperCount', label: '论文', unit: '篇' },
  { key: 'extractedCount', label: '已提取', unit: '篇' },
  { key: 'eligibleCount', label: '符合条件', unit: '地址' },
  { key: 'selectedCount', label: '冻结收件人', unit: '地址' },
  { key: 'smtpAccepted', label: 'SMTP 已接受', unit: '封' },
  { key: 'failedCount', label: '失败', unit: '封' },
  { key: 'unknownCount', label: '结果未知', unit: '封' },
] as const
let pollTimer: ReturnType<typeof globalThis.setTimeout> | undefined
let loadSequence = 0
let historyLoadSequence = 0
let disposed = false

function isTerminal(run: OutreachRun): boolean { return terminalStatuses.has(run.status) }

function statusLabel(status: string): string {
  return ({ PENDING: '等待开始', QUEUED: '排队中', ACTIVE: '运行中', RUNNING: '运行中', SENDING: '发送中', BLOCKED: '已阻止', FAILED: '失败', CANCELED: '已取消', COMPLETED: '已完成' } as Record<string, string>)[status] ?? status
}

function stageLabel(stage: string): string {
  return ({ IMPORT: '搜索与导入', WAIT_IMPORT: '等待导入', EXTRACT: '来源提取', WAIT_EXTRACTION: '等待提取', PREPARE: '筛选与内容准备', REVIEW: '机器预检', APPROVE: '自动审批', START: '启动发送', SENDING: '邮件投递', DONE: '流程结束' } as Record<string, string>)[stage] ?? stage
}

function statusTone(status: string): 'neutral' | 'positive' | 'warning' | 'danger' | 'info' {
  if (status === 'COMPLETED') return 'positive'
  if (status === 'BLOCKED') return 'warning'
  if (status === 'FAILED') return 'danger'
  return status === 'CANCELED' ? 'neutral' : 'info'
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
}

function stopPolling(): void {
  if (pollTimer !== undefined) globalThis.clearTimeout(pollTimer)
  pollTimer = undefined
}

function schedulePolling(): void {
  stopPolling()
  if (!disposed && currentRun.value && !isTerminal(currentRun.value)) {
    pollTimer = globalThis.setTimeout(() => { void loadRun(currentRun.value!.id, false) }, 3000)
  }
}

async function loadRun(id: string, showLoading = true): Promise<void> {
  stopPolling()
  const sequence = ++loadSequence
  if (showLoading) loadingRun.value = true
  try {
    const run = await outreachApi.get(id)
    if (disposed || sequence !== loadSequence) return
    currentRun.value = run
    progressError.value = ''
  } catch {
    if (disposed || sequence !== loadSequence) return
    progressError.value = '任务进度暂时无法更新；不会重复创建或自动重试发送。请刷新进度确认状态。'
  } finally {
    if (!disposed && sequence === loadSequence) {
      loadingRun.value = false
      schedulePolling()
    }
  }
}

async function loadHistory(page = 1): Promise<void> {
  const sequence = ++historyLoadSequence
  loadingHistory.value = true
  historyError.value = ''
  try {
    const result = await outreachApi.list(page, 10)
    if (!disposed && sequence === historyLoadSequence) history.value = result
  } catch {
    if (!disposed && sequence === historyLoadSequence) historyError.value = '历史任务加载失败，请稍后刷新。'
  } finally {
    if (!disposed && sequence === historyLoadSequence) loadingHistory.value = false
  }
}

async function selectRun(run: OutreachRun, refresh = false): Promise<void> {
  ++loadSequence
  loadingRun.value = false
  currentRun.value = run
  progressError.value = ''
  schedulePolling()
  await router.replace({ query: { ...route.query, run: run.id } })
  if (refresh) await loadRun(run.id)
}

function criteriaRequest(): SearchCriteriaRequest {
  const result: SearchCriteriaRequest = {
    categoryIds: [...new Set(form.categories.split(/[，,\s]+/).filter(Boolean))],
    categoryMode: form.categoryMode as SearchCriteriaRequest['categoryMode'],
    titleKeywords: form.titleKeywords.trim(), abstractKeywords: form.abstractKeywords.trim(), authorKeywords: form.authorKeywords.trim(),
    sortBy: form.sortBy as SearchCriteriaRequest['sortBy'], sortOrder: form.sortOrder as SearchCriteriaRequest['sortOrder'], page: 1, pageSize: 20,
  }
  for (const field of ['submittedFrom', 'submittedTo', 'updatedFrom', 'updatedTo'] as const) {
    if (form[field]) result[field] = form[field]
  }
  for (const field of ['hasDoi', 'hasJournalReference', 'sourceAvailable'] as const) {
    if (form[field]) result[field] = form[field] === 'true'
  }
  return result
}

function reviewCreate(): void {
  if (saving.value || !creationReady.value) return
  error.value = ''
  if (pendingRequest.value) {
    reviewRequest.value = pendingRequest.value
  } else {
    const count = Number(form.targetCount)
    const maximum = form.targetMode === 'EMAILS' ? options.value!.limits.maxEmails : options.value!.limits.maxPapers
    if (!Number.isInteger(count) || count < 1 || count > maximum) {
      error.value = `目标数量必须是 1–${maximum} 的整数。`
      return
    }
    if (!form.name.trim() || !form.purpose.trim()) { error.value = '请填写任务名称和用途。'; return }
    if (!options.value!.senders.some((item) => item.id === form.smtpAccountId && item.available)
        || !options.value!.templates.some((item) => item.id === form.templateId && item.available)) {
      error.value = '请选择可用的发件邮箱和模板。'
      return
    }
    const criteria = criteriaRequest()
    if (!criteria.categoryIds.length && !criteria.titleKeywords && !criteria.abstractKeywords && !criteria.authorKeywords
        && !criteria.submittedFrom && !criteria.submittedTo) {
      error.value = '请至少设置一个论文分类、关键词或提交日期检索条件。'
      return
    }
    for (const [from, to] of [[form.submittedFrom, form.submittedTo], [form.updatedFrom, form.updatedTo]]) {
      if (from && to && from > to) { error.value = '日期范围的开始日期不能晚于结束日期。'; return }
    }
    reviewRequest.value = {
      name: form.name.trim(), purpose: form.purpose.trim(), targetMode: form.targetMode as OutreachTargetMode,
      targetCount: count, smtpAccountId: form.smtpAccountId, templateId: form.templateId,
      criteria, confirmation: 'AUTO_OUTREACH_SEND',
    }
  }
  sendConfirmed.value = false
  confirmationOpen.value = true
}

function savePending(request: OutreachCreateRequest): boolean {
  try { globalThis.sessionStorage.setItem(storageKey, JSON.stringify(request)); return true }
  catch { return false }
}

function clearPending(): void {
  pendingRequest.value = null
  try { globalThis.sessionStorage.removeItem(storageKey) } catch { /* Storage may be disabled. */ }
}

function restorePending(): void {
  try {
    const stored = globalThis.sessionStorage.getItem(storageKey)
    if (!stored) return
    const request = JSON.parse(stored) as OutreachCreateRequest
    if (request.confirmation !== 'AUTO_OUTREACH_SEND' || !/^[0-9a-f-]{36}$/i.test(request.idempotencyKey)
        || !['EMAILS', 'PAPERS'].includes(request.targetMode) || !Number.isInteger(request.targetCount)
        || typeof request.name !== 'string' || typeof request.purpose !== 'string'
        || !Array.isArray(request.criteria?.categoryIds)) return
    pendingRequest.value = request
    Object.assign(form, {
      name: request.name, purpose: request.purpose, targetMode: request.targetMode, targetCount: String(request.targetCount),
      smtpAccountId: request.smtpAccountId, templateId: request.templateId, categories: request.criteria.categoryIds.join(', '),
      ...request.criteria,
      hasDoi: request.criteria.hasDoi === undefined ? '' : String(request.criteria.hasDoi),
      hasJournalReference: request.criteria.hasJournalReference === undefined ? '' : String(request.criteria.hasJournalReference),
      sourceAvailable: request.criteria.sourceAvailable === undefined ? '' : String(request.criteria.sourceAvailable),
    })
  } catch { /* Invalid or unavailable session storage must not initiate a request. */ }
}

async function createRun(): Promise<void> {
  if (saving.value || !creationReady.value || !sendConfirmed.value || !reviewRequest.value) return
  saving.value = true
  error.value = ''
  try {
    if (!pendingRequest.value) {
      const request = { ...reviewRequest.value, idempotencyKey: globalThis.crypto.randomUUID() }
      if (!savePending(request)) {
        error.value = '浏览器会话存储不可用，无法安全保存重复请求标识。未创建任务，请启用会话存储后再确认。'
        confirmationOpen.value = false
        return
      }
      pendingRequest.value = request
    }
    const run = await outreachApi.create(pendingRequest.value)
    clearPending()
    confirmationOpen.value = false
    await selectRun(run)
    void loadHistory()
  } catch (cause) {
    const status = axios.isAxiosError(cause) ? cause.response?.status : undefined
    if (status && status >= 400 && status < 500 && ![408, 409, 429].includes(status)) {
      clearPending()
      error.value = '请求未被接受，请检查所选配置和权限后重新确认。'
    } else {
      error.value = '创建结果尚未确认。请先查看历史任务，或重新确认原请求；将使用同一个请求标识，不会另建一批。'
    }
    confirmationOpen.value = false
  } finally { saving.value = false }
}

async function cancelRun(): Promise<void> {
  if (canceling.value || !canPause.value || !cancelTarget.value) return
  const target = cancelTarget.value
  canceling.value = true
  progressError.value = ''
  try {
    const updated = await outreachApi.cancel(target.id, target.lockVersion)
    if (disposed) return
    if (route.query.run === target.id && currentRun.value?.id === target.id) {
      ++loadSequence
      loadingRun.value = false
      currentRun.value = updated
      schedulePolling()
    }
    void loadHistory(history.value?.page ?? 1)
  } catch (cause) {
    if (disposed || route.query.run !== target.id || currentRun.value?.id !== target.id) return
    await loadRun(target.id, false)
    if (disposed || route.query.run !== target.id || currentRun.value?.id !== target.id) return
    progressError.value = axios.isAxiosError(cause) && cause.response?.status === 409
      ? '状态已变化，已刷新任务。请查看最新状态后重新确认取消。'
      : '取消结果尚未确认，已尝试刷新任务。不会自动重试取消；已经发送的邮件无法撤回。'
  } finally {
    if (!disposed) { cancelTarget.value = null; canceling.value = false }
  }
}

watch(() => route.query.run, (value) => {
  const id = typeof value === 'string' ? value : undefined
  if (id && currentRun.value?.id !== id) {
    currentRun.value = null
    void loadRun(id)
  } else if (!id) {
    ++loadSequence
    currentRun.value = null
    loadingRun.value = false
    stopPolling()
  }
}, { immediate: true })

onMounted(async () => {
  restorePending()
  void loadHistory()
  try {
    options.value = await outreachApi.options()
    if (!pendingRequest.value) {
      form.smtpAccountId = options.value.senders.find((item) => item.available)?.id ?? ''
      form.templateId = options.value.templates.find((item) => item.available)?.id ?? ''
    }
  } catch { error.value = '发件邮箱和模板配置加载失败；创建任务已禁用。' }
  finally { loadingOptions.value = false }
})

onUnmounted(() => { disposed = true; ++loadSequence; stopPolling() })
</script>

<template>
  <section
    data-testid="outreach-view"
    data-design-skill="stacked-form"
    aria-labelledby="outreach-title"
    class="space-y-6"
  >
    <header>
      <h1
        id="outreach-title"
        class="text-2xl font-semibold tracking-tight text-slate-950"
      >
        一键触达
      </h1>
      <p class="mt-2 max-w-3xl text-sm/6 text-slate-600">
        选择论文范围、发件邮箱和模板，服务端将持续执行搜索、来源提取、机器筛选、模板渲染、审批与发送。关闭页面不影响任务。
      </p>
    </header>
    <BulkWeeklyPanel
      mode="bulk"
      :options="options"
    />
    <BulkWeeklyPanel
      mode="weekly"
      :options="options"
    />
    <div class="border-t border-slate-200 pt-6">
      <h2 class="text-xl font-semibold text-slate-950">
        小规模触达（原流程）
      </h2>
      <p class="mt-1 text-sm/6 text-slate-500">
        以下保留原有数量限制与历史任务。历史风险接受不会改变此流程的生产限制。
      </p>
    </div>
    <DsAlert
      v-if="!canCreate"
      tone="info"
    >
      创建任务需要论文导入、活动创建、审批与发送的全部权限；现有任务仍可查看。
    </DsAlert>
    <DsAlert
      v-if="!canPause"
      tone="info"
    >
      取消任务需要活动暂停权限；创建权限与取消权限分别控制。
    </DsAlert>
    <DsAlert
      v-if="options?.productionHoldReason"
      tone="warning"
    >
      {{ options.productionHoldReason }}。仍可创建任务并执行搜索与准备；服务端会在发送启动前阻止，生产限制未解除前不会自动发送。
    </DsAlert>
    <DsAlert
      v-if="error"
      tone="danger"
    >
      {{ error }}
    </DsAlert>
    <DsAlert
      v-if="pendingRequest"
      tone="warning"
    >
      创建结果尚未确认。原请求已保存在当前浏览器会话，可再次确认同一请求。请勿通过新请求重复发送。
    </DsAlert>

    <div class="grid items-start gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
      <DsCard>
        <form
          id="outreach-form"
          class="space-y-6"
          @submit.prevent="reviewCreate"
        >
          <div>
            <h2 class="text-base font-semibold text-slate-900">
              创建触达任务
            </h2>
            <p class="mt-1 text-sm/6 text-slate-500">
              下一步会显示汇总，请显式确认自动审批和真实发送。
            </p>
          </div>
          <fieldset
            :disabled="formLocked || loadingOptions"
            class="space-y-6 disabled:opacity-70"
          >
            <legend class="sr-only">
              触达任务配置
            </legend>
            <div class="grid gap-5 sm:grid-cols-2">
              <DsInput
                id="outreach-name"
                v-model="form.name"
                label="任务名称"
              />
              <DsInput
                id="outreach-purpose"
                v-model="form.purpose"
                label="用途"
              />
              <DsSelect
                id="outreach-target-mode"
                v-model="form.targetMode"
                label="目标模式"
                :options="[{ value: 'EMAILS', label: '按邮件数量' }, { value: 'PAPERS', label: '按论文数量' }]"
              />
              <DsInput
                id="outreach-target-count"
                v-model="form.targetCount"
                type="number"
                label="目标数量"
                :description="form.targetMode === 'EMAILS' ? `1–${options?.limits.maxEmails ?? 120} 个唯一可发送地址` : `1–${options?.limits.maxPapers ?? 100} 篇符合条件的论文`"
              />
            </div>
            <p class="rounded-md bg-slate-50 p-3 text-xs/6 text-slate-600">
              最多搜索 {{ options?.limits.searchPaperBudget ?? 100 }} 篇论文。
              <template v-if="form.targetMode === 'EMAILS'">
                必须有足量可发送地址才会冻结目标；不足会阻止，不会无限补搜或替换失败收件人。
              </template>
              <template v-else>
                允许论文没有联系人；最多 {{ options?.limits.maxEmails ?? 120 }} 个唯一地址，超过会阻止，不会隐藏裁剪。
              </template>
            </p>
            <div class="grid gap-5 sm:grid-cols-2">
              <DsSelect
                id="outreach-sender"
                v-model="form.smtpAccountId"
                label="发件邮箱"
                :options="senderOptions"
              />
              <DsSelect
                id="outreach-template"
                v-model="form.templateId"
                label="已发布模板"
                :options="templateOptions"
              />
            </div>
            <p
              v-if="!loadingOptions && (!senderOptions.some(item => !item.disabled) || !templateOptions.some(item => !item.disabled))"
              class="text-sm/6 text-amber-700"
            >
              没有可用发件邮箱或模板。请先完成账户启用与测试，并发布通过检查的模板。
            </p>

            <div class="border-t border-slate-200 pt-6">
              <h3 class="text-sm font-semibold text-slate-900">
                论文检索范围
              </h3>
              <p class="mt-1 text-xs/5 text-slate-500">
                沿用论文发现的结构化条件；至少设置分类、关键词或提交日期之一。
              </p>
              <div class="mt-4 grid gap-5 sm:grid-cols-2">
                <DsInput
                  id="outreach-categories"
                  v-model="form.categories"
                  label="arXiv 分类"
                  placeholder="cs.AI, cs.LG"
                  description="多个分类用逗号分隔"
                />
                <DsSelect
                  id="outreach-category-mode"
                  v-model="form.categoryMode"
                  label="分类匹配方式"
                  :options="[{ value: 'ANY', label: '任意分类' }, { value: 'PRIMARY', label: '主分类' }, { value: 'CROSS_LIST', label: '交叉分类' }]"
                />
                <DsInput
                  id="outreach-title-keywords"
                  v-model="form.titleKeywords"
                  label="标题关键词"
                />
                <DsInput
                  id="outreach-abstract-keywords"
                  v-model="form.abstractKeywords"
                  label="摘要关键词"
                />
                <DsInput
                  id="outreach-author-keywords"
                  v-model="form.authorKeywords"
                  label="作者关键词"
                />
              </div>
              <details class="mt-5">
                <summary class="cursor-pointer text-sm font-medium text-brand-700">
                  日期与高级筛选
                </summary>
                <div class="mt-4 grid gap-5 sm:grid-cols-2">
                  <div
                    v-for="field in [{ key: 'submittedFrom', label: '提交日期起' }, { key: 'submittedTo', label: '提交日期止' }, { key: 'updatedFrom', label: '更新日期起' }, { key: 'updatedTo', label: '更新日期止' }] as const"
                    :key="field.key"
                  >
                    <label
                      :for="`outreach-${field.key}`"
                      class="block text-sm/6 font-medium text-slate-900"
                    >{{ field.label }}</label>
                    <input
                      :id="`outreach-${field.key}`"
                      v-model="form[field.key]"
                      type="date"
                      class="mt-2 block min-h-11 w-full min-w-0 rounded-md bg-white px-3 py-2 text-sm text-slate-900 shadow-xs outline-1 -outline-offset-1 outline-slate-300 focus:outline-2 focus:outline-brand-500"
                    >
                  </div>
                  <DsSelect
                    id="outreach-doi"
                    v-model="form.hasDoi"
                    label="有 DOI"
                    :options="triStateOptions"
                  />
                  <DsSelect
                    id="outreach-journal"
                    v-model="form.hasJournalReference"
                    label="有期刊引用"
                    :options="triStateOptions"
                  />
                  <DsSelect
                    id="outreach-source"
                    v-model="form.sourceAvailable"
                    label="来源可用"
                    :options="triStateOptions"
                  />
                  <DsSelect
                    id="outreach-sort"
                    v-model="form.sortBy"
                    label="排序字段"
                    :options="[{ value: 'RELEVANCE', label: '相关性' }, { value: 'LAST_UPDATED_DATE', label: '更新日期' }, { value: 'SUBMITTED_DATE', label: '提交日期' }]"
                  />
                  <DsSelect
                    id="outreach-order"
                    v-model="form.sortOrder"
                    label="排序方向"
                    :options="[{ value: 'DESCENDING', label: '降序' }, { value: 'ASCENDING', label: '升序' }]"
                  />
                </div>
              </details>
            </div>
          </fieldset>
          <div class="border-t border-slate-200 pt-5">
            <DsButton
              data-testid="review-create"
              type="submit"
              :disabled="!creationReady || loadingOptions"
              :busy="saving"
              class="w-full sm:w-auto"
            >
              <PaperAirplaneIcon class="size-4" />{{ pendingRequest ? '重新确认原请求' : '查看汇总并确认' }}
            </DsButton>
          </div>
        </form>
      </DsCard>

      <DsCard aria-labelledby="outreach-progress-title">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h2
            id="outreach-progress-title"
            class="text-base font-semibold text-slate-900"
          >
            任务进度
          </h2>
          <DsButton
            v-if="typeof route.query.run === 'string'"
            variant="secondary"
            :busy="loadingRun"
            @click="loadRun(route.query.run as string)"
          >
            <ArrowPathIcon class="size-4" />刷新进度
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
          v-if="loadingRun && !currentRun"
          class="mt-5 text-sm text-slate-500"
          role="status"
        >
          正在恢复任务进度…
        </p>
        <div
          v-else-if="currentRun"
          class="mt-5 space-y-5"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <h3 class="break-words font-medium text-slate-900">
                {{ currentRun.name }}
              </h3>
              <p class="mt-1 text-xs/5 text-slate-500">
                {{ currentRun.targetMode === 'EMAILS' ? '邮件目标' : '论文目标' }}：{{ currentRun.targetCount }} {{ currentRun.targetMode === 'EMAILS' ? '个唯一地址' : '篇论文' }}
              </p>
            </div>
            <DsBadge
              :tone="statusTone(currentRun.status)"
              dot
            >
              {{ statusLabel(currentRun.status) }}
            </DsBadge>
          </div>
          <div
            aria-live="polite"
            class="rounded-md bg-slate-50 p-3"
          >
            <p class="text-sm font-medium text-slate-800">
              {{ stageLabel(currentRun.stage) }}
            </p>
            <p
              v-if="currentRun.message"
              class="mt-1 break-words text-sm/6 text-slate-600"
            >
              {{ currentRun.message }}
            </p>
            <p class="mt-2 text-xs/5 text-slate-500">
              {{ isTerminal(currentRun) ? '任务已停止推进' : '每 3 秒更新；服务端继续运行' }}
            </p>
          </div>
          <dl class="grid grid-cols-2 gap-x-4 gap-y-5 sm:grid-cols-3">
            <div
              v-for="field in countFields"
              :key="field.key"
              :data-testid="`progress-${field.key}`"
            >
              <dt class="text-xs text-slate-500">
                {{ field.label }}
              </dt>
              <dd class="mt-1 text-xl font-semibold tabular-nums text-slate-900">
                {{ currentRun[field.key] }} <span class="text-xs font-normal text-slate-500">{{ field.unit }}</span>
              </dd>
            </div>
          </dl>
          <p class="text-xs/6 text-slate-500">
            SMTP 接受不代表送达。退信、回复和未知结果请结合活动发送记录确认；不会补发未知结果。
          </p>
          <div class="flex flex-wrap gap-x-5 gap-y-2 text-sm font-medium text-brand-700">
            <RouterLink
              v-if="currentRun.campaignId"
              data-testid="outreach-campaign-link"
              :to="`/email/campaigns/${currentRun.campaignId}`"
              class="inline-flex min-h-11 items-center"
            >
              查看邮件活动 →
            </RouterLink>
            <RouterLink
              v-if="currentRun.importJobId && auth.hasPermission('paper:read')"
              :to="`/jobs/${currentRun.importJobId}`"
              class="inline-flex min-h-11 items-center"
            >
              导入任务 →
            </RouterLink>
            <RouterLink
              v-if="currentRun.extractionJobId && auth.hasPermission('paper:read')"
              :to="`/jobs/${currentRun.extractionJobId}`"
              class="inline-flex min-h-11 items-center"
            >
              提取任务 →
            </RouterLink>
          </div>
          <div class="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-4">
            <p class="text-xs text-slate-500">
              更新于 {{ dateTime(currentRun.updatedAt) }}
            </p>
            <DsButton
              v-if="canCancel"
              data-testid="review-cancel"
              variant="secondary"
              :disabled="canceling"
              @click="cancelTarget = { ...currentRun }"
            >
              取消任务
            </DsButton>
          </div>
        </div>
        <DsEmptyState
          v-else
          title="尚未选择任务"
          description="创建任务或从历史记录选择，刷新页面后也可以继续查看。"
        />
      </DsCard>
    </div>

    <DsCard aria-labelledby="outreach-history-title">
      <div class="flex items-center justify-between gap-3">
        <h2
          id="outreach-history-title"
          class="text-base font-semibold text-slate-900"
        >
          历史任务
        </h2>
        <DsButton
          variant="ghost"
          :busy="loadingHistory"
          @click="loadHistory(history?.page ?? 1)"
        >
          <ArrowPathIcon class="size-4" />刷新历史
        </DsButton>
      </div>
      <DsAlert
        v-if="historyError"
        tone="danger"
        class="mt-4"
      >
        {{ historyError }}
      </DsAlert>
      <p
        v-if="loadingHistory && !history"
        class="mt-4 text-sm text-slate-500"
        role="status"
      >
        正在加载历史任务…
      </p>
      <ul
        v-else-if="history?.items.length"
        class="mt-4 divide-y divide-slate-200"
      >
        <li
          v-for="run in history.items"
          :key="run.id"
        >
          <button
            :data-testid="`history-run-${run.id}`"
            type="button"
            :aria-current="currentRun?.id === run.id ? 'true' : undefined"
            class="flex min-h-16 w-full flex-wrap items-center justify-between gap-3 rounded-md px-2 py-3 text-left hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-brand-500"
            @click="selectRun(run, true)"
          >
            <span class="min-w-0 flex-1"><span class="block break-words text-sm font-medium text-slate-900">{{ run.name }}</span><span class="mt-1 block text-xs/5 text-slate-500">{{ run.targetMode === 'EMAILS' ? '邮件' : '论文' }}目标 {{ run.targetCount }} · SMTP 接受 {{ run.smtpAccepted }} · {{ dateTime(run.createdAt) }}</span></span>
            <DsBadge :tone="statusTone(run.status)">
              {{ statusLabel(run.status) }}
            </DsBadge>
          </button>
        </li>
      </ul>
      <DsEmptyState
        v-else-if="!historyError"
        title="暂无触达任务"
        description="完成配置并确认发送后，任务会持久保存在服务端。"
      />
      <DsPagination
        v-if="history && history.totalPages > 1"
        :page="history.page"
        :total-pages="history.totalPages"
        class="mt-4"
        @change="loadHistory"
      />
    </DsCard>

    <DsModal
      :open="confirmationOpen"
      title="确认自动审批与真实发送"
      @close="!saving && (confirmationOpen = false)"
    >
      <div
        v-if="reviewRequest"
        data-testid="outreach-confirmation"
        class="space-y-4"
      >
        <DsAlert
          v-if="options?.productionHoldReason"
          tone="warning"
        >
          {{ options.productionHoldReason }}。任务可先搜索与准备；服务端会在发送启动前阻止，不会绕过生产限制。
        </DsAlert>
        <dl class="space-y-3 text-sm/6">
          <div>
            <dt class="text-slate-500">
              任务与用途
            </dt><dd class="break-words font-medium text-slate-900">
              {{ reviewRequest.name }} · {{ reviewRequest.purpose }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">
              目标
            </dt><dd class="font-medium text-slate-900">
              {{ reviewRequest.targetCount }} {{ reviewRequest.targetMode === 'EMAILS' ? '个唯一可发送地址' : '篇符合条件的论文' }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">
              发件邮箱
            </dt><dd class="break-all font-medium text-slate-900">
              {{ selectedSender?.name }} · {{ selectedSender?.fromEmail }}
            </dd><dd
              v-if="selectedSender?.replyToEmail"
              class="break-all text-xs text-slate-500"
            >
              Reply-To：{{ selectedSender.replyToEmail }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">
              模板
            </dt><dd class="font-medium text-slate-900">
              {{ selectedTemplate?.name }} · v{{ selectedTemplate?.currentVersion }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">
              论文条件
            </dt><dd class="break-words text-slate-800">
              分类：{{ reviewRequest.criteria.categoryIds.join(', ') || '不限' }}；标题：{{ reviewRequest.criteria.titleKeywords || '不限' }}；摘要：{{ reviewRequest.criteria.abstractKeywords || '不限' }}；作者：{{ reviewRequest.criteria.authorKeywords || '不限' }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">
              分类匹配
            </dt><dd class="text-slate-800">
              {{ { ANY: '任意分类', PRIMARY: '主分类', CROSS_LIST: '交叉分类' }[reviewRequest.criteria.categoryMode] }}
            </dd>
          </div>
          <div v-if="reviewRequest.criteria.submittedFrom || reviewRequest.criteria.submittedTo">
            <dt class="text-slate-500">
              提交日期
            </dt><dd class="text-slate-800">
              {{ reviewRequest.criteria.submittedFrom || '不限' }} 至 {{ reviewRequest.criteria.submittedTo || '不限' }}
            </dd>
          </div>
          <div v-if="reviewRequest.criteria.updatedFrom || reviewRequest.criteria.updatedTo">
            <dt class="text-slate-500">
              更新日期
            </dt><dd class="text-slate-800">
              {{ reviewRequest.criteria.updatedFrom || '不限' }} 至 {{ reviewRequest.criteria.updatedTo || '不限' }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">
              附加筛选
            </dt><dd class="text-slate-800">
              DOI：{{ reviewRequest.criteria.hasDoi === undefined ? '不限' : reviewRequest.criteria.hasDoi ? '是' : '否' }}；期刊引用：{{ reviewRequest.criteria.hasJournalReference === undefined ? '不限' : reviewRequest.criteria.hasJournalReference ? '是' : '否' }}；来源可用：{{ reviewRequest.criteria.sourceAvailable === undefined ? '不限' : reviewRequest.criteria.sourceAvailable ? '是' : '否' }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">
              排序
            </dt><dd class="text-slate-800">
              {{ { RELEVANCE: '相关性', LAST_UPDATED_DATE: '更新日期', SUBMITTED_DATE: '提交日期' }[reviewRequest.criteria.sortBy] }} · {{ reviewRequest.criteria.sortOrder === 'ASCENDING' ? '升序' : '降序' }}
            </dd>
          </div>
        </dl>
        <p class="rounded-md bg-amber-50 p-3 text-xs/6 text-amber-800">
          最多搜索 {{ options?.limits.searchPaperBudget }} 篇论文。<template v-if="reviewRequest.targetMode === 'EMAILS'">
            不足目标会阻止，发送不会超过目标，也不会替换失败收件人。
          </template><template v-else>
            最多 {{ options?.limits.maxEmails }} 个唯一地址，超过会阻止，不会隐藏裁剪。
          </template>模板固定文案由此次选择授权；机器预检不等于人工语义核验。已经发送的邮件无法撤回。
        </p>
        <DsCheckbox
          id="outreach-send-confirmation"
          v-model="sendConfirmed"
          label="我确认所选范围、邮箱和模板，并授权自动审批及真实发送"
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
        </DsButton>
        <DsButton
          data-testid="confirm-create"
          :disabled="!sendConfirmed || !creationReady"
          :busy="saving"
          @click="createRun"
        >
          确认并启动
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="!!cancelTarget"
      title="取消触达任务？"
      @close="!canceling && (cancelTarget = null)"
    >
      <p class="text-sm/6 text-slate-600">
        取消将停止后续自动推进，并按活动发送租约安全停止投递。已经发送的邮件无法撤回；进行中的投递可能仍返回最终结果。
      </p>
      <template #actions>
        <DsButton
          variant="secondary"
          :disabled="canceling"
          @click="cancelTarget = null"
        >
          继续任务
        </DsButton>
        <DsButton
          data-testid="confirm-cancel"
          variant="danger"
          :busy="canceling"
          :disabled="!canPause"
          @click="cancelRun"
        >
          确认取消
        </DsButton>
      </template>
    </DsModal>
  </section>
</template>
