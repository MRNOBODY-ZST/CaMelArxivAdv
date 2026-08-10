<script setup lang="ts">
import { Cog6ToothIcon } from '@heroicons/vue/24/outline'
</script>

<template>
  <section data-testid="system-settings-view" aria-labelledby="system-settings-title" class="space-y-6">
    <header>
      <h1 id="system-settings-title" class="text-2xl font-semibold tracking-tight text-slate-950">系统设置</h1>
      <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">查看个性化生成、Ray、消息队列和 SMTP 安全开关，不显示任何密钥。</p>
    </header>
    <div class="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
      <div class="flex items-start gap-3">
        <span class="grid size-10 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-600">
          <Cog6ToothIcon class="size-5" aria-hidden="true" />
        </span>
        <div>
          <h2 class="text-sm font-semibold text-slate-900">运行配置</h2>
          <p class="mt-1 text-sm/6 text-slate-500">后端状态接口接入后，这里会报告已启用能力和非敏感运行信息。</p>
        </div>
      </div>
    </div>
  </section>
</template>
