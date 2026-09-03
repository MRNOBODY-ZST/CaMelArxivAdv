<script setup lang="ts">
import {
  ArrowPathIcon, CheckCircleIcon, ClockIcon, ExclamationTriangleIcon, PaperAirplaneIcon,
  PauseIcon, PlayIcon, ShieldCheckIcon, SparklesIcon,
} from '@heroicons/vue/24/outline'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type {
  CampaignPreflight, CampaignRecipient, CampaignStatus, CampaignView, DeliveryView, RuntimeStatus,
  SafetyRunView,
} from '@/modules/campaigns/campaigns.types'

const SAFETY_CONFIRMATION = 'SAFETY_REDIRECT' as const
const route = useRoute()
const auth = useAuthStore()
const campaign = ref<CampaignView | null>(null)
const runtime = ref<RuntimeStatus | null>(null)
const preflight = ref<CampaignPreflight | null>(null)
const safetyRuns = ref<SafetyRunView[]>([])
const recipients = ref<CampaignRecipient[]>([])
const deliveries = ref<DeliveryView[]>([])
const page = ref(1)
const totalPages = ref(0)
const loading = ref(true)
const mutating = ref(false)
const generating = ref(false)
const startingSafety = ref(false)
const error = ref('')
const notice = ref('')
const safetyRecipientLimit = ref('1')
const safetyConfirmation = ref('')
let pollTimer: ReturnType<typeof globalThis.setTimeout> | null = null

const id = computed(() => String(route.params.id ?? ''))
const latestSafetyRun = computed(() => safetyRuns.value[0] ?? null)
const safetyLimit = computed(() => /^\d+$/.test(safetyRecipientLimit.value)
  ? Number(safetyRecipientLimit.value) : Number.NaN)
const safetyLimitValid = computed(() => Number.isInteger(safetyLimit.value)
  && safetyLimit.value >= 1 && safetyLimit.value <= 20)
const safetyLimitError = computed(() => safetyLimitValid.value ? '' : '安全实流数量必须在 1–20 之间。')
const safetyConfirmationValid = computed(() => safetyConfirmation.value === SAFETY_CONFIRMATION)
const safetyActive = computed(() => ['QUEUED', 'RUNNING'].includes(latestSafetyRun.value?.status ?? ''))
const personalizationActive = computed(() => ['QUEUED', 'RUNNING'].includes(campaign.value?.generationStatus ?? ''))
const productionActive = computed(() => ['SCHEDULED', 'RUNNING'].includes(campaign.value?.status ?? ''))
const polling = computed(() => personalizationActive.value || safetyActive.value || productionActive.value)
const canGenerate = computed(() => Boolean(
  auth.hasPermission('campaign:create')
  && campaign.value?.status === 'DRAFT'
  && campaign.value.generationStatus === 'NOT_REQUESTED'
  && runtime.value?.generationReady,
))
const canStartSafety = computed(() => auth.hasPermission('campaign:send')
  && !safetyActive.value && safetyLimitValid.value && safetyConfirmationValid.value)

type PrimaryAction = { label: string; icon: typeof PaperAirplaneIcon; enabled: boolean; run: () => Promise<CampaignView> }
const primaryAction = computed<PrimaryAction | null>(() => {
  const current = campaign.value
  if (!current) return null
  if (current.status === 'DRAFT' && auth.hasPermission('campaign:create')) {
    return {
      label: '提交审核', icon: CheckCircleIcon, enabled: Boolean(preflight.value?.ready),
      run: () => campaignsApi.submitCampaignForReview(current.id, current.lockVersion),
    }
  }
  if (current.status === 'READY_FOR_REVIEW' && auth.hasPermission('campaign:approve')) {
    return {
      label: '批准活动', icon: ShieldCheckIcon, enabled: Boolean(preflight.value?.ready),
      run: () => campaignsApi.approveCampaign(current.id, current.lockVersion),
    }
  }
  if (['APPROVED', 'SCHEDULED'].includes(current.status) && auth.hasPermission('campaign:send')) {
    return {
      label: '开始正式发送', icon: PaperAirplaneIcon, enabled: Boolean(preflight.value?.ready),
      run: () => campaignsApi.startCampaign(current.id, current.lockVersion),
    }
  }
  if (current.status === 'RUNNING' && auth.hasPermission('campaign:pause')) {
    return {
      label: '暂停正式发送', icon: PauseIcon, enabled: true,
      run: () => campaignsApi.pauseCampaign(current.id, current.lockVersion),
    }
  }
  if (current.status === 'PAUSED' && auth.hasPermission('campaign:pause')) {
    return {
      label: '恢复正式发送', icon: PlayIcon, enabled: true,
      run: () => campaignsApi.resumeCampaign(current.id, current.lockVersion),
    }
  }
  return null
})

