<script setup lang="ts">
import { ArrowPathIcon, SparklesIcon } from '@heroicons/vue/24/outline'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { CampaignRecipient, CampaignView, RuntimeStatus } from '@/modules/campaigns/campaigns.types'

const route = useRoute()
const campaign = ref<CampaignView | null>(null)
const runtime = ref<RuntimeStatus | null>(null)
const recipients = ref<CampaignRecipient[]>([])
const page = ref(1)
const totalPages = ref(0)
const loading = ref(true)
const generating = ref(false)
const error = ref('')
const notice = ref('')
let pollTimer: ReturnType<typeof globalThis.setTimeout> | null = null

const id = computed(() => String(route.params.id ?? ''))
const polling = computed(() => ['QUEUED', 'RUNNING'].includes(campaign.value?.generationStatus ?? ''))
const canGenerate = computed(() => Boolean(
  campaign.value?.status === 'DRAFT'
  && campaign.value.generationStatus === 'NOT_REQUESTED'
  && runtime.value?.generationReady,
))
const generationUnavailableReason = computed(() => {
  if (!runtime.value) return '生成运行状态不可用，请由系统管理员检查服务配置。'
  if (!runtime.value.personalizationEnabled) return '个性化生成当前未启用：请在后端配置服务密钥后开启。'
  if (!runtime.value.rayConfigured) return 'Ray 计算集群尚未配置。'
  if (!runtime.value.rabbitConfigured) return 'RabbitMQ 消息队列尚未配置。'
  if (campaign.value?.generationStatus !== 'NOT_REQUESTED') return '当前活动已经开始或完成生成，不能重复提交。'
  if (campaign.value?.status !== 'DRAFT') return '只有草稿活动可以生成个性化内容。'
  return ''
})

async function load(target = page.value, quiet = false): Promise<void> {
  if (!quiet) loading.value = true
  if (!quiet) error.value = ''
  try {
    const [campaignResult, recipientResult] = await Promise.all([
      campaignsApi.getCampaign(id.value), campaignsApi.listRecipients(id.value, target),
    ])
    campaign.value = campaignResult
    recipients.value = recipientResult.items
    page.value = recipientResult.page
    totalPages.value = recipientResult.totalPages
    schedulePoll()
  } catch (cause) {
    if (!quiet) error.value = campaignErrorMessage(cause, '活动详情加载失败。')
  } finally {
    if (!quiet) loading.value = false
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
  error.value = ''
  notice.value = ''
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

function schedulePoll(): void {
  if (pollTimer !== null) globalThis.clearTimeout(pollTimer)
  pollTimer = polling.value
    ? globalThis.setTimeout(() => void load(page.value, true), 3_000)
    : null
}

function personalizationTone(status: string): 'neutral' | 'positive' | 'warning' | 'danger' | 'info' {
  if (status === 'GENERATED') return 'positive'
  if (status === 'FAILED') return 'danger'
  if (status === 'QUEUED' || status === 'RUNNING') return 'info'
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
    <header class="gap-4 sm:flex sm:items-start sm:justify-between">
      <div>
        <p class="text-xs font-semibold uppercase tracking-wide text-brand-700">
          活动编辑器
        </p>
        <h1
          id="campaign-detail-title"
          class="mt-1 text-2xl font-semibold tracking-tight text-slate-950"
        >
          {{ campaign?.name ?? '活动加载中' }}
        </h1>
        <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">
          {{ campaign?.purpose ?? '生成并逐条审核个性化邮件草稿。' }}
        </p>
      </div>
      <DsButton
        data-testid="start-personalization"
        class="mt-4 shrink-0 sm:mt-0"
        :disabled="!canGenerate"
        :busy="generating"
        @click="startGeneration"
      >
        <SparklesIcon class="size-4" />生成个性化草稿
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
      v-if="campaign && generationUnavailableReason"
      tone="warning"
      title="生成条件未满足"
    >
      {{ generationUnavailableReason }}
    </DsAlert>
    <DsAlert
      v-if="polling"
      tone="info"
      title="正在分布式生成"
    >
      页面每 3 秒刷新一次状态；可安全离开后再返回。
    </DsAlert>

    <div
      v-if="loading"
      class="h-56 animate-pulse rounded-lg bg-slate-100"
      aria-label="活动详情加载中"
    />
    <template v-else-if="campaign">
      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            模板
          </p><RouterLink
            data-testid="template-editor-link"
            :to="`/email/templates/${campaign.templateId}`"
            class="mt-1 inline-flex min-h-8 items-center text-sm font-semibold text-brand-700"
          >
            {{ campaign.templateName }} · v{{ campaign.templateVersion }}
          </RouterLink>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            收件人分组
          </p><p class="mt-1 text-sm font-semibold text-slate-900">
            {{ campaign.segmentName }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            生成状态
          </p><p class="mt-1 text-sm font-semibold text-slate-900">
            {{ campaign.generationStatus }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            草稿结果
          </p><p class="mt-1 text-sm font-semibold text-slate-900">
            {{ campaign.recipientCounts.generated }} 成功 · {{ campaign.recipientCounts.failed }} 失败
          </p>
        </DsCard>
      </div>

      <div class="flex items-center justify-between gap-3">
        <div>
          <h2 class="text-lg font-semibold text-slate-900">
            逐人草稿
          </h2><p class="mt-1 text-sm text-slate-500">
            生成草稿与 SMTP 投递是两个独立阶段；这里不会发送邮件。
          </p>
        </div>
        <DsButton
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
            </div>
            <DsBadge
              :tone="personalizationTone(recipient.personalizationStatus)"
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
              </p>
              <p class="mt-1 text-sm font-semibold text-slate-900">
                {{ recipient.subject }}
              </p>
              <p class="mt-4 text-xs font-semibold uppercase tracking-wide text-slate-500">
                纯文本
              </p>
              <pre class="mt-1 whitespace-pre-wrap rounded-lg bg-slate-50 p-4 font-sans text-sm/6 text-slate-700">{{ recipient.text }}</pre>
              <p
                v-if="recipient.rationale"
                class="mt-3 text-xs/5 text-slate-500"
              >
                生成说明：{{ recipient.rationale }}
              </p>
            </div>
            <iframe
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
          description="满足上方运行条件后，点击“生成个性化草稿”。"
        />
      </DsCard>
    </template>
  </section>
</template>
