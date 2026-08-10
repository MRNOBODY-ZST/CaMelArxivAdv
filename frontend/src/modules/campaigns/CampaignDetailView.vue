<script setup lang="ts">
import { SparklesIcon } from '@heroicons/vue/24/outline'
</script>

<template>
  <section data-testid="campaign-detail-view" aria-labelledby="campaign-detail-title" class="space-y-6">
    <header>
      <h1 id="campaign-detail-title" class="text-2xl font-semibold tracking-tight text-slate-950">活动编辑器</h1>
      <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">配置活动目标，生成并逐条审核个性化邮件草稿。</p>
    </header>
    <div class="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
      <div class="flex items-start gap-3">
        <span class="grid size-10 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-600">
          <SparklesIcon class="size-5" aria-hidden="true" />
        </span>
        <div>
          <h2 class="text-sm font-semibold text-slate-900">论文内容个性化</h2>
          <p class="mt-1 text-sm/6 text-slate-500">生成服务接入后，这里会显示每位作者的主题、HTML、纯文本和生成状态。</p>
        </div>
      </div>
    </div>
  </section>
</template>
