<script setup lang="ts">
import { PaperAirplaneIcon, PlusIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { CampaignGenerationStatus, CampaignView, SegmentView } from '@/modules/campaigns/campaigns.types'
import { emailApi } from '@/modules/email/email.api'
import type { SmtpAccountView, TemplateView } from '@/modules/email/email.types'

const loading = ref(true)
const router = useRouter()
const dependencyLoading = ref(false)
const saving = ref(false)
const error = ref('')
const campaigns = ref<CampaignView[]>([])
const templates = ref<TemplateView[]>([])
const segments = ref<SegmentView[]>([])
const smtpAccounts = ref<SmtpAccountView[]>([])
const page = ref(1)
const totalPages = ref(0)
const createOpen = ref(false)
const form = ref({ name: '', purpose: '', templateId: '', segmentId: '', smtpAccountId: '' })

const activeTemplates = computed(() => templates.value.filter((item) => item.status === 'ACTIVE'))
const enabledSmtp = computed(() => smtpAccounts.value.filter((item) => item.enabled))
const dependenciesReady = computed(() => activeTemplates.value.length > 0 && segments.value.length > 0 && enabledSmtp.value.length > 0)

async function load(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await campaignsApi.listCampaigns(target)
    campaigns.value = result.items
    page.value = result.page
    totalPages.value = result.totalPages
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '邮件活动加载失败，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function openCreate(): Promise<void> {
  createOpen.value = true
  dependencyLoading.value = true
  error.value = ''
  try {
    const [templatePage, segmentPage, smtpPage] = await Promise.all([
      emailApi.listTemplates(1, 100), campaignsApi.listSegments(1, 100), emailApi.listSmtpAccounts(1, 100),
    ])
    templates.value = templatePage.items
    segments.value = segmentPage.items
    smtpAccounts.value = smtpPage.items
    form.value = {
      name: '', purpose: '', templateId: activeTemplates.value[0]?.id ?? '',
      segmentId: segments.value[0]?.id ?? '', smtpAccountId: enabledSmtp.value[0]?.id ?? '',
    }
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '创建活动所需配置加载失败。')
  } finally {
    dependencyLoading.value = false
  }
}

async function create(): Promise<void> {
  if (!form.value.name.trim() || !form.value.purpose.trim() || !form.value.templateId
      || !form.value.segmentId || !form.value.smtpAccountId) {
    error.value = '请填写活动名称、目标并选择全部依赖项。'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const created = await campaignsApi.createCampaign({ ...form.value, name: form.value.name.trim(), purpose: form.value.purpose.trim() })
    createOpen.value = false
    await load(1)
    await router.push(`/email/campaigns/${created.id}`)
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '邮件活动创建失败。')
  } finally {
    saving.value = false
  }
}

function statusLabel(status: CampaignGenerationStatus): string {
  return ({
    NOT_REQUESTED: '尚未生成', QUEUED: '等待生成', RUNNING: '生成中', COMPLETED: '生成完成',
    PARTIALLY_FAILED: '部分失败', FAILED: '生成失败',
  })[status]
}

function statusTone(status: CampaignGenerationStatus): 'neutral' | 'positive' | 'warning' | 'danger' | 'info' {
  if (status === 'COMPLETED') return 'positive'
  if (status === 'FAILED') return 'danger'
  if (status === 'PARTIALLY_FAILED') return 'warning'
  if (status === 'QUEUED' || status === 'RUNNING') return 'info'
  return 'neutral'
}

onMounted(load)
</script>

