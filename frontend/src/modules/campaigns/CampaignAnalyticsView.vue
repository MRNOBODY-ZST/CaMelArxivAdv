<script setup lang="ts">
import { ChartBarIcon } from '@heroicons/vue/24/outline'
</script>

<template>
  <section data-testid="campaign-analytics-view" aria-labelledby="campaign-analytics-title" class="space-y-6">
    <header>
      <h1 id="campaign-analytics-title" class="text-2xl font-semibold tracking-tight text-slate-950">活动分析</h1>
      <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">汇总真实活动收件人、SMTP 状态和经过分类的互动事件。</p>
    </header>
    <div class="rounded-xl border-2 border-dashed border-slate-200 bg-white px-6 py-12 text-center">
      <ChartBarIcon class="mx-auto size-10 text-slate-400" aria-hidden="true" />
      <h2 class="mt-3 text-sm font-semibold text-slate-900">暂无活动指标</h2>
      <p class="mt-1 text-sm text-slate-500">产生真实活动数据后才会显示图表。</p>
    </div>
  </section>
</template>
