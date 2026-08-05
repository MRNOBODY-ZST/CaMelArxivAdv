<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ArchiveBoxIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import { papersApi, type PaperSummary } from './papers.api'

const papers = ref<PaperSummary[]>([]); const loading = ref(true); const error = ref(''); const total = ref(0); const totalPages = ref(0)
const query = reactive({ page: 1, pageSize: 20, category: '', title: '', author: '' })
onMounted(load)
async function load(page = query.page): Promise<void> {
  loading.value = true; error.value = ''
  const request: { page: number; pageSize: number; category?: string; title?: string; author?: string } = { page, pageSize: query.pageSize }
  if (query.category) request.category = query.category
  if (query.title) request.title = query.title
  if (query.author) request.author = query.author
  try { const data = await papersApi.list(request); papers.value = data.items; query.page = data.page; total.value = data.total; totalPages.value = data.totalPages } catch { error.value = '论文库加载失败' } finally { loading.value = false }
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
      <div class="border-b border-slate-200 px-5 py-4 text-sm text-slate-500">
        共 {{ total.toLocaleString() }} 篇
      </div><ul class="divide-y divide-slate-200">
        <li
          v-for="paper in papers"
          :key="paper.id"
          class="p-5 sm:p-6"
        >
          <RouterLink
            :to="`/papers/${paper.id}`"
            class="group block"
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