const lifecycleSteps = computed(() => {
  const current = campaign.value
  const reviewed = current ? ['APPROVED', 'SCHEDULED', 'RUNNING', 'PAUSED', 'COMPLETED'].includes(current.status) : false
  const safetyComplete = safetyRuns.value.some((run) => run.status === 'COMPLETED')
  const productionStarted = current ? ['RUNNING', 'PAUSED', 'COMPLETED'].includes(current.status) : false
  return [
    { label: '个性化内容', complete: current?.generationStatus === 'COMPLETED', detail: generationLabel(current?.generationStatus) },
    { label: '审核批准', complete: reviewed, detail: statusLabel(current?.status) },
    { label: '安全实流', complete: safetyComplete, detail: latestSafetyRun.value ? safetyStatusLabel(latestSafetyRun.value.status) : '尚未执行' },
    { label: '正式发送', complete: current?.status === 'COMPLETED', current: productionStarted, detail: statusLabel(current?.status) },
  ]
})

const exclusionCounts = computed(() => {
  const labels: Record<string, string> = {
    CONTENT_NOT_READY: '内容未就绪', UNSUBSCRIBE_MISSING: '缺少退订占位符', CONFIDENCE_NOT_HIGH: '置信度不足',
    CONTACT_INACTIVE: '联系人未激活', CONTACT_DELETED: '联系人已删除', SYNTAX_INVALID: '地址格式无效',
    EXAMPLE_ADDRESS: '示例地址', AUTHOR_RELATION_MISSING: '作者关系缺失', EVIDENCE_NOT_HIGH: '证据不足',
    EVIDENCE_UNVERIFIED: '证据未验证', EVIDENCE_UNCONFIRMED: '证据未确认', EVIDENCE_MISSING: '证据缺失',
    SUPPRESSED: '抑制名单', UNSUBSCRIBED: '已退订', CAMPAIGN_EXCLUDED: '活动内排除', COOLDOWN_ACTIVE: '发送冷却中',
  }
  return Object.entries(preflight.value?.counts ?? {})
    .filter(([key, value]) => !['TOTAL', 'ELIGIBLE'].includes(key) && value > 0)
    .map(([key, value]) => ({ key, label: labels[key] ?? key, value }))
})

const generationUnavailableReason = computed(() => {
  if (!runtime.value) return '生成运行状态不可用，请由系统管理员检查服务配置。'
  if (!runtime.value.personalizationEnabled) return '个性化生成当前未启用：请在后端配置服务密钥后开启。'
  if (!runtime.value.rayConfigured) return 'Ray 计算集群尚未配置。'
  if (!runtime.value.kafkaConfigured) return 'Kafka 消息平台尚未配置。'
  if (!auth.hasPermission('campaign:create')) return '当前账号没有个性化生成权限。'
  if (campaign.value?.generationStatus !== 'NOT_REQUESTED') return '当前活动已经开始或完成生成，不能重复提交。'
  if (campaign.value?.status !== 'DRAFT') return '只有草稿活动可以生成个性化内容。'
  return ''
})

