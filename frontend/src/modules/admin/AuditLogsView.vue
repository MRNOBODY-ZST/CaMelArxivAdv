<script setup lang="ts">
import { ClipboardDocumentListIcon } from '@heroicons/vue/24/outline'
import { onMounted, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import DsTable from '@/components/design-skill/DsTable.vue'
import { adminApi, administrationErrorMessage, type AuditLogView, type AuditResult } from '@/modules/admin/admin.api'

const items = ref<AuditLogView[]>([])
const page = ref(1)
const total = ref(0)
const totalPages = ref(0)
const loading = ref(true)
const errorMessage = ref('')
const action = ref('')
const resource = ref('')
const result = ref('')

const resultOptions = [
  { label: '全部结果', value: '' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILURE' },
  { label: '拒绝', value: 'DENIED' },
]

onMounted(() => load())

async function load(targetPage = page.value): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await adminApi.listAuditLogs({
      page: targetPage, pageSize: 20, action: action.value,
      resource: resource.value, result: result.value,
    })
    items.value = response.items
    page.value = response.page
    total.value = response.total
    totalPages.value = response.totalPages
  } catch (error) {
    errorMessage.value = administrationErrorMessage(error, '没有权限读取审计日志。')
  } finally {
    loading.value = false
  }
}

function resultTone(value: AuditResult): 'positive' | 'danger' | 'warning' {
  if (value === 'SUCCESS') return 'positive'
  return value === 'DENIED' ? 'warning' : 'danger'
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value))
}

function safeSummary(summary: Record<string, unknown>): string {
  const safe = Object.fromEntries(Object.entries(summary)
    .filter(([key]) => !/(authorization|cookie|hash|jwt|password|secret|token)/i.test(key)))
  return Object.keys(safe).length === 0 ? '—' : JSON.stringify(safe)
}
</script>

<template>
  <div class="space-y-6">
    <div>
      <p class="text-xs font-semibold uppercase tracking-wider text-brand-600">
        系统管理
      </p>
      <h1 class="mt-1 text-2xl font-semibold tracking-tight text-slate-950">
        审计日志
      </h1>
      <p class="mt-2 text-sm/6 text-slate-500">
        追踪敏感操作的操作者、资源、结果和 Trace ID。密码、Token、Cookie 与密钥永不展示。
      </p>
    </div>

    <DsAlert
      v-if="errorMessage"
      tone="danger"
      title="审计日志加载失败"
    >
      {{ errorMessage }}
    </DsAlert>

    <DsCard>
      <form
        class="grid gap-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_10rem_auto]"
        @submit.prevent="load(1)"
      >
        <DsInput
          id="audit-action"
          v-model="action"
          placeholder="动作，例如 USER_DISABLED"
        />
        <DsInput
          id="audit-resource"
          v-model="resource"
          placeholder="资源类型或 ID"
        />
        <DsSelect
          id="audit-result"
          v-model="result"
          :options="resultOptions"
        />
        <DsButton
          data-testid="apply-audit-filters"
          type="submit"
          variant="secondary"
        >
          应用筛选
        </DsButton>
      </form>
      <p class="mt-3 text-xs text-slate-500">
        共 {{ total }} 条记录
      </p>
    </DsCard>

    <DsCard padding="none">
      <div
        v-if="loading"
        class="space-y-3 p-6"
      >
        <DsSkeleton
          v-for="index in 6"
          :key="index"
          class="h-14"
        />
      </div>
      <DsEmptyState
        v-else-if="items.length === 0"
        title="暂无审计记录"
        description="调整筛选条件，或等待敏感操作产生记录。"
      >
        <template #icon>
          <ClipboardDocumentListIcon class="size-8 text-slate-400" />
        </template>
      </DsEmptyState>
      <template v-else>
        <DsTable label="审计日志列表">
          <thead class="bg-slate-50 text-xs font-semibold text-slate-500">
            <tr>
              <th class="px-5 py-3">
                时间 / 操作者
              </th><th class="px-5 py-3">
                动作 / 资源
              </th><th class="px-5 py-3">
                变更摘要
              </th><th class="px-5 py-3">
                结果
              </th><th class="px-5 py-3">
                Trace ID
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr
              v-for="entry in items"
              :key="entry.id"
            >
              <td class="whitespace-nowrap px-5 py-4">
                <p class="text-slate-800">
                  {{ dateTime(entry.occurredAt) }}
                </p><p class="mt-1 text-xs text-slate-500">
                  {{ entry.actorUsername ?? '系统/未知' }}
                </p>
              </td>
              <td class="px-5 py-4">
                <p class="font-medium text-slate-900">
                  {{ entry.action }}
                </p><p class="mt-1 font-mono text-xs text-slate-500">
                  {{ entry.resourceType }} / {{ entry.resourceId ?? '—' }}
                </p>
              </td>
              <td class="max-w-md px-5 py-4 font-mono text-xs/5 text-slate-600">
                <p>前：{{ safeSummary(entry.beforeSummary) }}</p><p class="mt-1">
                  后：{{ safeSummary(entry.afterSummary) }}
                </p>
              </td>
              <td class="px-5 py-4">
                <DsBadge
                  :tone="resultTone(entry.result)"
                  dot
                >
                  {{ entry.result }}
                </DsBadge><p
                  v-if="entry.errorType"
                  class="mt-1 text-xs text-red-600"
                >
                  {{ entry.errorType }}
                </p>
              </td>
              <td class="px-5 py-4 font-mono text-xs text-slate-500">
                {{ entry.traceId }}
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
      </template>
    </DsCard>
  </div>
</template>
