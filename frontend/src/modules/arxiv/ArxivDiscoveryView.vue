<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ArrowDownTrayIcon, BookmarkIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline'
import axios from 'axios'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { ApiErrorResponse } from '@/modules/auth/auth.types'
import { arxivApi } from './arxiv.api'
import type { PreviewResult, SearchCriteriaRequest, TaxonomyResponse } from './arxiv.types'
import CategoryTree from './components/CategoryTree.vue'
import SearchCriteriaSummary from './components/SearchCriteriaSummary.vue'

const auth = useAuthStore()
const taxonomy = ref<TaxonomyResponse | null>(null)
const result = ref<PreviewResult | null>(null)
const selected = ref<string[]>([])
const loading = ref(true)
const searching = ref(false)
const acting = ref(false)
const error = ref('')
const notice = ref('')
const savedName = ref('')
const importCeiling = ref('500')
const criteria = reactive<SearchCriteriaRequest>({
  categoryIds: [], categoryMode: 'ANY', titleKeywords: '', abstractKeywords: '', authorKeywords: '',
  sortBy: 'RELEVANCE', sortOrder: 'DESCENDING', page: 1, pageSize: 20,
})
const canImport = computed(() => auth.hasPermission('paper:import'))

onMounted(async () => {
  try { taxonomy.value = await arxivApi.taxonomy() }
  catch (reason) { error.value = message(reason) }
  finally { loading.value = false }
})

async function preview(): Promise<void> {
  searching.value = true; error.value = ''; notice.value = ''; selected.value = []
  try { result.value = await arxivApi.preview({ ...criteria, categoryIds: [...criteria.categoryIds] }) }
  catch (reason) { error.value = message(reason) }
  finally { searching.value = false }
}

async function save(): Promise<void> {
  if (!savedName.value.trim()) { error.value = '请输入保存检索名称'; return }
  await action(async () => { await arxivApi.saveSearch(savedName.value.trim(), criteria); notice.value = '检索条件已保存' })
}

async function importSelected(): Promise<void> {
  await action(async () => {
    const response = await arxivApi.importSelected(selected.value)
    notice.value = response.created ? `导入任务 ${response.jobId.slice(0, 8)} 已创建` : '相同导入任务已存在'
  })
}

async function importAll(): Promise<void> {
  const ceiling = Number(importCeiling.value)
  if (!Number.isInteger(ceiling) || ceiling < 1) { error.value = '导入上限必须是正整数'; return }
  await action(async () => {
    const response = await arxivApi.importCriteria(criteria, ceiling)
    notice.value = response.created ? `批量导入任务 ${response.jobId.slice(0, 8)} 已创建` : '相同批量任务已存在'
  })
}

async function action(callback: () => Promise<void>): Promise<void> {
  acting.value = true; error.value = ''; notice.value = ''
  try { await callback() } catch (reason) { error.value = message(reason) } finally { acting.value = false }
}

function togglePaper(id: string, checked: boolean): void {
  selected.value = checked ? [...new Set([...selected.value, id])] : selected.value.filter((item) => item !== id)
}

function message(reason: unknown): string {
  return axios.isAxiosError<ApiErrorResponse>(reason) ? reason.response?.data.detail ?? '请求失败' : '请求失败，请稍后重试'
}
</script>

