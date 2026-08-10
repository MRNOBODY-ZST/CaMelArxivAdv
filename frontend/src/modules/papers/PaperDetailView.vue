<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowPathIcon, CheckCircleIcon, ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsTabs from '@/components/design-skill/DsTabs.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { contactsApi, type ContactSummary } from '@/modules/contacts/contacts.api'
import { papersApi, type PaperDetail } from './papers.api'

const route = useRoute(); const auth = useAuthStore()
const paper = ref<PaperDetail>(); const contacts = ref<ContactSummary[]>([]); const error = ref('')
const extracting = ref(false); const submittedJobId = ref('')
const tabs = [
  { value: 'metadata', label: '基础信息' }, { value: 'authors', label: '作者' },
  { value: 'contacts', label: '联系人' }, { value: 'categories', label: '分类' },
  { value: 'versions', label: '版本历史' }, { value: 'extractions', label: '提取记录' },
  { value: 'raw', label: '原始元数据' },
]
const canExtract = computed(() => auth.hasPermission('paper:import'))
const canReadContacts = computed(() => auth.hasPermission('contact:read_masked'))

onMounted(async () => {
  const id = String(route.params.id)
  try {
    paper.value = await papersApi.get(id)
    if (canReadContacts.value) {
      contacts.value = (await contactsApi.list({ page: 1, pageSize: 100, paperId: id })).items
    }
  } catch { error.value = '论文详情加载失败' }
})

async function extract(): Promise<void> {
  if (!paper.value) return
  extracting.value = true; error.value = ''; submittedJobId.value = ''
  try { submittedJobId.value = (await papersApi.extract(paper.value.id)).jobId } catch { error.value = 'Source 解析任务创建失败' } finally { extracting.value = false }
}

