<script setup lang="ts">
import { EnvelopeIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsTabs from '@/components/design-skill/DsTabs.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { DeliveryView } from '@/modules/campaigns/campaigns.types'
import MailSendRecordsPanel from '@/modules/email/MailSendRecordsPanel.vue'

const auth = useAuthStore()
const loading = ref(true)
const error = ref('')
const deliveries = ref<DeliveryView[]>([])
const page = ref(1)
const totalPages = ref(0)
const canReadRecords = computed(() => auth.hasPermission('smtp:read'))
const canReadCampaignDeliveries = computed(() => auth.hasPermission('campaign:read'))
const tabs = computed(() => [
  ...(canReadRecords.value ? [{ label: '测试邮件记录', value: 'records' }] : []),
  ...(canReadCampaignDeliveries.value ? [{ label: '活动发送记录', value: 'campaigns' }] : []),
])

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

function tone(status: string): 'neutral' | 'positive' | 'warning' | 'danger' | 'info' {
  if (status === 'SMTP_ACCEPTED' || status === 'SUCCEEDED') return 'positive'
  if (status === 'FAILED') return 'danger'
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
        测试邮件记录与活动投递分开显示；SMTP 接受不代表最终送达，图片加载回传也不代表人工阅读。
      </p>
    </header>
    <DsTabs
      v-if="tabs.length"
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
