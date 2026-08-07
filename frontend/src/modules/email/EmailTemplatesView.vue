<script setup lang="ts">
import { DocumentPlusIcon, DocumentTextIcon, EllipsisHorizontalIcon, PlusIcon } from '@heroicons/vue/24/outline'
import { onMounted, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { emailApi, emailErrorMessage } from '@/modules/email/email.api'
import type { TemplateView } from '@/modules/email/email.types'

const auth = useAuthStore()
const templates = ref<TemplateView[]>([])
const loading = ref(true)
const page = ref(1)
const total = ref(0)
const totalPages = ref(0)
const errorMessage = ref('')

onMounted(() => load())

async function load(targetPage = page.value): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await emailApi.listTemplates(targetPage, 20)
    templates.value = response.items
    page.value = response.page
    total.value = response.total
    totalPages.value = response.totalPages
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '邮件模板暂时无法加载。')
  } finally {
    loading.value = false
  }
}

function statusTone(status: TemplateView['status']): 'neutral' | 'positive' | 'warning' {
  if (status === 'ACTIVE') return 'positive'
  return status === 'DRAFT' ? 'warning' : 'neutral'
}

function statusLabel(status: TemplateView['status']): string {
  return { ACTIVE: '已启用', DRAFT: '草稿', ARCHIVED: '已归档' }[status]
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="text-xs font-semibold uppercase tracking-wider text-brand-600">
          邮件运营
        </p>
        <h1 class="mt-1 text-2xl font-semibold tracking-tight text-slate-950">
          邮件模板
        </h1>
        <p class="mt-2 max-w-2xl text-sm/6 text-slate-500">
          创建可审计、可回滚的邮件内容。每次保存都会生成不可变版本，危险 HTML 会在服务端移除。
        </p>
      </div>
      <RouterLink
        v-if="auth.hasPermission('template:manage')"
        to="/email/templates/new"
      >
        <DsButton><PlusIcon class="size-4" />新建模板</DsButton>
      </RouterLink>
    </div>

    <DsAlert
      v-if="errorMessage"
      tone="danger"
      title="模板加载失败"
    >
      {{ errorMessage }}
    </DsAlert>

    <div
      v-if="loading"
      class="grid gap-4 md:grid-cols-2 xl:grid-cols-3"
      data-testid="template-skeleton"
    >
      <DsSkeleton
        v-for="index in 6"
        :key="index"
        class="h-48"
      />
    </div>

    <DsCard
      v-else-if="templates.length === 0"
      data-testid="template-empty"
    >
      <DsEmptyState
        title="创建第一封邮件模板"
        description="从主题、正文和合规退订链接开始，发送前可先在 Mailpit 中预览。"
      >
        <template #icon>
          <DocumentPlusIcon class="size-6" />
        </template>
        <template #actions>
          <RouterLink
            v-if="auth.hasPermission('template:manage')"
            to="/email/templates/new"
          >
            <DsButton>创建模板</DsButton>
          </RouterLink>
        </template>
      </DsEmptyState>
    </DsCard>

    <template v-else>
      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        <RouterLink
          v-for="template in templates"
          :key="template.id"
          :to="`/email/templates/${template.id}`"
          class="group rounded-xl bg-white p-5 shadow-xs ring-1 ring-slate-200 hover:-translate-y-0.5 hover:shadow-md hover:ring-brand-200"
        >
          <div class="flex items-start justify-between gap-4">
            <span class="grid size-10 place-items-center rounded-lg bg-brand-50 text-brand-600">
              <DocumentTextIcon class="size-5" />
            </span>
            <EllipsisHorizontalIcon class="size-5 text-slate-300 group-hover:text-slate-500" />
          </div>
          <div class="mt-5 flex items-center gap-2">
            <DsBadge :tone="statusTone(template.status)">
              {{ statusLabel(template.status) }}
            </DsBadge>
            <span class="text-xs text-slate-400">v{{ template.currentVersion }}</span>
          </div>
          <h2 class="mt-3 truncate text-base font-semibold text-slate-900">
            {{ template.name }}
          </h2>
          <p class="mt-1 line-clamp-2 min-h-10 text-sm/5 text-slate-500">
            {{ template.description || template.subjectTemplate }}
          </p>
          <div class="mt-5 flex items-center justify-between border-t border-slate-100 pt-4 text-xs text-slate-400">
            <span>{{ template.contentSizeBytes.toLocaleString() }} B</span>
            <span>{{ dateTime(template.updatedAt) }}</span>
          </div>
        </RouterLink>
      </div>
      <div class="flex items-center justify-between">
        <p class="text-sm text-slate-500">
          共 {{ total }} 个模板
        </p>
        <DsPagination
          v-if="totalPages > 1"
          :page="page"
          :total-pages="totalPages"
          @change="load"
        />
      </div>
    </template>
  </div>
</template>