function bytes(value: number | null): string {
  if (value == null) return '—'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function statusTone(value: string): 'positive' | 'warning' | 'danger' | 'neutral' {
  if (value === 'SUCCEEDED') return 'positive'
  if (value === 'PARTIALLY_SUCCEEDED') return 'warning'
  if (['FAILED', 'SECURITY_REJECTED'].includes(value)) return 'danger'
  return 'neutral'
}
</script>

<template>
  <div
    v-if="paper"
    class="space-y-6"
  >
    <header class="flex flex-col gap-5 lg:flex-row lg:items-start lg:justify-between">
      <div>
        <RouterLink
          to="/papers"
          class="text-sm font-medium text-brand-600"
        >
          ← 返回论文库
        </RouterLink>
        <div class="mt-3 flex flex-wrap gap-2">
          <span
            v-for="category in paper.categories"
            :key="category.categoryId"
            class="rounded bg-brand-50 px-2 py-1 text-xs font-semibold text-brand-700"
          >{{ category.categoryId }}</span>
        </div>
        <h1 class="mt-3 max-w-4xl text-2xl font-semibold text-slate-950">
          {{ paper.title }}
        </h1>
        <p class="mt-2 font-mono text-xs text-slate-400">
          {{ paper.arxivId }} · {{ new Date(paper.updatedAt).toLocaleDateString('zh-CN') }}
        </p>
      </div>
      <div
        v-if="canExtract"
        class="shrink-0"
      >
        <DsButton
          :busy="extracting"
          @click="extract"
        >
          <ArrowPathIcon class="size-4" />开始 Source 解析
        </DsButton>
        <RouterLink
          v-if="submittedJobId"
          :to="`/jobs/${submittedJobId}`"
          class="mt-2 block text-right font-mono text-xs font-medium text-brand-600"
        >
          任务 {{ submittedJobId }} →
        </RouterLink>
      </div>
    </header>
    <p
      v-if="error"
      role="alert"
      class="rounded-md bg-red-50 p-3 text-sm text-red-700"
    >
      {{ error }}
    </p>

    <DsTabs :tabs="tabs">
      <template #metadata>
        <DsCard class="space-y-5">
          <div>
            <h2 class="text-sm font-semibold text-slate-900">
              摘要
            </h2><p class="mt-2 whitespace-pre-line text-sm/7 text-slate-600">
              {{ paper.abstractText }}
            </p>
          </div>
          <dl class="grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
            <div>
              <dt class="text-slate-400">
                DOI
              </dt><dd class="mt-1 text-slate-800">
                {{ paper.doi || '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-slate-400">
                期刊引用
              </dt><dd class="mt-1 text-slate-800">
                {{ paper.journalReference || '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-slate-400">
                Source 状态
              </dt><dd class="mt-1">
                <DsBadge :tone="paper.sourceStatus === 'PARSED' ? 'positive' : 'neutral'">
                  {{ paper.sourceStatus }}
                </DsBadge>
              </dd>
            </div>
            <div>
              <dt class="text-slate-400">
                Source 格式
              </dt><dd class="mt-1 text-slate-800">
                {{ paper.sourceFormat || '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-slate-400">
                许可
              </dt><dd class="mt-1">
                <a
                  v-if="paper.licenseUrl"
                  :href="paper.licenseUrl"
                  target="_blank"
                  rel="noreferrer"
                  class="text-brand-600"
                >查看许可</a><span v-else>—</span>
              </dd>
            </div>
            <div>
              <dt class="text-slate-400">
                PDF
              </dt><dd class="mt-1">
                <a
                  :href="paper.pdfUrl"
                  target="_blank"
                  rel="noreferrer"
                  class="text-brand-600"
                >打开 arXiv PDF</a>
              </dd>
            </div>
          </dl>
        </DsCard>
      </template>

      <template #authors>
        <DsCard>
          <ol class="divide-y divide-slate-100">
            <li
              v-for="author in paper.authors"
              :key="author.order"
              class="py-4 first:pt-0"
            >
              <div class="flex flex-wrap items-center gap-2">
                <p class="font-medium text-slate-900">
                  {{ author.order }}. {{ author.name }}
                </p><DsBadge
                  v-if="author.corresponding"
                  tone="info"
                >
                  通讯作者
                </DsBadge>
              </div><p class="mt-1 text-sm text-slate-500">
                {{ author.affiliations.join(' · ') || '未提供机构' }}
              </p>
            </li>
          </ol>
        </DsCard>
      </template>

      <template #contacts>
        <DsCard>
          <div
            v-if="!canReadContacts"
            class="py-10 text-center text-sm text-slate-500"
          >
            当前账号没有查看脱敏联系人的权限。
          </div>
          <div
            v-else-if="!contacts.length"
            class="py-10 text-center text-sm text-slate-500"
          >
            尚未提取到联系人。
          </div>
          <ul
            v-else
            class="divide-y divide-slate-100"
          >
            <li
              v-for="contact in contacts"
              :key="contact.id"
              class="flex flex-col gap-3 py-4 first:pt-0 sm:flex-row sm:items-center sm:justify-between"
            >
              <div>
                <p class="font-mono font-medium text-slate-900">
                  {{ contact.email }}
                </p><p class="mt-1 text-sm text-slate-500">
                  {{ contact.authorName || '论文级联系人' }} · {{ contact.ruleName }}
                </p>
              </div><div class="flex items-center gap-2">
                <DsBadge :tone="contact.confidence === 'HIGH' ? 'positive' : 'warning'">
                  {{ contact.confidence }}
                </DsBadge><RouterLink
                  to="/contacts"
                  class="text-sm font-medium text-brand-600"
                >
                  查看证据
                </RouterLink>
              </div>
            </li>
          </ul>
        </DsCard>
      </template>

      <template #categories>
        <DsCard>
          <ul class="grid gap-3 sm:grid-cols-2">
            <li
              v-for="category in paper.categories"
              :key="category.categoryId"
              class="rounded-lg border border-slate-200 p-4"
            >
              <div class="flex items-center justify-between gap-3">
                <p class="font-mono text-sm font-semibold text-brand-700">
                  {{ category.categoryId }}
                </p><DsBadge>{{ category.relationType }}</DsBadge>
              </div><p class="mt-2 text-sm text-slate-600">
                {{ category.categoryName }}
              </p>
            </li>
          </ul>
        </DsCard>
      </template>

      <template #versions>
        <DsCard class="space-y-6">
          <div>
            <h2 class="font-semibold text-slate-900">
              版本
            </h2><ul class="mt-3 space-y-2 text-sm">
              <li
                v-for="version in paper.versions"
                :key="version.version"
                class="grid gap-2 rounded bg-slate-50 p-3 sm:grid-cols-3"
              >
                <span class="font-medium">v{{ version.version }}</span><span class="text-slate-500">{{ new Date(version.submittedAt).toLocaleString('zh-CN') }}</span><span class="text-slate-500">{{ bytes(version.sizeBytes) }} · {{ version.sourceFormat || '未知格式' }}</span>
              </li>
            </ul>
          </div>
          <div>
            <h2 class="font-semibold text-slate-900">
              导入来源
            </h2><ul class="mt-3 space-y-2 text-sm">
              <li
                v-for="item in paper.imports"
                :key="item.jobId"
                class="rounded border border-slate-200 p-3"
              >
                <span class="font-medium">{{ item.metadataSource }}</span><RouterLink
                  :to="`/jobs/${item.jobId}`"
                  class="ml-3 font-mono text-xs text-brand-600"
                >
                  {{ item.jobId }}
                </RouterLink>
              </li><li
                v-if="!paper.imports.length"
                class="text-slate-500"
              >
                无导入记录
              </li>
            </ul>
          </div>
        </DsCard>
      </template>

      <template #extractions>
        <div class="space-y-4">
          <DsCard
            v-for="run in paper.extractionRuns"
            :key="run.id"
          >
            <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
              <div>
                <div class="flex flex-wrap items-center gap-2">
                  <DsBadge
                    :tone="statusTone(run.status)"
                    dot
                  >
                    {{ run.status }}
                  </DsBadge><span class="font-mono text-xs text-slate-400">parser {{ run.parserVersion }}</span>
                </div><p class="mt-3 text-sm text-slate-500">
                  {{ new Date(run.startedAt).toLocaleString('zh-CN') }} · {{ run.documentClass || '未知 document class' }}
                </p>
              </div><RouterLink
                v-if="run.jobId"
                :to="`/jobs/${run.jobId}`"
                class="font-mono text-xs font-medium text-brand-600"
              >
                查看任务 {{ run.jobId }}
              </RouterLink>
            </div>
            <dl class="mt-5 grid gap-4 border-t border-slate-100 pt-5 text-sm sm:grid-cols-2 lg:grid-cols-5">
              <div>
                <dt class="text-slate-400">
                  检查文件
                </dt><dd class="mt-1 font-semibold text-slate-800">
                  {{ run.filesInspected }}
                </dd>
              </div><div>
                <dt class="text-slate-400">
                  联系人
                </dt><dd class="mt-1 font-semibold text-slate-800">
                  {{ run.contactsFound }}
                </dd>
              </div><div>
                <dt class="text-slate-400">
                  归档 / 解包
                </dt><dd class="mt-1 text-slate-800">
                  {{ bytes(run.archiveSizeBytes) }} / {{ bytes(run.extractedSizeBytes) }}
                </dd>
              </div><div>
                <dt class="text-slate-400">
                  耗时
                </dt><dd class="mt-1 text-slate-800">
                  {{ run.durationMs == null ? '—' : `${run.durationMs} ms` }}
                </dd>
              </div><div>
                <dt class="text-slate-400">
                  临时目录
                </dt><dd
                  class="mt-1 inline-flex items-center gap-1.5"
                  :class="run.cleanupConfirmed ? 'text-emerald-700' : 'text-amber-700'"
                >
                  <CheckCircleIcon
                    v-if="run.cleanupConfirmed"
                    class="size-4"
                  /><ExclamationTriangleIcon
                    v-else
                    class="size-4"
                  />{{ run.cleanupConfirmed ? '临时文件已清理' : '清理未确认' }}
                </dd>
              </div>
            </dl>
            <p
              v-if="run.errorSummary"
              class="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-700"
            >
              {{ run.errorCode }} · {{ run.errorSummary }}
            </p>
          </DsCard>
          <DsCard
            v-if="!paper.extractionRuns.length"
            class="py-10 text-center text-sm text-slate-500"
          >
            还没有 Source 提取记录。
          </DsCard>
        </div>
      </template>

      <template #raw>
        <DsCard><pre class="max-h-[36rem] overflow-auto whitespace-pre-wrap break-all text-xs/5 text-slate-600">{{ JSON.stringify(paper.rawMetadata, null, 2) }}</pre></DsCard>
      </template>
    </DsTabs>
  </div>
  <p
    v-else-if="error"
    role="alert"
    class="rounded-md bg-red-50 p-3 text-sm text-red-700"
  >
    {{ error }}
  </p>
</template>
