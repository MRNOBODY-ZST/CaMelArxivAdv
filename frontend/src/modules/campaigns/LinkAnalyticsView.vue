<script setup lang="ts">
import { LinkIcon } from '@heroicons/vue/24/outline'
</script>

<template>
  <section data-testid="link-analytics-view" aria-labelledby="link-analytics-title" class="space-y-6">
    <header>
      <h1 id="link-analytics-title" class="text-2xl font-semibold tracking-tight text-slate-950">链接分析</h1>
      <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">查看活动链接点击，并区分真人、预取、机器人和安全扫描流量。</p>
    </header>
    <div class="rounded-xl border-2 border-dashed border-slate-200 bg-white px-6 py-12 text-center">
      <LinkIcon class="mx-auto size-10 text-slate-400" aria-hidden="true" />
      <h2 class="mt-3 text-sm font-semibold text-slate-900">暂无链接互动</h2>
      <p class="mt-1 text-sm text-slate-500">有真实点击事件后会显示明细。</p>
    </div>
  </section>
</template>