<template>
  <div class="space-y-6">
    <header class="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="text-sm font-medium text-brand-600">
          官方分类 · Legacy API 预览
        </p><h1 class="mt-1 text-2xl font-semibold tracking-tight text-slate-950">
          发现值得联系的研究者
        </h1><p class="mt-2 max-w-2xl text-sm text-slate-500">
          先用小规模官方查询验证条件，再创建可暂停、可恢复的导入任务。
        </p>
      </div>
      <p
        v-if="taxonomy"
        class="text-xs text-slate-400"
      >
        分类快照 {{ taxonomy.snapshotVersion }}
      </p>
    </header>
    <p
      v-if="error"
      role="alert"
      class="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700"
    >
      {{ error }}
    </p>
    <p
      v-if="notice"
      role="status"
      class="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-700"
    >
      {{ notice }}
    </p>
    <DsSkeleton
      v-if="loading"
      class="h-80"
    />
    <div
      v-else
      class="grid gap-6 xl:grid-cols-[22rem_minmax(0,1fr)]"
    >
      <DsCard class="h-fit space-y-5">
        <div>
          <h2 class="font-semibold text-slate-900">
            检索条件
          </h2><p class="mt-1 text-xs text-slate-500">
            分类和关键词由 arXiv 官方检索执行；DOI、来源等条件会标记为平台派生。
          </p>
        </div>
        <CategoryTree
          v-if="taxonomy"
          v-model="criteria.categoryIds"
          :groups="taxonomy.groups"
        />
        <DsInput
          id="title-keywords"
          v-model="criteria.titleKeywords"
          label="标题关键词"
          placeholder="例如 reliable agents"
        />
        <DsInput
          id="author-keywords"
          v-model="criteria.authorKeywords"
          label="作者关键词"
          placeholder="例如 Hinton"
        />
        <div class="grid grid-cols-2 gap-3">
          <label class="text-sm font-medium text-slate-900">提交起始<input
            v-model="criteria.submittedFrom"
            type="date"
            class="mt-2 min-h-11 w-full rounded-md border border-slate-300 px-3 text-sm"
          ></label><label class="text-sm font-medium text-slate-900">提交结束<input
            v-model="criteria.submittedTo"
            type="date"
            class="mt-2 min-h-11 w-full rounded-md border border-slate-300 px-3 text-sm"
          ></label>
        </div>
        <DsButton
          class="w-full"
          :busy="searching"
          :disabled="criteria.categoryIds.length === 0 && !criteria.titleKeywords && !criteria.authorKeywords"
          @click="preview"
        >
          <MagnifyingGlassIcon class="size-4" />预览结果
        </DsButton>
      </DsCard>
      <div class="min-w-0 space-y-5">
        <DsCard
          v-if="result"
          class="space-y-4"
        >
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h2 class="font-semibold text-slate-900">
                官方预览结果
              </h2><p class="mt-1 text-sm text-slate-500">
                约 {{ result.officialTotal.toLocaleString() }} 篇 · {{ result.cacheStatus === 'HIT' ? '缓存命中' : '实时查询' }}
              </p>
            </div><SearchCriteriaSummary :criteria="result.criteria" />
          </div>
          <div class="flex flex-col gap-3 rounded-md bg-slate-50 p-3 lg:flex-row lg:items-end">
            <DsInput
              id="saved-search-name"
              v-model="savedName"
              label="保存检索"
              placeholder="例如 AI Agents 周报"
              class="flex-1"
            />
            <DsButton
              variant="secondary"
              :busy="acting"
              :disabled="!canImport"
              @click="save"
            >
              <BookmarkIcon class="size-4" />保存
            </DsButton>
            <DsInput
              id="import-ceiling"
              v-model="importCeiling"
              label="全量导入上限"
              type="number"
              class="w-40"
            />
            <DsButton
              :busy="acting"
              :disabled="!canImport"
              @click="importAll"
            >
              <ArrowDownTrayIcon class="size-4" />按条件导入
            </DsButton>
          </div>
          <div class="overflow-x-auto rounded-md border border-slate-200">
            <table class="min-w-full divide-y divide-slate-200 text-left text-sm">
              <thead class="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th class="w-12 px-4 py-3">
                    <span class="sr-only">选择</span>
                  </th><th class="px-4 py-3">
                    论文
                  </th><th class="px-4 py-3">
                    分类
                  </th><th class="px-4 py-3">
                    更新
                  </th>
                </tr>
              </thead><tbody class="divide-y divide-slate-100 bg-white">
                <tr
                  v-for="paper in result.papers"
                  :key="paper.arxivId"
                  class="align-top"
                >
                  <td class="px-4 py-4">
                    <input
                      type="checkbox"
                      class="size-4 rounded border-slate-300"
                      :aria-label="`选择 ${paper.title}`"
                      :checked="selected.includes(paper.arxivId)"
                      @change="togglePaper(paper.arxivId, ($event.target as HTMLInputElement).checked)"
                    >
                  </td><td class="max-w-xl px-4 py-4">
                    <a
                      :href="paper.pdfUrl"
                      target="_blank"
                      rel="noreferrer"
                      class="font-semibold text-slate-900 hover:text-brand-600"
                    >{{ paper.title }}</a><p class="mt-1 line-clamp-2 text-xs/5 text-slate-500">
                      {{ paper.authors.map((item) => item.name).join(' · ') }}
                    </p><p class="mt-1 font-mono text-xs text-slate-400">
                      {{ paper.arxivId }} · {{ paper.versionCount }} 个版本
                    </p>
                  </td><td class="px-4 py-4">
                    <span class="rounded bg-brand-50 px-2 py-1 text-xs font-medium text-brand-700">{{ paper.primaryCategory }}</span>
                  </td><td class="whitespace-nowrap px-4 py-4 text-xs text-slate-500">
                    {{ new Date(paper.updatedAt).toLocaleDateString('zh-CN') }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="flex justify-end">
            <DsButton
              :disabled="!canImport || selected.length === 0"
              :busy="acting"
              @click="importSelected"
            >
              导入已选 {{ selected.length }} 篇
            </DsButton>
          </div>
        </DsCard>
        <DsEmptyState
          v-else
          title="先设置检索条件"
          description="选择一个或多个官方分类，或填写标题、作者关键词。"
        >
          <MagnifyingGlassIcon class="size-8" />
        </DsEmptyState>
      </div>
    </div>
  </div>
</template>
