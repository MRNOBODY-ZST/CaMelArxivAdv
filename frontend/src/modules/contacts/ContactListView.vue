<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { AtSymbolIcon, EyeIcon, ShieldCheckIcon } from '@heroicons/vue/24/outline'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import DsTable from '@/components/design-skill/DsTable.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { contactsApi, type ContactDetail, type ContactSummary } from './contacts.api'

const auth = useAuthStore()
const contacts = ref<ContactSummary[]>([])
const selected = ref<ContactDetail>()
const loading = ref(true); const detailLoading = ref(false); const error = ref(''); const total = ref(0); const totalPages = ref(0)
const query = reactive({ page: 1, pageSize: 20, domain: '', confidence: '', verificationStatus: '' })
const confidenceOptions = [
  { label: '全部置信度', value: '' }, { label: '高', value: 'HIGH' },
  { label: '中', value: 'MEDIUM' }, { label: '低', value: 'LOW' }, { label: '未映射', value: 'UNMAPPED' },
]
const verificationOptions = [
  { label: '全部验证状态', value: '' }, { label: '未验证', value: 'UNVERIFIED' },
  { label: '已确认', value: 'CONFIRMED' }, { label: '已驳回', value: 'REJECTED' },
]
const canReadFull = computed(() => auth.hasPermission('contact:read_full'))
const canVerify = computed(() => auth.hasPermission('contact:verify'))

onMounted(load)

async function load(page = query.page): Promise<void> {
  loading.value = true; error.value = ''
  const request: Parameters<typeof contactsApi.list>[0] = { page, pageSize: query.pageSize }
  if (query.domain) request.domain = query.domain
  if (query.confidence) request.confidence = query.confidence
  if (query.verificationStatus) request.verificationStatus = query.verificationStatus
  try {
    const data = await contactsApi.list(request)
    contacts.value = data.items; query.page = data.page; total.value = data.total; totalPages.value = data.totalPages
  } catch { error.value = '联系人加载失败' } finally { loading.value = false }
}

async function inspect(contact: ContactSummary, full: boolean): Promise<void> {
  detailLoading.value = true; error.value = ''
  try { selected.value = await contactsApi.get(contact.id, full) } catch { error.value = full ? '完整邮箱读取失败' : '联系人详情加载失败' } finally { detailLoading.value = false }
}

async function verify(status: 'CONFIRMED' | 'REJECTED'): Promise<void> {
  if (!selected.value) return
  detailLoading.value = true; error.value = ''
  try {
    selected.value = await contactsApi.verify(selected.value.id, {
      mappingId: selected.value.mappingId, expectedVersion: selected.value.version, status,
    })
    await load(query.page)
  } catch { error.value = '人工验证失败，数据可能已被其他用户更新' } finally { detailLoading.value = false }
}

function confidenceTone(value: string): 'positive' | 'warning' | 'danger' | 'neutral' {
  if (value === 'HIGH') return 'positive'
  if (value === 'MEDIUM') return 'warning'
  if (value === 'LOW') return 'danger'
  return 'neutral'
}
</script>

