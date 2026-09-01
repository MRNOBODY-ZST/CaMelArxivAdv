<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import { mailTrackingApi, mailTrackingErrorMessage } from '@/modules/email/mail-tracking.api'
import {
  formatMailTrackingDate,
  mailTrackingCondition,
  mailSendSourceLabel,
  mailSendStatusLabel,
  mailTrackingState,
} from '@/modules/email/mail-tracking.presentation'
import type {
  MailClickEvent,
  MailClickLink,
  MailOpenClassification,
  MailOpenEvent,
  MailSendRecord,
} from '@/modules/email/mail-tracking.types'

const props = defineProps<{ recordId: string | null }>()
const emit = defineEmits<{ close: [] }>()

const loading = ref(false)
const error = ref('')
const record = ref<MailSendRecord | null>(null)
const events = ref<MailOpenEvent[]>([])
const links = ref<MailClickLink[]>([])
const clickEvents = ref<MailClickEvent[]>([])
const latestEvents = computed(() => [...events.value]
  .sort((left, right) => new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime())
  .slice(0, 50))

watch(() => props.recordId, (id) => {
  record.value = null
  events.value = []
  links.value = []
  clickEvents.value = []
  error.value = ''
  if (id) void load(id)
}, { immediate: true })

async function load(id = props.recordId): Promise<void> {
  if (!id) return
  loading.value = true
  error.value = ''
  try {
    const result = await mailTrackingApi.getSendRecord(id)
    if (props.recordId !== id) return
    record.value = result.record
    events.value = result.events
    links.value = result.links
    clickEvents.value = result.clickEvents
  } catch (cause) {
    if (props.recordId === id) error.value = mailTrackingErrorMessage(cause, '测试邮件记录详情加载失败。')
  } finally {
    if (props.recordId === id) loading.value = false
  }
}

function classificationLabel(value: MailOpenClassification): string {
  return {
    UNCLASSIFIED: '未分类回传', PREFETCH: '预取', IMAGE_PROXY: '图片代理', BOT: '自动化',
  }[value]
}

function classificationTone(value: MailOpenClassification): 'neutral' | 'warning' | 'info' {
  if (value === 'UNCLASSIFIED') return 'info'
  if (value === 'PREFETCH' || value === 'IMAGE_PROXY') return 'warning'
  return 'neutral'
}

function clickTarget(linkId: string): string {
  return links.value.find(link => link.id === linkId)?.targetUrl ?? '目标链接不可用'
}
</script>

