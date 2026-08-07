<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ArchiveBoxIcon, ArrowPathIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { papersApi, type PaperSummary } from './papers.api'

const auth = useAuthStore()
const papers = ref<PaperSummary[]>([]); const loading = ref(true); const error = ref(''); const total = ref(0); const totalPages = ref(0)
const selectedIds = ref<string[]>([]); const batchLoading = ref(false); const submittedJobId = ref('')
const query = reactive({ page: 1, pageSize: 20, category: '', title: '', author: '' })
const canExtract = computed(() => auth.hasPermission('paper:import'))
const currentPaperIds = computed(() => papers.value.map((paper) => paper.id))
const allCurrentSelected = computed(() => currentPaperIds.value.length > 0
  && currentPaperIds.value.every((id) => selectedIds.value.includes(id)))
const someCurrentSelected = computed(() => currentPaperIds.value.some((id) => selectedIds.value.includes(id)))
onMounted(load)
async function load(page = query.page): Promise<void> {
  loading.value = true; error.value = ''
  const request: { page: number; pageSize: number; category?: string; title?: string; author?: string } = { page, pageSize: query.pageSize }
  if (query.category) request.category = query.category
  if (query.title) request.title = query.title
  if (query.author) request.author = query.author
  try { const data = await papersApi.list(request); papers.value = data.items; query.page = data.page; total.value = data.total; totalPages.value = data.totalPages } catch { error.value = '论文库加载失败' } finally { loading.value = false }
}

function toggle(paperId: string, selected: boolean): void {
  selectedIds.value = selected
    ? [...new Set([...selectedIds.value, paperId])].slice(0, 100)
    : selectedIds.value.filter((id) => id !== paperId)
}

function toggleCurrentPage(selected: boolean): void {
  const current = new Set(currentPaperIds.value)
  if (!selected) {
    selectedIds.value = selectedIds.value.filter((id) => !current.has(id))
    return
  }
  const available = Math.max(0, 100 - selectedIds.value.length)
  const additions = currentPaperIds.value
    .filter((id) => !selectedIds.value.includes(id))
    .slice(0, available)
  selectedIds.value = [...selectedIds.value, ...additions]
}

async function batchExtract(): Promise<void> {
  if (!selectedIds.value.length) return
  batchLoading.value = true; error.value = ''; submittedJobId.value = ''
  try {
    submittedJobId.value = (await papersApi.batchExtract(selectedIds.value)).jobId
    selectedIds.value = []
  } catch { error.value = '批量 Source 解析任务创建失败' } finally { batchLoading.value = false }
}
</script>

<template>
  <div class="space-y-6">
    <header>
      <p class="text-sm font-medium text-brand-600">
        规范化元数据
      </p><h1 class="mt-1 text-2xl font-semibold text-slate-950">
        论文库
      </h1><p class="mt-2 text-sm text-slate-500">
        按分类、标题和作者搜索已导入论文。全部筛选在服务端完成。
      </p>
    </header>
    <DsCard>
      <form
        class="grid gap-4 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_12rem_auto] lg:items-end"
        @submit.prevent="load(1)"
      >
        <DsInput
          id="paper-title"
          v-model="query.title"
          label="标题"
          placeholder="搜索标题"
        /><DsInput
          id="paper-author"
          v-model="query.author"
          label="作者"
          placeholder="搜索作者"
        /><DsInput
          id="paper-category"
          v-model="query.category"
          label="分类"
          placeholder="cs.AI"
        /><DsButton
          type="submit"
          :busy="loading"
        >
          <MagnifyingGlassIcon class="size-4" />筛选
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
      v-if="papers.length"
      padding="none"
    >
      <div class="flex flex-col gap-3 border-b border-slate-200 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div class="flex items-center gap-3 text-sm text-slate-500">
          <input
            v-if="canExtract"
            type="checkbox"
            class="size-4 min-h-4 min-w-4 shrink-0 rounded border-slate-300 text-brand-500 focus:ring-brand-500"
            aria-label="选择本页全部论文"
            :checked="allCurrentSelected"
            @change="toggleCurrentPage(($event.target as HTMLInputElement).checked)"
          >
          <span>共 {{ total.toLocaleString() }} 篇<span v-if="selectedIds.length"> · 已选 {{ selectedIds.length }} / 100 篇</span></span>
        </div>
        <div
          v-if="canExtract"
          class="flex flex-wrap items-center gap-2"
        >
          <RouterLink
            v-if="submittedJobId"
            :to="`/jobs/${submittedJobId}`"
            class="font-mono text-xs font-medium text-brand-600"
          >
            任务 {{ submittedJobId }} →
          </RouterLink>
          <DsButton
            variant="secondary"
            size="sm"
            :disabled="allCurrentSelected || selectedIds.length >= 100"
            @click="toggleCurrentPage(true)"
          >
            全选本页
          </DsButton>
          <DsButton
            variant="ghost"
            size="sm"
            :disabled="!someCurrentSelected"
            @click="toggleCurrentPage(false)"
          >
            清空本页
          </DsButton>
          <DsButton
            size="sm"
            :busy="batchLoading"
            :disabled="!selectedIds.length"
            @click="batchExtract"
          >
            <ArrowPathIcon class="size-4" />批量解析
          </DsButton>
        </div>
      </div><ul class="divide-y divide-slate-200">
        <li
          v-for="paper in papers"
          :key="paper.id"
          class="p-5 sm:p-6"
        >
          <div class="flex gap-4">
            <input
              v-if="canExtract"
              :id="`select-paper-${paper.id}`"
              type="checkbox"
              :checked="selectedIds.includes(paper.id)"
              class="mt-1 size-4 min-h-4 min-w-4 shrink-0 rounded border-slate-300 text-brand-500 focus:ring-brand-500"
              :aria-label="`选择论文 ${paper.arxivId}`"
              @change="toggle(paper.id, ($event.target as HTMLInputElement).checked)"
            >
            <RouterLink
              :to="`/papers/${paper.id}`"
              class="group min-w-0 flex-1"
            >
              <div class="flex flex-wrap items-center gap-2">
                <span class="rounded bg-brand-50 px-2 py-1 text-xs font-semibold text-brand-700">{{ paper.primaryCategory }}</span><span class="font-mono text-xs text-slate-400">{{ paper.arxivId }} · v{{ paper.versionCount }}</span>
              </div><h2 class="mt-3 text-base font-semibold text-slate-900 group-hover:text-brand-600">
                {{ paper.title }}
              </h2><p class="mt-2 text-sm text-slate-500">
                {{ paper.authors.join(' · ') }}
              </p><div class="mt-3 flex flex-wrap gap-4 text-xs text-slate-400">
                <span>更新 {{ new Date(paper.updatedAt).toLocaleDateString('zh-CN') }}</span><span v-if="paper.doi">DOI {{ paper.doi }}</span><span>{{ paper.sourceStatus }}</span>
              </div>
            </RouterLink>
          </div>
        </li>
      </ul><div class="px-5 pb-5">
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
      title="没有匹配的论文"
      description="调整筛选条件，或先从论文发现页创建导入任务。"
    >
      <ArchiveBoxIcon class="size-8" />
    </DsEmptyState>
  </div>
</template>