<template>
  <div class="space-y-6">
    <header>
      <p class="text-sm font-medium text-brand-600">
        隐私保护视图
      </p>
      <h1 class="mt-1 text-2xl font-semibold text-slate-950">
        作者与联系人
      </h1>
      <p class="mt-2 text-sm text-slate-500">
        列表始终显示脱敏邮箱。完整值仅在单条授权查看时短暂解密，并记录审计事件。
      </p>
    </header>

    <DsCard>
      <form
        class="grid gap-4 sm:grid-cols-2 lg:grid-cols-[1fr_12rem_12rem_auto] lg:items-end"
        @submit.prevent="load(1)"
      >
        <DsInput
          id="contact-domain"
          v-model="query.domain"
          label="Domain"
          placeholder="university.edu"
        />
        <DsSelect
          id="contact-confidence"
          v-model="query.confidence"
          label="置信度"
          :options="confidenceOptions"
        />
        <DsSelect
          id="contact-verification"
          v-model="query.verificationStatus"
          label="验证状态"
          :options="verificationOptions"
        />
        <DsButton
          type="submit"
          :busy="loading"
        >
          筛选
        </DsButton>
      </form>
    </DsCard>

    <p
      v-if="error"
      role="alert"
      class="rounded-md bg-red-50 p-3 text-sm text-red-700"
    >
      {{ error }}
    </p>

    <DsCard
      v-if="contacts.length"
      padding="none"
    >
      <div class="flex items-center justify-between border-b border-slate-200 px-5 py-4 text-sm text-slate-500">
        <span>共 {{ total.toLocaleString() }} 个唯一联系人</span>
        <span class="inline-flex items-center gap-1.5"><ShieldCheckIcon class="size-4 text-emerald-600" />AES-GCM 加密存储</span>
      </div>
      <DsTable label="联系人列表">
        <thead class="bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-500">
          <tr>
            <th class="px-5 py-3">
              邮箱 / 作者
            </th><th class="px-5 py-3">
              论文
            </th><th class="px-5 py-3">
              提取判断
            </th><th class="px-5 py-3">
              状态
            </th><th class="px-5 py-3 text-right">
              操作
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr
            v-for="contact in contacts"
            :key="contact.id"
            class="align-top"
          >
            <td class="px-5 py-4">
              <p class="font-mono text-sm font-medium text-slate-900">
                {{ contact.email }}
              </p><p class="mt-1 text-xs text-slate-500">
                {{ contact.authorName || '论文级联系人' }} · {{ contact.domain }}
              </p>
            </td>
            <td class="max-w-xs px-5 py-4">
              <RouterLink
                :to="`/papers/${contact.paperId}`"
                class="font-medium text-brand-600"
              >
                {{ contact.arxivId }}
              </RouterLink><p class="mt-1 truncate text-xs text-slate-500">
                {{ contact.paperTitle }}
              </p>
            </td>
            <td class="px-5 py-4">
              <DsBadge :tone="confidenceTone(contact.confidence)">
                {{ contact.confidence }}
              </DsBadge><p class="mt-2 text-xs text-slate-500">
                {{ contact.ruleName }}
              </p>
            </td>
            <td class="px-5 py-4">
              <DsBadge :tone="contact.verificationStatus === 'CONFIRMED' ? 'positive' : contact.verificationStatus === 'REJECTED' ? 'danger' : 'neutral'">
                {{ contact.verificationStatus }}
              </DsBadge><p
                v-if="contact.corresponding"
                class="mt-2 text-xs font-medium text-brand-600"
              >
                通讯作者
              </p>
            </td>
            <td class="px-5 py-4 text-right">
              <DsButton
                variant="ghost"
                size="sm"
                @click="inspect(contact, false)"
              >
                <EyeIcon class="size-4" />查看证据
              </DsButton>
            </td>
          </tr>
        </tbody>
      </DsTable>
      <div class="px-5 pb-5">
        <DsPagination
          v-if="totalPages > 1"
          :page="query.page"
          :total-pages="totalPages"
          @change="load"
        />
      </div>
    </DsCard>
    <DsEmptyState
      v-else-if="!loading"
      title="还没有联系人"
      description="先在论文库中启动 Source 解析任务。"
    >
      <AtSymbolIcon class="size-8" />
    </DsEmptyState>

    <DsModal
      :open="Boolean(selected)"
      title="联系人证据"
      description="证据上下文已截断并脱敏。"
      @close="selected = undefined"
    >
      <div
        v-if="selected"
        class="space-y-5"
      >
        <div class="rounded-lg bg-slate-50 p-4">
          <p class="font-mono text-base font-semibold text-slate-900">
            {{ selected.email }}
          </p><p class="mt-1 text-xs text-slate-500">
            {{ selected.authorName || '未映射作者' }} · {{ selected.arxivId }}
          </p>
        </div>
        <DsButton
          v-if="canReadFull && selected.email.includes('***')"
          variant="secondary"
          :busy="detailLoading"
          @click="inspect(selected, true)"
        >
          查看完整邮箱
        </DsButton>
        <ul class="space-y-3">
          <li
            v-for="(item, index) in selected.evidence"
            :key="`${item.sourceRelativePath}-${index}`"
            class="rounded-md border border-slate-200 p-3 text-sm"
          >
            <p class="font-medium text-slate-800">
              {{ item.ruleName }} · {{ item.sourceRelativePath }}<span v-if="item.lineNumber">:{{ item.lineNumber }}</span>
            </p><p class="mt-2 font-mono text-xs/5 text-slate-500">
              {{ item.maskedContext }}
            </p>
          </li>
        </ul>
        <div
          v-if="canVerify"
          class="flex flex-wrap gap-3 border-t border-slate-200 pt-4"
        >
          <DsButton
            :busy="detailLoading"
            @click="verify('CONFIRMED')"
          >
            确认有效
          </DsButton><DsButton
            variant="danger"
            :busy="detailLoading"
            @click="verify('REJECTED')"
          >
            标记无效
          </DsButton>
        </div>
      </div>
    </DsModal>
  </div>
</template>
