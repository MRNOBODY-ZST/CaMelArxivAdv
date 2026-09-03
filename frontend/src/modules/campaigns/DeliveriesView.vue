<script setup lang="ts">
import { EnvelopeIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsTabs from '@/components/design-skill/DsTabs.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { DeliveryView, SafetyRunView } from '@/modules/campaigns/campaigns.types'
import MailSendRecordsPanel from '@/modules/email/MailSendRecordsPanel.vue'

const auth = useAuthStore()
const route = useRoute()
const loading = ref(true)
const error = ref('')
const deliveries = ref<DeliveryView[]>([])
const safetyRuns = ref<Array<SafetyRunView & { campaignName: string }>>([])
const safetyLoading = ref(false)
const safetyLoaded = ref(false)
const safetyError = ref('')
const page = ref(1)
const totalPages = ref(0)
const canReadRecords = computed(() => auth.hasPermission('smtp:read'))
const canReadCampaignDeliveries = computed(() => auth.hasPermission('campaign:read'))
type DeliveryTab = 'records' | 'safety' | 'campaigns'
const selectedTab = ref<DeliveryTab>(canReadRecords.value ? 'records' : 'campaigns')
const tabs = computed(() => [
  ...(canReadRecords.value ? [{ label: '测试邮件记录', value: 'records' }] : []),
  ...(canReadCampaignDeliveries.value ? [{ label: '安全实流', value: 'safety' }] : []),
  ...(canReadCampaignDeliveries.value ? [{ label: '活动发送记录', value: 'campaigns' }] : []),
])

watch(() => route.query.record, (record) => {
  if (typeof record === 'string' && canReadRecords.value) selectedTab.value = 'records'
})
watch(selectedTab, (tab) => {
  if (tab === 'safety' && !safetyLoaded.value) void loadSafetyRuns()
})

async function load(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await campaignsApi.listDeliveries(target)
    deliveries.value = result.items
    page.value = result.page
    totalPages.value = result.totalPages
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '发送记录加载失败。')
  } finally {
    loading.value = false
  }
}

async function loadSafetyRuns(): Promise<void> {
  safetyLoading.value = true
  safetyError.value = ''
  try {
    const campaignPage = await campaignsApi.listCampaigns(1, 20)
    const runGroups = await Promise.all(campaignPage.items.map(async (campaign) => ({
      campaignName: campaign.name,
      runs: await campaignsApi.listSafetyRuns(campaign.id),
    })))
    safetyRuns.value = runGroups.flatMap((group) => group.runs.map((run) => ({ ...run, campaignName: group.campaignName })))
    safetyLoaded.value = true
  } catch (cause) {
    safetyError.value = campaignErrorMessage(cause, '安全实流记录加载失败。')
  } finally {
    safetyLoading.value = false
  }
}

function tone(status: string): 'neutral' | 'positive' | 'warning' | 'danger' | 'info' {
  if (status === 'SMTP_ACCEPTED' || status === 'SUCCEEDED') return 'positive'
  if (status === 'FAILED' || status === 'PERMANENT_FAILURE' || status === 'BOUNCED') return 'danger'
  if (status === 'OUTCOME_UNKNOWN' || status === 'TEMPORARY_FAILURE' || status === 'PARTIALLY_FAILED') return 'warning'
  if (status === 'RUNNING' || status === 'QUEUED') return 'info'
  return 'neutral'
}

onMounted(() => {
  if (canReadCampaignDeliveries.value) void load()
  else loading.value = false
})
</script>

