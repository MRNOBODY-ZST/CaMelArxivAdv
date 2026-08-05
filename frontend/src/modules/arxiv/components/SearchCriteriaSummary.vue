<script setup lang="ts">
import type { SearchCriteria } from '../arxiv.types'
defineProps<{ criteria: SearchCriteria }>()
</script>

<template>
  <div class="flex flex-wrap gap-2 text-xs">
    <span
      v-for="category in criteria.categoryIds"
      :key="category"
      class="rounded-full bg-brand-50 px-2.5 py-1 font-medium text-brand-700"
    >{{ category }}</span>
    <span
      v-if="criteria.titleKeywords"
      class="rounded-full bg-slate-100 px-2.5 py-1 text-slate-700"
    >标题：{{ criteria.titleKeywords }}</span>
    <span
      v-if="criteria.authorKeywords"
      class="rounded-full bg-slate-100 px-2.5 py-1 text-slate-700"
    >作者：{{ criteria.authorKeywords }}</span>
    <span
      v-if="criteria.submittedFrom || criteria.submittedTo"
      class="rounded-full bg-slate-100 px-2.5 py-1 text-slate-700"
    >提交：{{ criteria.submittedFrom || '最早' }} — {{ criteria.submittedTo || '现在' }}</span>
  </div>
</template>
