<script setup lang="ts">
import { UserGroupIcon } from '@heroicons/vue/24/outline'
</script>

<template>
  <section data-testid="segments-view" aria-labelledby="segments-title" class="space-y-6">
    <header class="sm:flex sm:items-start sm:justify-between">
      <div>
        <h1 id="segments-title" class="text-2xl font-semibold tracking-tight text-slate-950">收件人分组</h1>
        <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">用受控规则筛选已验证联系人，并在创建活动前预览实际可用人数。</p>
      </div>
    </header>
    <div class="rounded-xl border-2 border-dashed border-slate-200 bg-white px-6 py-12 text-center">
      <UserGroupIcon class="mx-auto size-10 text-slate-400" aria-hidden="true" />
      <h2 class="mt-3 text-sm font-semibold text-slate-900">还没有收件人分组</h2>
      <p class="mt-1 text-sm text-slate-500">新建分组后可按论文分类、置信度和联系人状态筛选。</p>
    </div>
  </section>
</template>
