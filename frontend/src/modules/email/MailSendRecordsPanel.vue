<script setup lang="ts">
import { ArrowPathIcon, EnvelopeIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import DsTable from '@/components/design-skill/DsTable.vue'
import { mailTrackingApi, mailTrackingErrorMessage } from '@/modules/email/mail-tracking.api'
import {
  formatMailTrackingDate,
  isMailTrackingExpired,
  mailSendSourceLabel,
  mailSendStatusLabel,
  mailTrackingState,
} from '@/modules/email/mail-tracking.presentation'
import type { MailSendRecord, MailTrackingStatus } from '@/modules/email/mail-tracking.types'
import MailSendRecordDialog from '@/modules/email/MailSendRecordDialog.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const configError = ref('')
const records = ref<MailSendRecord[]>([])
const status = ref<MailTrackingStatus | null>(null)
const page = ref(1)
const totalPages = ref(0)
const selectedRecordId = computed(() => typeof route.query.record === 'string' ? route.query.record : null)

onMounted(() => {
  void Promise.all([load(), loadStatus()])
})

async function load(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await mailTrackingApi.listSendRecords(target)
    records.value = result.items
    page.value = result.page
    totalPages.value = result.totalPages
  } catch (cause) {
    error.value = mailTrackingErrorMessage(cause, '测试邮件记录加载失败。')
  } finally {
    loading.value = false
  }
}

async function loadStatus(): Promise<void> {
  configError.value = ''
  try {
    status.value = await mailTrackingApi.getStatus()
  } catch (cause) {
    status.value = null
    configError.value = mailTrackingErrorMessage(cause, '图片加载检测配置暂时无法加载。')
  }
}

async function refresh(): Promise<void> {
  refreshing.value = true
  try {
    await Promise.all([load(page.value), loadStatus()])
  } finally {
    refreshing.value = false
  }
}

function openRecord(id: string): void {
  void router.replace({ path: route.path, query: { ...route.query, record: id } })
}

function closeRecord(): void {
  const query = { ...route.query }
  delete query.record
  void router.replace({ path: route.path, query })
}

function callbackOrigin(value: string): string {
  try {
    return new globalThis.URL(value).origin
  } catch {
    return value
  }
}

function sendStatusTone(value: MailSendRecord): 'neutral' | 'positive' | 'warning' | 'danger' | 'info' {
  if (value.status === 'SMTP_ACCEPTED') return 'positive'
  if (value.status === 'FAILED') return 'danger'
  if (value.status === 'SENDING') return 'info'
  return 'warning'
}

function trackingTone(value: MailSendRecord): 'neutral' | 'positive' | 'warning' | 'danger' | 'info' {
  if (!value.trackingEnabled) return 'neutral'
  if (value.status === 'FAILED') return 'danger'
  if (value.status === 'UNKNOWN' || isMailTrackingExpired(value)) return 'warning'
  return value.rawOpenCount > 0 ? 'info' : 'neutral'
}
</script>

