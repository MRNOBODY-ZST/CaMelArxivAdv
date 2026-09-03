<script setup lang="ts">
import { LinkIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { LinkAnalyticsView as LinkMetric } from '@/modules/campaigns/campaigns.types'

const loading = ref(true)
const error = ref('')
const links = ref<LinkMetric[]>([])
const page = ref(1)
const totalPages = ref(0)
const totals = computed(() => links.value.reduce((sum, link) => ({
  raw: sum.raw + link.rawClicks,
  human: sum.human + link.humanClicks,
  automated: sum.automated + link.automatedClicks,
}), { raw: 0, human: 0, automated: 0 }))

async function load(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await campaignsApi.listLinkAnalytics(target)
    links.value = result.items
    page.value = result.page
    totalPages.value = result.totalPages
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '链接指标加载失败。')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section
    data-testid="link-analytics-view"
    aria-labelledby="link-analytics-title"
    class="space-y-6"
  >
    <header>
      <h1
        id="link-analytics-title"
        class="text-2xl font-semibold tracking-tight text-slate-950"
      >
        链接分析
      </h1><p class="mt-2 max-w-2xl text-sm/6 text-slate-600">
        查看正式活动链接点击，并区分原始、可能人工、预取、机器人和安全扫描流量；回传不等于确认人工阅读。
      </p>
    </header>
    <DsAlert
      v-if="error"
      tone="danger"
    >
      {{ error }}
    </DsAlert>
    <div
      v-if="loading"
      class="h-48 animate-pulse rounded-lg bg-slate-100"
      aria-label="链接指标加载中"
    />
    <DsCard
      v-else-if="links.length"
      padding="none"
    >
      <dl class="grid grid-cols-3 divide-x divide-slate-200 border-b border-slate-200 bg-slate-50">
        <div class="px-5 py-4">
          <dt class="text-xs text-slate-500">
            原始点击
          </dt><dd class="mt-1 text-2xl font-semibold text-slate-950">
            {{ totals.raw }}
          </dd>
        </div>
        <div class="px-5 py-4">
          <dt class="text-xs text-slate-500">
            可能人工
          </dt><dd class="mt-1 text-2xl font-semibold text-brand-700">
            {{ totals.human }}
          </dd>
        </div>
        <div class="px-5 py-4">
          <dt class="text-xs text-slate-500">
            自动化
          </dt><dd class="mt-1 text-2xl font-semibold text-slate-600">
            {{ totals.automated }}
          </dd>
        </div>
      </dl>
      <div class="overflow-x-auto">
        <table class="min-w-full divide-y divide-slate-200 text-left text-sm">
          <thead class="bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-500">
            <tr>
              <th class="px-5 py-3">
                活动
              </th><th class="px-5 py-3">
                链接
              </th><th class="px-5 py-3">
                原始点击
              </th><th class="px-5 py-3">
                可能人工点击
              </th><th class="px-5 py-3">
                自动化点击
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr
              v-for="link in links"
              :key="link.id"
            >
              <td class="px-5 py-4 font-medium text-slate-900">
                {{ link.campaignName }}
              </td>
              <td class="max-w-lg px-5 py-4">
                <p class="font-medium text-slate-800">
                  {{ link.label }}
                </p><p
                  class="mt-1 truncate text-xs text-slate-500"
                  :title="link.targetUrl"
                >
                  {{ link.targetUrl }}
                </p>
              </td>
              <td class="px-5 py-4 font-semibold text-emerald-700">
                {{ link.rawClicks }}
              </td>
              <td class="px-5 py-4 font-semibold text-brand-700">
                {{ link.humanClicks }}
              </td>
              <td class="px-5 py-4 text-slate-600">
                {{ link.automatedClicks }}
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
        title="暂无链接互动"
        description="有真实点击事件后会显示明细；自动流量会单独列出。"
      >
        <template #icon>
          <LinkIcon class="size-9 text-slate-400" />
        </template>
      </DsEmptyState>
    </DsCard>
  </section>
</template>
