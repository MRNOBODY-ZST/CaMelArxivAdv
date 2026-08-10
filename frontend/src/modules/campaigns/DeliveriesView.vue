<script setup lang="ts">
import { EnvelopeIcon } from '@heroicons/vue/24/outline'
</script>

<template>
  <section data-testid="deliveries-view" aria-labelledby="deliveries-title" class="space-y-6">
    <header>
      <h1 id="deliveries-title" class="text-2xl font-semibold tracking-tight text-slate-950">发送记录</h1>
      <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">查看 SMTP 尝试与服务器接受状态；服务器接受不代表最终送达。</p>
    </header>
    <div class="rounded-xl border-2 border-dashed border-slate-200 bg-white px-6 py-12 text-center">
      <EnvelopeIcon class="mx-auto size-10 text-slate-400" aria-hidden="true" />
      <h2 class="mt-3 text-sm font-semibold text-slate-900">暂无发送记录</h2>
      <p class="mt-1 text-sm text-slate-500">当前没有发生任何活动发送。</p>
    </div>
  </section>
</template>
