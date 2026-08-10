<script setup lang="ts">
import { PaperAirplaneIcon } from '@heroicons/vue/24/outline'
</script>

<template>
  <section data-testid="campaigns-view" aria-labelledby="campaigns-title" class="space-y-6">
    <header>
      <h1 id="campaigns-title" class="text-2xl font-semibold tracking-tight text-slate-950">邮件活动</h1>
      <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">根据作者论文生成逐人草稿，并在人工审核后进入后续发送流程。</p>
    </header>
    <div class="rounded-xl border-2 border-dashed border-slate-200 bg-white px-6 py-12 text-center">
      <PaperAirplaneIcon class="mx-auto size-10 text-slate-400" aria-hidden="true" />
      <h2 class="mt-3 text-sm font-semibold text-slate-900">还没有邮件活动</h2>
      <p class="mt-1 text-sm text-slate-500">活动会绑定模板、收件人分组和 SMTP 账户。</p>
    </div>
  </section>
</template>