async function load(target = page.value, quiet = false): Promise<void> {
  if (!quiet) loading.value = true
  if (!quiet) error.value = ''
  try {
    const [campaignResult, recipientResult, preflightResult, safetyResult, deliveryResult] = await Promise.all([
      campaignsApi.getCampaign(id.value),
      campaignsApi.listRecipients(id.value, target),
      campaignsApi.preflightCampaign(id.value),
      campaignsApi.listSafetyRuns(id.value),
      campaignsApi.listDeliveries(1, 10, id.value),
    ])
    campaign.value = campaignResult
    recipients.value = recipientResult.items
    page.value = recipientResult.page
    totalPages.value = recipientResult.totalPages
    preflight.value = preflightResult
    safetyRuns.value = safetyResult
    deliveries.value = deliveryResult.items
  } catch (cause) {
    if (!quiet) error.value = campaignErrorMessage(cause, '活动详情加载失败。')
  } finally {
    if (!quiet) loading.value = false
    schedulePoll()
  }
}

async function loadRuntime(): Promise<void> {
  try {
    runtime.value = await campaignsApi.runtimeStatus()
  } catch {
    runtime.value = null
  }
}

async function startGeneration(): Promise<void> {
  if (!canGenerate.value) return
  generating.value = true
  clearFeedback()
  try {
    const result = await campaignsApi.startPersonalization(id.value)
    notice.value = `已提交 ${result.queuedRecipients} 位作者，任务 ${result.jobId} 正在由 Ray 分发处理。`
    await load(1, true)
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '个性化生成启动失败。')
  } finally {
    generating.value = false
  }
}

async function executePrimary(): Promise<void> {
  if (!primaryAction.value?.enabled) return
  mutating.value = true
  clearFeedback()
  try {
    campaign.value = await primaryAction.value.run()
    notice.value = '活动状态已更新。'
    await load(page.value, true)
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '活动状态更新失败，请刷新后重试。')
  } finally {
    mutating.value = false
  }
}

async function startSafetyRun(): Promise<void> {
  if (!canStartSafety.value || !campaign.value) return
  startingSafety.value = true
  clearFeedback()
  try {
    const created = await campaignsApi.startSafetyRun(campaign.value.id, {
      expectedLockVersion: campaign.value.lockVersion,
      recipientLimit: safetyLimit.value,
      confirmation: SAFETY_CONFIRMATION,
    })
    safetyRuns.value = [created, ...safetyRuns.value]
    notice.value = `安全实流已提交，所有 ${created.recipientLimit} 封邮件只会转发到 ${created.destinationMasked}。`
    schedulePoll()
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '安全实流启动失败。')
  } finally {
    startingSafety.value = false
  }
}

function clearFeedback(): void {
  error.value = ''
  notice.value = ''
}

function schedulePoll(): void {
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
  pollTimer = polling.value
    ? globalThis.setTimeout(() => void load(page.value, true), 3_000)
    : null
}

function statusLabel(status?: CampaignStatus): string {
  return ({
    DRAFT: '草稿', READY_FOR_REVIEW: '待审核', APPROVED: '已批准', REJECTED: '已驳回', SCHEDULED: '已排期',
    RUNNING: '发送中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELED: '已取消',
  } as Partial<Record<CampaignStatus, string>>)[status ?? 'DRAFT'] ?? '未知'
}

function generationLabel(status?: string): string {
  return ({ NOT_REQUESTED: '尚未生成', QUEUED: '排队中', RUNNING: '生成中', COMPLETED: '已生成', PARTIALLY_FAILED: '部分失败', FAILED: '失败' } as Record<string, string>)[status ?? ''] ?? '未知'
}

function safetyStatusLabel(status: string): string {
  return ({ QUEUED: '排队中', RUNNING: '执行中', COMPLETED: '已完成', PARTIALLY_FAILED: '部分失败', FAILED: '失败', CANCELED: '已取消' } as Record<string, string>)[status] ?? status
}

function tone(status: string): 'neutral' | 'positive' | 'warning' | 'danger' | 'info' {
  if (['COMPLETED', 'SMTP_ACCEPTED', 'APPROVED'].includes(status)) return 'positive'
  if (['FAILED', 'PERMANENT_FAILURE', 'BOUNCED', 'CANCELED'].includes(status)) return 'danger'
  if (['PARTIALLY_FAILED', 'TEMPORARY_FAILURE', 'OUTCOME_UNKNOWN', 'PAUSED'].includes(status)) return 'warning'
  if (['RUNNING', 'QUEUED', 'CONNECTING', 'SCHEDULED'].includes(status)) return 'info'
  return 'neutral'
}