<template>
  <section
    aria-labelledby="mail-send-records-title"
    class="space-y-5"
  >
    <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h2
          id="mail-send-records-title"
          class="text-lg font-semibold text-slate-950"
        >
          测试邮件记录
        </h2>
        <p class="mt-1 max-w-3xl text-sm/6 text-slate-600">
          SMTP 诊断和模板测试与活动投递分开记录。图片加载回传仅是估算，不代表人工阅读。
        </p>
      </div>
      <DsButton
        aria-label="刷新测试邮件记录"
        variant="secondary"
        :busy="refreshing"
        @click="refresh"
      >
        <ArrowPathIcon class="size-4" />刷新
      </DsButton>
    </div>

    <DsAlert
      v-if="configError"
      tone="warning"
      title="图片加载检测配置不可用"
    >
      {{ configError }}
    </DsAlert>
    <DsAlert
      v-else-if="status && !status.enabled"
      tone="warning"
      title="图片加载检测当前未启用"
    >
      测试邮件仍可不检测发送；历史记录不会凭此产生新的回传。
    </DsAlert>
    <DsAlert
      v-else-if="status?.callbackScope === 'LOCAL_ONLY'"
      tone="warning"
      title="回传仅限本机或私有网络"
    >
      回传来源：{{ callbackOrigin(status.callbackBaseUrl) }}。外部收件箱通常无法回传；该状态不代表公网可达。
    </DsAlert>
    <DsAlert
      v-else-if="status"
      tone="warning"
      title="公网 HTTPS 回传尚未验证"
    >
      回传来源：{{ callbackOrigin(status.callbackBaseUrl) }}。这是配置值，尚未验证公网可达性或邮件客户端行为。
    </DsAlert>

    <div
      v-if="loading"
      class="space-y-3"
      aria-label="测试邮件记录加载中"
    >
      <DsSkeleton class="h-16" />
      <DsSkeleton class="h-16" />
      <DsSkeleton class="h-16" />
    </div>
    <DsAlert
      v-else-if="error"
      tone="danger"
      title="测试邮件记录不可用"
    >
      {{ error }}
    </DsAlert>
    <DsCard
      v-else-if="records.length"
      padding="none"
    >
      <DsTable label="测试邮件记录">
        <thead class="bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-500">
          <tr>
            <th class="px-5 py-3">
              收件人 / 主题
            </th>
            <th class="hidden px-5 py-3 md:table-cell">
              来源 / 账户
            </th>
            <th class="px-5 py-3">
              发送结果
            </th>
            <th class="px-5 py-3">
              图片加载状态
            </th>
            <th class="hidden px-5 py-3 lg:table-cell">
              完成时间
            </th>
            <th class="px-5 py-3">
              <span class="sr-only">查看详情</span>
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100 bg-white">
          <tr
            v-for="item in records"
            :key="item.id"
          >
            <td class="max-w-xs px-5 py-4">
              <p class="font-medium text-slate-900">
                {{ item.recipientMasked }}
              </p>
              <p class="mt-1 truncate text-xs text-slate-500">
                {{ item.subject }}
              </p>
              <p class="mt-1 text-xs text-slate-400 md:hidden">
                {{ mailSendSourceLabel(item) }} · {{ item.smtpAccountName ?? '—' }}
              </p>
            </td>
            <td class="hidden px-5 py-4 text-sm text-slate-600 md:table-cell">
              <p>{{ mailSendSourceLabel(item) }}</p>
              <p class="mt-1 text-xs text-slate-500">
                {{ item.smtpAccountName ?? '—' }}
              </p>
            </td>
            <td class="px-5 py-4">
              <DsBadge :tone="sendStatusTone(item)">
                {{ mailSendStatusLabel(item) }}
              </DsBadge>
              <p
                v-if="item.failureCategory"
                class="mt-1 text-xs text-red-600"
              >
                {{ item.failureCategory }}
              </p>
            </td>
            <td class="px-5 py-4">
              <DsBadge :tone="trackingTone(item)">
                {{ mailTrackingState(item) }}
              </DsBadge>
              <p
                v-if="item.trackingEnabled && item.automatedOpenCount"
                class="mt-1 text-xs text-slate-500"
              >
                可能自动化：{{ item.automatedOpenCount }}
              </p>
            </td>
            <td class="hidden whitespace-nowrap px-5 py-4 text-sm text-slate-500 lg:table-cell">
              {{ formatMailTrackingDate(item.completedAt) }}
            </td>
            <td class="px-5 py-4 text-right">
              <button
                type="button"
                class="min-h-11 rounded-md px-3 text-sm font-medium text-brand-700 hover:bg-brand-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-600"
                :aria-label="`查看 ${item.recipientMasked} 的测试邮件记录`"
                @click="openRecord(item.id)"
              >
                查看
              </button>
            </td>
          </tr>
        </tbody>
      </DsTable>
      <div
        v-if="totalPages > 1"
        class="px-5 pb-5"
      >
        <DsPagination
          :page="page"
          :total-pages="totalPages"
          @change="load"
        />
      </div>
    </DsCard>
    <DsCard
      v-else
      padding="none"
    >
      <DsEmptyState
        title="暂无测试邮件记录"
        description="SMTP 诊断和模板测试发送后会在这里单独显示，不会混入活动投递。功能启用前发送的旧邮件无法补加图片加载检测。"
      >
        <template #icon>
          <EnvelopeIcon class="size-9 text-slate-400" />
        </template>
      </DsEmptyState>
    </DsCard>

    <MailSendRecordDialog
      :record-id="selectedRecordId"
      @close="closeRecord"
    />
  </section>
</template>
