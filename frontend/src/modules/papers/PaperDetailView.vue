<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsTabs from '@/components/design-skill/DsTabs.vue'
import { papersApi, type PaperDetail } from './papers.api'

const route = useRoute(); const paper = ref<PaperDetail>(); const error = ref('')
const tabs = [{ value: 'metadata', label: '元数据' }, { value: 'authors', label: '作者' }, { value: 'versions', label: '版本与来源' }, { value: 'raw', label: '原始元数据' }]
onMounted(async () => { try { paper.value = await papersApi.get(String(route.params.id)) } catch { error.value = '论文详情加载失败' } })
</script>

<template>
  <div
    v-if="paper"
    class="space-y-6"
  >
    <header>
      <RouterLink
        to="/papers"
        class="text-sm font-medium text-brand-600"
      >
        ← 返回论文库
      </RouterLink><div class="mt-3 flex flex-wrap gap-2">
        <span
          v-for="category in paper.categories"
          :key="category.categoryId"
          class="rounded bg-brand-50 px-2 py-1 text-xs font-semibold text-brand-700"
        >{{ category.categoryId }}</span>
      </div><h1 class="mt-3 max-w-4xl text-2xl font-semibold text-slate-950">
        {{ paper.title }}
      </h1><p class="mt-2 font-mono text-xs text-slate-400">
        {{ paper.arxivId }} · {{ new Date(paper.updatedAt).toLocaleDateString('zh-CN') }}
      </p>
    </header>
    <DsTabs :tabs="tabs">
      <template #metadata>
        <DsCard class="space-y-5">
          <div>
            <h2 class="text-sm font-semibold text-slate-900">
              摘要
            </h2><p class="mt-2 whitespace-pre-line text-sm/7 text-slate-600">
              {{ paper.abstractText }}
            </p>
          </div><dl class="grid gap-4 text-sm sm:grid-cols-2">
            <div>
              <dt class="text-slate-400">
                DOI
              </dt><dd class="mt-1 text-slate-800">
                {{ paper.doi || '—' }}
              </dd>
            </div><div>
              <dt class="text-slate-400">
                期刊引用
              </dt><dd class="mt-1 text-slate-800">
                {{ paper.journalReference || '—' }}
              </dd>
            </div><div>
              <dt class="text-slate-400">
                来源状态
              </dt><dd class="mt-1 text-slate-800">
                {{ paper.sourceStatus }}
              </dd>
            </div><div>
              <dt class="text-slate-400">
                许可
              </dt><dd class="mt-1">
                <a
                  v-if="paper.licenseUrl"
                  :href="paper.licenseUrl"
                  target="_blank"
                  class="text-brand-600"
                >查看许可</a><span v-else>—</span>
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
              <p class="font-medium text-slate-900">
                {{ author.order }}. {{ author.name }}
              </p><p class="mt-1 text-sm text-slate-500">
                {{ author.affiliations.join(' · ') || '未提供机构' }}
              </p>
            </li>
          </ol>
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
                class="flex justify-between rounded bg-slate-50 p-3"
              >
                <span>v{{ version.version }}</span><span class="text-slate-500">{{ new Date(version.submittedAt).toLocaleString('zh-CN') }}</span>
              </li>
            </ul>
          </div><div>
            <h2 class="font-semibold text-slate-900">
              导入来源
            </h2><ul class="mt-3 space-y-2 text-sm">
              <li
                v-for="item in paper.imports"
                :key="item.jobId"
                class="rounded border border-slate-200 p-3"
              >
                <span class="font-medium">{{ item.metadataSource }}</span><span class="ml-3 font-mono text-xs text-slate-400">{{ item.jobId }}</span>
              </li>
            </ul>
          </div>
        </DsCard>
      </template>
      <template #raw>
        <DsCard><pre class="max-h-[36rem] overflow-auto whitespace-pre-wrap break-all text-xs/5 text-slate-600">{{ JSON.stringify(paper.rawMetadata, null, 2) }}</pre></DsCard>
      </template>
    </DsTabs>
  </div><p
    v-else-if="error"
    role="alert"
    class="rounded-md bg-red-50 p-3 text-sm text-red-700"
  >
    {{ error }}
  </p>
</template>