<template>
  <section
    data-testid="deliveries-view"
    aria-labelledby="deliveries-title"
    class="space-y-6"
  >
    <header>
      <h1
        id="deliveries-title"
        class="text-2xl font-semibold tracking-tight text-slate-950"
      >
        发送记录
      </h1>
      <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">
        测试邮件、安全实流与正式活动分开显示；SMTP 已接受不等于最终送达，回传不等于确认人工阅读。
      </p>
    </header>
    <DsTabs
      v-if="tabs.length"
      v-model:selected="selectedTab"
      :tabs="tabs"
    >
      <template
        v-if="canReadRecords"
        #records
      >
        <MailSendRecordsPanel />
      </template>
      <template
        v-if="canReadCampaignDeliveries"
        #safety
      >
        <section
          aria-labelledby="safety-deliveries-title"
          class="space-y-4"
        >
          <div>
            <h2
              id="safety-deliveries-title"
              class="text-base font-semibold text-slate-900"
            >
              安全实流证据
            </h2>
            <p class="mt-1 text-sm text-slate-500">
              邮件由真实 SMTP 发出，但服务端强制改投固定测试邮箱，不计入正式活动指标。
            </p>
          </div>
          <DsAlert
            v-if="safetyError"
            tone="danger"
          >
            {{ safetyError }}
          </DsAlert>
          <div
            v-if="safetyLoading"
            class="h-40 animate-pulse rounded-lg bg-slate-100"
            aria-label="安全实流加载中"
          />
          <div
            v-else-if="safetyRuns.length"
            class="grid gap-4 lg:grid-cols-2"
          >
            <DsCard
              v-for="run in safetyRuns"
              :key="run.id"
              padding="sm"
            >
              <div class="flex items-start justify-between gap-3">
                <div>
                  <p class="font-semibold text-slate-900">
                    {{ run.campaignName }}
                  </p>
                  <p class="mt-1 font-mono text-xs text-slate-500">
                    {{ run.destinationMasked }}
                  </p>
                </div>
                <DsBadge
                  :tone="tone(run.status)"
                  dot
                >
                  {{ run.status }}
                </DsBadge>
              </div>
              <dl class="mt-4 grid grid-cols-3 gap-3 text-sm">
                <div>
                  <dt class="text-xs text-slate-500">
                    SMTP 接受
                  </dt><dd class="mt-1 font-semibold text-slate-900">
                    {{ run.progress.smtpAccepted }}/{{ run.progress.total }}
                  </dd>
                </div>
                <div>
                  <dt class="text-xs text-slate-500">
                    回复
                  </dt><dd class="mt-1 font-semibold text-slate-900">
                    {{ run.events.reply }}
                  </dd>
                </div>
                <div>
                  <dt class="text-xs text-slate-500">
                    退信
                  </dt><dd class="mt-1 font-semibold text-slate-900">
                    {{ run.events.bounce }}
                  </dd>
                </div>
              </dl>
            </DsCard>
          </div>
          <DsCard
            v-else
            padding="none"
          >
            <DsEmptyState
              title="暂无安全实流"
              description="从具体活动的运营中心启动 1–20 封安全实流。"
            />
          </DsCard>
        </section>
      </template>
      <template
        v-if="canReadCampaignDeliveries"
        #campaigns
      >
        <section aria-labelledby="campaign-deliveries-title">
          <h2
            id="campaign-deliveries-title"
            class="sr-only"
          >
            活动发送记录
          </h2>
          <DsAlert
            v-if="error"
            tone="danger"
          >
            {{ error }}
          </DsAlert>
          <div
            v-if="loading"
            class="h-48 animate-pulse rounded-lg bg-slate-100"
            aria-label="活动发送记录加载中"
          />
          <DsCard
            v-else-if="deliveries.length"
            padding="none"
          >
            <div class="overflow-x-auto">
              <table class="min-w-full divide-y divide-slate-200 text-left text-sm">
                <thead class="bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-500">
                  <tr>
                    <th class="px-5 py-3">
                      活动 / 作者
                    </th><th class="px-5 py-3">
                      论文
                    </th><th class="px-5 py-3">
                      尝试
                    </th><th class="px-5 py-3">
                      状态
                    </th><th class="px-5 py-3">
                      完成时间
                    </th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-slate-100">
                  <tr
                    v-for="delivery in deliveries"
                    :key="delivery.id"
                  >
                    <td class="px-5 py-4">
                      <p class="font-medium text-slate-900">
                        {{ delivery.campaignName }}
                      </p><p class="mt-1 text-xs text-slate-500">
                        {{ delivery.authorName }}
                      </p>
                    </td>
                    <td class="max-w-80 px-5 py-4 text-slate-600">
                      {{ delivery.paperTitle }}
                    </td>
                    <td class="px-5 py-4 text-slate-600">
                      #{{ delivery.attemptNumber }}
                    </td>
                    <td class="px-5 py-4">
                      <DsBadge
                        :tone="tone(delivery.status)"
                        dot
                      >
                        {{ delivery.status }}
                      </DsBadge><p
                        v-if="delivery.smtpResponseCode"
                        class="mt-1 text-xs text-slate-500"
                      >
                        SMTP {{ delivery.smtpResponseCode }}
                      </p>
                      <p
                        v-if="delivery.failureCategory || delivery.retryable"
                        class="mt-1 text-xs text-slate-500"
                      >
                        {{ delivery.failureCategory ?? '暂时失败' }}<span v-if="delivery.retryable"> · 可重试</span>
                      </p>
                    </td>
                    <td class="whitespace-nowrap px-5 py-4 text-slate-500">
                      {{ delivery.completedAt ? new Date(delivery.completedAt).toLocaleString() : '进行中' }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
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
              title="暂无活动发送记录"
              description="当前没有发生任何活动发送。个性化草稿生成不会出现在这里。"
            >
              <template #icon>
                <EnvelopeIcon class="size-9 text-slate-400" />
              </template>
            </DsEmptyState>
          </DsCard>
        </section>
      </template>
    </DsTabs>
    <DsAlert
      v-else
      tone="warning"
      title="没有发送记录读取权限"
    >
      当前账号不具备 SMTP 或活动发送记录读取权限。
    </DsAlert>
  </section>
</template>