<template>
  <DsModal
    :open="Boolean(recordId)"
    title="测试邮件记录详情"
    description="图片加载与链接点击回传只表示相应请求到达，不能证明人工阅读或点击。"
    @close="emit('close')"
  >
    <div class="space-y-4">
      <div
        v-if="loading"
        class="space-y-3"
        aria-label="测试邮件记录详情加载中"
      >
        <DsSkeleton class="h-20" />
        <DsSkeleton class="h-28" />
      </div>
      <DsAlert
        v-else-if="error"
        tone="danger"
        title="详情不可用"
      >
        {{ error }}
      </DsAlert>
      <template v-else-if="record">
        <dl class="grid gap-3 rounded-lg bg-slate-50 p-4 text-sm sm:grid-cols-2">
          <div class="sm:col-span-2">
            <dt class="text-xs font-medium text-slate-500">
              记录 ID
            </dt>
            <dd class="mt-1 break-all font-mono text-xs text-slate-800">
              {{ record.id }}
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              收件人
            </dt>
            <dd class="mt-1 font-medium text-slate-800">
              {{ record.recipientMasked }}
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              来源
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ mailSendSourceLabel(record) }}
            </dd>
          </div>
          <div class="sm:col-span-2">
            <dt class="text-xs font-medium text-slate-500">
              主题
            </dt>
            <dd class="mt-1 break-words text-slate-800">
              {{ record.subject }}
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              SMTP 账户
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ record.smtpAccountName ?? '—' }}
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              发送结果
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ mailSendStatusLabel(record) }}
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              检测状态
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ mailTrackingState(record) }}
              <span
                v-if="mailTrackingCondition(record)"
                class="mt-1 block text-xs text-slate-500"
              >
                {{ mailTrackingCondition(record) }}
              </span>
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              检测到期时间
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ formatMailTrackingDate(record.trackingExpiresAt) }}
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              图片加载回传
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ record.rawOpenCount }} 次
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              图片可能自动化
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ record.automatedOpenCount }} 次
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              图片首次 / 最近回传
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ formatMailTrackingDate(record.firstOpenAt) }} / {{ formatMailTrackingDate(record.lastOpenAt) }}
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              链接点击回传
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ record.rawClickCount }} 次
            </dd>
          </div>
          <div>
            <dt class="text-xs font-medium text-slate-500">
              点击可能自动化
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ record.automatedClickCount }} 次
            </dd>
          </div>
          <div class="sm:col-span-2">
            <dt class="text-xs font-medium text-slate-500">
              点击首次 / 最近回传
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ formatMailTrackingDate(record.firstClickAt) }} / {{ formatMailTrackingDate(record.lastClickAt) }}
            </dd>
          </div>
          <div v-if="record.failureCategory">
            <dt class="text-xs font-medium text-slate-500">
              失败类别
            </dt>
            <dd class="mt-1 text-slate-800">
              {{ record.failureCategory }}
            </dd>
          </div>
        </dl>

        <section aria-labelledby="latest-callbacks-title">
          <div class="flex items-baseline justify-between gap-3">
            <h3
              id="latest-callbacks-title"
              class="text-sm font-semibold text-slate-900"
            >
              最近分类回传
            </h3>
            <span class="text-xs text-slate-500">最多显示 50 条</span>
          </div>
          <p
            v-if="latestEvents.length === 0"
            class="mt-2 text-sm text-slate-500"
          >
            尚无回传；这不是“未阅读”的结论。
          </p>
          <ol
            v-else
            class="mt-3 space-y-2"
          >
            <li
              v-for="event in latestEvents"
              :key="event.id"
              class="rounded-md border border-slate-200 p-3 text-sm"
            >
              <div class="flex flex-wrap items-center justify-between gap-2">
                <DsBadge :tone="classificationTone(event.classification)">
                  {{ classificationLabel(event.classification) }}
                </DsBadge>
                <time class="text-xs text-slate-500">{{ formatMailTrackingDate(event.occurredAt) }}</time>
              </div>
              <p class="mt-2 break-words text-xs/5 text-slate-600">
                {{ event.reason }}
              </p>
            </li>
          </ol>
        </section>

        <section
          aria-labelledby="click-callbacks-title"
          class="border-t border-slate-200 pt-4"
        >
          <div class="border-b border-slate-200 pb-3">
            <h3
              id="click-callbacks-title"
              class="text-sm font-semibold text-slate-900"
            >
              链接点击回传
            </h3>
            <p class="mt-1 text-xs/5 text-slate-500">
              仅表示安全重定向被请求；未分类事件也不等于人工点击。
            </p>
          </div>
          <p
            v-if="links.length === 0"
            class="mt-3 text-sm text-slate-500"
          >
            邮件中没有可检测的 HTTP(S) 链接。
          </p>
          <ol
            v-else
            class="mt-3 divide-y divide-slate-200 rounded-md border border-slate-200"
          >
            <li
              v-for="link in links"
              :key="link.id"
              class="p-3 text-sm"
            >
              <div class="flex flex-wrap items-start justify-between gap-2">
                <div class="min-w-0">
                  <p class="font-medium text-slate-900">
                    {{ link.label ?? `链接 ${link.position}` }}
                  </p>
                  <p class="mt-1 break-all text-xs/5 text-slate-500">
                    {{ link.targetUrl }}
                  </p>
                </div>
                <DsBadge :tone="link.rawClickCount > 0 ? 'info' : 'neutral'">
                  {{ link.rawClickCount }} 次
                </DsBadge>
              </div>
              <p
                v-if="link.automatedClickCount"
                class="mt-2 text-xs text-slate-500"
              >
                可能自动化：{{ link.automatedClickCount }}
              </p>
            </li>
          </ol>

          <div
            v-if="clickEvents.length"
            class="mt-4"
          >
            <h4 class="text-xs font-semibold uppercase tracking-wide text-slate-500">
              最近点击分类
            </h4>
            <ol class="mt-2 space-y-2">
              <li
                v-for="event in clickEvents"
                :key="event.id"
                class="rounded-md border border-slate-200 p-3 text-sm"
              >
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <DsBadge :tone="classificationTone(event.classification)">
                    {{ classificationLabel(event.classification) }}
                  </DsBadge>
                  <time class="text-xs text-slate-500">{{ formatMailTrackingDate(event.occurredAt) }}</time>
                </div>
                <p class="mt-2 break-all text-xs/5 text-slate-600">
                  {{ clickTarget(event.linkId) }}
                </p>
                <p class="mt-1 text-xs/5 text-slate-500">
                  {{ event.reason }}
                </p>
              </li>
            </ol>
          </div>
        </section>
      </template>
    </div>
    <template #actions>
      <DsButton
        variant="secondary"
        @click="emit('close')"
      >
        关闭
      </DsButton>
      <DsButton
        :busy="loading"
        :disabled="!recordId"
        @click="load()"
      >
        刷新回传
      </DsButton>
    </template>
  </DsModal>
</template>