<template>
  <section
    data-testid="campaigns-view"
    aria-labelledby="campaigns-title"
    class="space-y-6"
  >
    <header class="gap-4 sm:flex sm:items-start sm:justify-between">
      <div>
        <h1
          id="campaigns-title"
          class="text-2xl font-semibold tracking-tight text-slate-950"
        >
          邮件活动
        </h1>
        <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">
          根据作者论文生成逐人草稿，并在人工审核后进入后续发送流程。
        </p>
      </div>
      <DsButton
        class="mt-4 shrink-0 sm:mt-0"
        @click="openCreate"
      >
        <PlusIcon class="size-4" />新建活动
      </DsButton>
    </header>
    <DsAlert
      v-if="error"
      tone="danger"
    >
      {{ error }}
    </DsAlert>
    <div
      v-if="loading"
      class="h-44 animate-pulse rounded-lg bg-slate-100"
      aria-label="活动加载中"
    />
    <div
      v-else-if="campaigns.length"
      class="grid gap-4 lg:grid-cols-2"
    >
      <DsCard
        v-for="campaign in campaigns"
        :key="campaign.id"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <h2 class="truncate font-semibold text-slate-900">
              {{ campaign.name }}
            </h2>
            <p class="mt-1 line-clamp-2 text-sm/6 text-slate-500">
              {{ campaign.purpose }}
            </p>
          </div>
          <DsBadge
            :tone="statusTone(campaign.generationStatus)"
            dot
          >
            {{ statusLabel(campaign.generationStatus) }}
          </DsBadge>
        </div>
        <dl class="mt-5 grid grid-cols-2 gap-3 text-sm">
          <div>
            <dt class="text-xs text-slate-500">
              模板
            </dt><dd class="mt-1 font-medium text-slate-800">
              {{ campaign.templateName }} · v{{ campaign.templateVersion }}
            </dd>
          </div>
          <div>
            <dt class="text-xs text-slate-500">
              分组
            </dt><dd class="mt-1 font-medium text-slate-800">
              {{ campaign.segmentName }}
            </dd>
          </div>
          <div>
            <dt class="text-xs text-slate-500">
              草稿
            </dt><dd class="mt-1 font-medium text-slate-800">
              {{ campaign.recipientCounts.generated }}
            </dd>
          </div>
          <div>
            <dt class="text-xs text-slate-500">
              失败
            </dt><dd class="mt-1 font-medium text-slate-800">
              {{ campaign.recipientCounts.failed }}
            </dd>
          </div>
        </dl>
        <RouterLink
          data-testid="campaign-detail-link"
          :to="`/email/campaigns/${campaign.id}`"
          class="mt-5 inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 hover:text-brand-800"
        >
          打开活动编辑器<span aria-hidden="true"> →</span>
        </RouterLink>
      </DsCard>
    </div>
    <DsCard
      v-else
      padding="none"
    >
      <DsEmptyState
        title="还没有邮件活动"
        description="活动会绑定已发布模板、收件人分组和已启用 SMTP 账户。"
      >
        <template #icon>
          <PaperAirplaneIcon class="size-9 text-slate-400" />
        </template>
        <template #actions>
          <DsButton @click="openCreate">
            创建第一个活动
          </DsButton>
        </template>
      </DsEmptyState>
    </DsCard>
    <DsPagination
      v-if="!loading && totalPages > 1"
      :page="page"
      :total-pages="totalPages"
      @change="load"
    />

    <DsModal
      :open="createOpen"
      title="新建邮件活动"
      description="创建后可生成逐位作者的草稿；此操作不会发送邮件。"
      @close="createOpen = false"
    >
      <div
        v-if="dependencyLoading"
        class="h-48 animate-pulse rounded-lg bg-slate-100"
      />
      <form
        v-else
        id="campaign-form"
        class="space-y-4"
        @submit.prevent="create"
      >
        <DsAlert
          v-if="!dependenciesReady"
          tone="warning"
          title="还不能创建活动"
        >
          需要至少一个已发布模板、一个收件人分组和一个已启用 SMTP 账户。
        </DsAlert>
        <DsInput
          id="campaign-name"
          v-model="form.name"
          label="活动名称"
          autocomplete="off"
        />
        <DsInput
          id="campaign-purpose"
          v-model="form.purpose"
          label="推送目的"
          description="生成服务会结合此目的与作者论文内容撰写邮件。"
          autocomplete="off"
        />
        <DsSelect
          id="campaign-template"
          v-model="form.templateId"
          label="已发布模板"
          :options="activeTemplates.map((item) => ({ label: `${item.name} · v${item.currentVersion}`, value: item.id }))"
        />
        <DsSelect
          id="campaign-segment"
          v-model="form.segmentId"
          label="收件人分组"
          :options="segments.map((item) => ({ label: `${item.name} · ${item.eligibleCount} 人`, value: item.id }))"
        />
        <DsSelect
          id="campaign-smtp"
          v-model="form.smtpAccountId"
          label="SMTP 账户"
          :options="enabledSmtp.map((item) => ({ label: item.name, value: item.id }))"
        />
      </form>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="createOpen = false"
        >
          取消
        </DsButton>
        <DsButton
          type="submit"
          form="campaign-form"
          :busy="saving"
          :disabled="!dependenciesReady"
        >
          创建活动
        </DsButton>
      </template>
    </DsModal>
  </section>
</template>