onMounted(async () => {
  await Promise.all([loadRuntime(), load()])
})
onBeforeUnmount(() => {
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
})
</script>

<template>
  <section
    data-testid="campaign-detail-view"
    aria-labelledby="campaign-detail-title"
    class="space-y-6"
  >
    <header class="gap-5 sm:flex sm:items-start sm:justify-between">
      <div class="min-w-0">
        <div class="flex flex-wrap items-center gap-2">
          <p class="text-xs font-semibold uppercase tracking-wide text-brand-700">
            活动运营中心
          </p>
          <DsBadge
            v-if="campaign"
            :tone="tone(campaign.status)"
            dot
          >
            {{ statusLabel(campaign.status) }}
          </DsBadge>
        </div>
        <h1
          id="campaign-detail-title"
          class="mt-1 truncate text-2xl font-semibold tracking-tight text-slate-950"
        >
          {{ campaign?.name ?? '活动加载中' }}
        </h1>
        <p class="mt-2 max-w-3xl text-sm/6 text-slate-600">
          {{ campaign?.purpose ?? '准备、审核并观察个性化论文邮件。' }}
        </p>
      </div>
      <DsButton
        v-if="primaryAction"
        data-testid="primary-campaign-action"
        class="mt-4 shrink-0 sm:mt-0"
        :disabled="!primaryAction.enabled"
        :busy="mutating"
        @click="executePrimary"
      >
        <component
          :is="primaryAction.icon"
          class="size-4"
        />{{ primaryAction.label }}
      </DsButton>
    </header>

    <DsAlert
      v-if="error"
      tone="danger"
    >
      {{ error }}
    </DsAlert>
    <DsAlert
      v-if="notice"
      tone="success"
    >
      {{ notice }}
    </DsAlert>
    <DsAlert
      tone="info"
      title="状态说明"
    >
      个性化草稿不等于已发送；SMTP 已接受不等于最终送达；打开或点击回传不等于确认人工阅读。
    </DsAlert>
    <DsAlert
      v-if="polling"
      tone="info"
      title="正在同步运行状态"
    >
      页面仅在任务未结束时每 3 秒刷新；离开页面会停止轮询。
    </DsAlert>

    <div
      v-if="loading"
      class="h-64 animate-pulse rounded-lg bg-slate-100"
      aria-label="活动详情加载中"
    />
    <template v-else-if="campaign">
      <DsCard>
        <h2 class="text-sm font-semibold text-slate-900">
          从内容到回传
        </h2>
        <ol
          class="mt-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-4"
          aria-label="活动进度"
        >
          <li
            v-for="(step, index) in lifecycleSteps"
            :key="step.label"
            class="relative flex gap-3"
          >
            <span :class="[step.complete ? 'bg-brand-600 text-white' : step.current ? 'border-brand-600 bg-white text-brand-700' : 'border-slate-300 bg-white text-slate-400', 'relative z-10 flex size-8 shrink-0 items-center justify-center rounded-full border-2 text-xs font-semibold']">
              <CheckCircleIcon
                v-if="step.complete"
                class="size-5"
              /><span v-else>{{ index + 1 }}</span>
            </span>
            <div class="min-w-0">
              <p class="text-sm font-semibold text-slate-900">
                {{ step.label }}
              </p><p class="mt-0.5 text-xs text-slate-500">
                {{ step.detail }}
              </p>
            </div>
          </li>
        </ol>
      </DsCard>

      <div class="grid gap-6 xl:grid-cols-[minmax(0,1.45fr)_minmax(21rem,0.75fr)]">
        <DsCard>
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h2 class="text-base font-semibold text-slate-900">
                正式发送预检
              </h2><p class="mt-1 text-sm text-slate-500">
                动态排除不合格、退订、抑制和冷却中的联系人。
              </p>
            </div>
            <DsBadge
              :tone="preflight?.ready ? 'positive' : 'warning'"
              dot
            >
              {{ preflight?.ready ? '可以继续' : '尚未就绪' }}
            </DsBadge>
          </div>
          <dl class="mt-5 grid grid-cols-3 divide-x divide-slate-200 overflow-hidden rounded-lg bg-slate-50 ring-1 ring-slate-200">
            <div class="px-3 py-4 sm:px-5">
              <dt class="text-xs text-slate-500">
                全部候选
              </dt><dd class="mt-1 text-2xl font-semibold text-slate-950">
                {{ preflight?.counts.TOTAL ?? 0 }}
              </dd>
            </div>
            <div class="px-3 py-4 sm:px-5">
              <dt class="text-xs text-slate-500">
                可发送
              </dt><dd class="mt-1 text-2xl font-semibold text-emerald-700">
                {{ preflight?.counts.ELIGIBLE ?? 0 }}
              </dd>
            </div>
            <div class="px-3 py-4 sm:px-5">
              <dt class="text-xs text-slate-500">
                预计耗时
              </dt><dd class="mt-1 text-2xl font-semibold text-slate-950">
                {{ preflight?.estimatedMinutes ?? 0 }}<span class="ml-1 text-xs font-normal text-slate-500">分钟</span>
              </dd>
            </div>
          </dl>
          <div
            v-if="exclusionCounts.length"
            class="mt-5"
          >
            <p class="text-xs font-semibold uppercase tracking-wide text-slate-500">
              排除原因
            </p><div class="mt-2 flex flex-wrap gap-2">
              <span
                v-for="item in exclusionCounts"
                :key="item.key"
                class="rounded-full bg-amber-50 px-3 py-1.5 text-xs font-medium text-amber-800 ring-1 ring-amber-200"
              >{{ item.label }} {{ item.value }}</span>
            </div>
          </div>
          <ul class="mt-5 grid gap-2 sm:grid-cols-2">
            <li
              v-for="(check, key) in preflight?.checks"
              :key="key"
              class="flex gap-2 text-xs/5 text-slate-600"
            >
              <CheckCircleIcon
                v-if="check.passed"
                class="mt-0.5 size-4 shrink-0 text-emerald-600"
              /><ExclamationTriangleIcon
                v-else
                class="mt-0.5 size-4 shrink-0 text-amber-600"
              /><span>{{ check.detail }}</span>
            </li>
          </ul>
        </DsCard>

        <DsCard>
          <div class="flex items-start justify-between gap-3">
            <div>
              <h2 class="text-base font-semibold text-slate-900">
                安全实流
              </h2><p class="mt-1 text-sm/6 text-slate-500">
                真实走 SMTP 与回传链路，但服务端强制改投固定测试邮箱。
              </p>
            </div><ShieldCheckIcon class="size-6 shrink-0 text-brand-600" />
          </div>
          <div
            v-if="latestSafetyRun"
            class="mt-4 rounded-lg bg-slate-50 p-4 ring-1 ring-slate-200"
          >
            <div class="flex items-center justify-between gap-3">
              <div>
                <p class="text-xs text-slate-500">
                  固定目标
                </p><p class="mt-1 font-mono text-sm font-semibold text-slate-900">
                  {{ latestSafetyRun.destinationMasked }}
                </p>
              </div><DsBadge
                :tone="tone(latestSafetyRun.status)"
                dot
              >
                {{ safetyStatusLabel(latestSafetyRun.status) }}
              </DsBadge>
            </div>
            <div class="mt-4 h-2 overflow-hidden rounded-full bg-slate-200">
              <div
                class="h-full rounded-full bg-brand-500 transition-all"
                :style="{ width: `${latestSafetyRun.progress.total ? Math.round((latestSafetyRun.progress.smtpAccepted + latestSafetyRun.progress.permanentFailure + latestSafetyRun.progress.outcomeUnknown + latestSafetyRun.progress.canceled) / latestSafetyRun.progress.total * 100) : 0}%` }"
              />
            </div>
            <p class="mt-2 text-xs text-slate-500">
              SMTP 接受 {{ latestSafetyRun.progress.smtpAccepted }}/{{ latestSafetyRun.progress.total }} · 回复 {{ latestSafetyRun.events.reply }} · 退信 {{ latestSafetyRun.events.bounce }} · 结果未知 {{ latestSafetyRun.progress.outcomeUnknown }}
            </p>
          </div>
          <div
            v-if="auth.hasPermission('campaign:send')"
            class="mt-5 space-y-4"
          >
            <DsInput
              id="safety-recipient-limit"
              v-model="safetyRecipientLimit"
              type="number"
              label="抽样邮件数"
              description="只能选择 1–20 封；不会投递给原作者。"
              :error="safetyLimitError"
            />
            <DsInput
              id="safety-confirmation"
              v-model="safetyConfirmation"
              label="确认短语"
              :description="`请输入 ${SAFETY_CONFIRMATION}`"
              autocomplete="off"
            />
            <DsButton
              data-testid="start-safety-run"
              class="w-full"
              :disabled="!canStartSafety"
              :busy="startingSafety"
              @click="startSafetyRun"
            >
              <ShieldCheckIcon class="size-4" />启动安全实流
            </DsButton>
          </div>
          <DsAlert
            v-else
            class="mt-5"
            tone="warning"
          >
            当前账号可以查看证据，但没有启动安全实流的权限。
          </DsAlert>
        </DsCard>
      </div>

      <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            个性化草稿
          </p><p class="mt-1 text-2xl font-semibold text-slate-950">
            {{ campaign.recipientCounts.generated }}
          </p><p class="mt-1 text-xs text-slate-500">
            失败 {{ campaign.recipientCounts.failed }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            正式 SMTP 接受
          </p><p class="mt-1 text-2xl font-semibold text-emerald-700">
            {{ campaign.deliveryCounts.smtpAccepted }}
          </p><p class="mt-1 text-xs text-slate-500">
            不代表最终送达
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            结果未知
          </p><p class="mt-1 text-2xl font-semibold text-amber-700">
            {{ campaign.deliveryCounts.outcomeUnknown }}
          </p><p class="mt-1 text-xs text-slate-500">
            需等待回传或人工核验
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            退信 / 退订
          </p><p class="mt-1 text-2xl font-semibold text-rose-700">
            {{ campaign.deliveryCounts.bounced + campaign.deliveryCounts.unsubscribed }}
          </p><p class="mt-1 text-xs text-slate-500">
            后续发送会自动抑制
          </p>
        </DsCard>
      </div>

      <DsCard>
        <div class="gap-4 sm:flex sm:items-start sm:justify-between">
          <div>
            <h2 class="text-base font-semibold text-slate-900">
              内容准备
            </h2><p class="mt-1 text-sm text-slate-500">
              生成只产生逐人草稿，不触发 SMTP 投递。
            </p>
          </div><div class="mt-4 flex flex-wrap gap-2 sm:mt-0">
            <RouterLink
              data-testid="template-editor-link"
              :to="`/email/templates/${campaign.templateId}`"
              class="inline-flex min-h-10 items-center rounded-md px-3 text-sm font-semibold text-brand-700 hover:bg-brand-50"
            >
              {{ campaign.templateName }} · v{{ campaign.templateVersion }}
            </RouterLink><DsButton
              data-testid="start-personalization"
              variant="secondary"
              :disabled="!canGenerate"
              :busy="generating"
              @click="startGeneration"
            >
              <SparklesIcon class="size-4" />生成个性化草稿
            </DsButton>
          </div>
        </div>
        <DsAlert
          v-if="generationUnavailableReason"
          class="mt-4"
          tone="warning"
          title="生成条件未满足"
        >
          {{ generationUnavailableReason }}
        </DsAlert>
      </DsCard>

      <DsCard
        v-if="deliveries.length"
        padding="none"
      >
        <div class="flex items-center justify-between gap-3 px-5 py-4">
          <div>
            <h2 class="text-base font-semibold text-slate-900">
              最近正式投递证据
            </h2><p class="mt-1 text-xs text-slate-500">
              失败分类与是否可重试来自服务端安全摘要。
            </p>
          </div><DsButton
            variant="ghost"
            size="sm"
            @click="load(page)"
          >
            <ArrowPathIcon class="size-4" />刷新
          </DsButton>
        </div>
        <div class="overflow-x-auto">
          <table class="min-w-full divide-y divide-slate-200 text-left text-sm">
            <thead class="bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-500">
              <tr>
                <th class="px-5 py-3">
                  作者 / 论文
                </th><th class="px-5 py-3">
                  尝试
                </th><th class="px-5 py-3">
                  状态
                </th><th class="px-5 py-3">
                  安全摘要
                </th>
              </tr>
            </thead><tbody class="divide-y divide-slate-100">
              <tr
                v-for="delivery in deliveries"
                :key="delivery.id"
              >
                <td class="px-5 py-4">
                  <p class="font-medium text-slate-900">
                    {{ delivery.authorName }}
                  </p><p class="mt-1 max-w-sm truncate text-xs text-slate-500">
                    {{ delivery.paperTitle }}
                  </p>
                </td><td class="px-5 py-4 text-slate-600">
                  #{{ delivery.attemptNumber }}
                </td><td class="px-5 py-4">
                  <DsBadge
                    :tone="tone(delivery.status)"
                    dot
                  >
                    {{ delivery.status }}
                  </DsBadge>
                </td><td class="px-5 py-4 text-xs text-slate-500">
                  {{ delivery.failureCategory ?? delivery.smtpResponseSummary ?? '暂无补充证据' }}<span v-if="delivery.retryable"> · 可重试</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </DsCard>

      <div class="flex items-center justify-between gap-3">
        <div>
          <h2 class="text-lg font-semibold text-slate-900">
            逐人草稿
          </h2><p class="mt-1 text-sm text-slate-500">
            审阅论文上下文生成的主题、正文和生成说明。
          </p>
        </div><DsButton
          variant="secondary"
          size="sm"
          @click="load(page)"
        >
          <ArrowPathIcon class="size-4" />刷新
        </DsButton>
      </div>
      <div
        v-if="recipients.length"
        class="space-y-4"
      >
        <DsCard
          v-for="recipient in recipients"
          :key="recipient.id"
        >
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h3 class="font-semibold text-slate-900">
                {{ recipient.authorName }}
              </h3><p class="mt-1 text-sm text-slate-500">
                {{ recipient.paperTitle }}<span v-if="recipient.category"> · {{ recipient.category }}</span>
              </p>
            </div><DsBadge
              :tone="tone(recipient.personalizationStatus)"
              dot
            >
              {{ recipient.personalizationStatus }}
            </DsBadge>
          </div>
          <div
            v-if="recipient.subject"
            class="mt-5 grid gap-4 xl:grid-cols-2"
          >
            <div>
              <p class="text-xs font-semibold uppercase tracking-wide text-slate-500">
                邮件主题
              </p><p class="mt-1 text-sm font-semibold text-slate-900">
                {{ recipient.subject }}
              </p><p class="mt-4 text-xs font-semibold uppercase tracking-wide text-slate-500">
                纯文本
              </p><pre class="mt-1 whitespace-pre-wrap rounded-lg bg-slate-50 p-4 font-sans text-sm/6 text-slate-700">{{ recipient.text }}</pre><p
                v-if="recipient.rationale"
                class="mt-3 text-xs/5 text-slate-500"
              >
                生成说明：{{ recipient.rationale }}
              </p>
            </div><iframe
              v-if="recipient.html"
              :title="`${recipient.authorName} 邮件 HTML 预览`"
              sandbox=""
              :srcdoc="recipient.html"
              class="min-h-72 w-full rounded-lg border border-slate-200 bg-white"
            />
          </div>
          <DsAlert
            v-else-if="recipient.personalizationStatus === 'FAILED'"
            class="mt-4"
            tone="danger"
          >
            {{ recipient.errorMessage ?? '生成失败，可联系管理员查看安全日志。' }}
          </DsAlert>
        </DsCard>
        <DsPagination
          v-if="totalPages > 1"
          :page="page"
          :total-pages="totalPages"
          @change="load"
        />
      </div>
      <DsCard
        v-else
        padding="none"
      >
        <DsEmptyState
          title="尚未生成收件人草稿"
          description="完成联系人分组和模板后，可从上方生成个性化草稿。"
        >
          <template #icon>
            <ClockIcon class="size-9 text-slate-400" />
          </template>
        </DsEmptyState>
      </DsCard>
    </template>
  </section>
</template>
