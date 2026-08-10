<script setup lang="ts">
import { UserGroupIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { EligibleContactPreview, SegmentRule, SegmentView } from '@/modules/campaigns/campaigns.types'

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const segments = ref<SegmentView[]>([])
const page = ref(1)
const totalPages = ref(0)
const createOpen = ref(false)
const previewCount = ref<number | null>(null)
const previewSample = ref<EligibleContactPreview[]>([])
const form = ref({
  name: '', description: '', category: '', confidence: '', verificationStatus: '', corresponding: '',
})

const rules = computed<SegmentRule[]>(() => {
  const next: SegmentRule[] = []
  if (form.value.category.trim()) next.push({ field: 'primaryCategory', operator: 'equals', value: form.value.category.trim() })
  if (form.value.confidence) next.push({ field: 'confidence', operator: 'equals', value: form.value.confidence })
  if (form.value.verificationStatus) next.push({ field: 'verificationStatus', operator: 'equals', value: form.value.verificationStatus })
  if (form.value.corresponding) next.push({ field: 'corresponding', operator: 'equals', value: form.value.corresponding === 'true' })
  return next
})

async function load(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await campaignsApi.listSegments(target)
    segments.value = result.items
    page.value = result.page
    totalPages.value = result.totalPages
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '收件人分组加载失败，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  form.value = { name: '', description: '', category: '', confidence: '', verificationStatus: '', corresponding: '' }
  previewCount.value = null
  previewSample.value = []
  notice.value = ''
  createOpen.value = true
}

async function preview(): Promise<void> {
  if (!rules.value.length) {
    error.value = '请至少配置一条筛选规则。'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const result = await campaignsApi.previewSegment(rules.value)
    previewCount.value = result.eligibleCount
    previewSample.value = result.sample
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '分组预览失败，请检查筛选条件。')
  } finally {
    saving.value = false
  }
}

async function create(): Promise<void> {
  if (!form.value.name.trim() || !rules.value.length) {
    error.value = '请填写分组名称并至少配置一条筛选规则。'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await campaignsApi.createSegment({
      name: form.value.name.trim(), description: form.value.description.trim(), rules: rules.value,
    })
    createOpen.value = false
    notice.value = '收件人分组已创建。'
    await load(1)
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '收件人分组创建失败。')
  } finally {
    saving.value = false
  }
}

function ruleLabel(rule: SegmentRule): string {
  const labels = {
    primaryCategory: '主分类', confidence: '置信度', verificationStatus: '验证状态', corresponding: '通讯作者',
  }
  return `${labels[rule.field]} = ${typeof rule.value === 'boolean' ? (rule.value ? '是' : '否') : rule.value}`
}

onMounted(load)
</script>

<template>
  <section
    data-testid="segments-view"
    aria-labelledby="segments-title"
    class="space-y-6"
  >
    <header class="gap-4 sm:flex sm:items-start sm:justify-between">
      <div>
        <h1
          id="segments-title"
          class="text-2xl font-semibold tracking-tight text-slate-950"
        >
          收件人分组
        </h1>
        <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">
          用受控规则筛选已验证联系人，并在创建活动前预览实际可用人数。
        </p>
      </div>
      <DsButton
        class="mt-4 shrink-0 sm:mt-0"
        @click="openCreate"
      >
        新建分组
      </DsButton>
    </header>

    <DsAlert
      v-if="error"
      tone="danger"
    >
      {{ error }}
    </DsAlert>
    <DsAlert
      v-if="notice"
      tone="success"
    >
      {{ notice }}
    </DsAlert>
    <div
      v-if="loading"
      class="h-40 animate-pulse rounded-lg bg-slate-100"
      aria-label="分组加载中"
    />
    <DsCard
      v-else-if="segments.length"
      padding="none"
    >
      <ul class="divide-y divide-slate-200">
        <li
          v-for="segment in segments"
          :key="segment.id"
          class="p-5 sm:flex sm:items-center sm:justify-between sm:gap-6"
        >
          <div class="min-w-0">
            <h2 class="font-semibold text-slate-900">
              {{ segment.name }}
            </h2>
            <p
              v-if="segment.description"
              class="mt-1 text-sm text-slate-500"
            >
              {{ segment.description }}
            </p>
            <div class="mt-3 flex flex-wrap gap-2">
              <span
                v-for="rule in segment.rules"
                :key="rule.field"
                class="rounded-full bg-slate-100 px-2.5 py-1 text-xs text-slate-600"
              >
                {{ ruleLabel(rule) }}
              </span>
            </div>
          </div>
          <p class="mt-4 shrink-0 text-sm font-semibold text-brand-700 sm:mt-0">
            {{ segment.eligibleCount }} 位可用联系人
          </p>
        </li>
      </ul>
      <div
        v-if="totalPages > 1"
        class="px-5 pb-5"
      >
        <DsPagination
          :page="page"
          :total-pages="totalPages"
          @change="load"
        />
      </div>
    </DsCard>
    <DsCard
      v-else
      padding="none"
    >
      <DsEmptyState
        title="还没有收件人分组"
        description="新建分组后可按论文分类、置信度和联系人状态筛选。"
      >
        <template #icon>
          <UserGroupIcon class="size-9 text-slate-400" />
        </template>
        <template #actions>
          <DsButton @click="openCreate">
            创建第一个分组
          </DsButton>
        </template>
      </DsEmptyState>
    </DsCard>

    <DsModal
      :open="createOpen"
      title="新建收件人分组"
      description="只允许以下固定字段，服务端不会执行自定义查询。"
      @close="createOpen = false"
    >
      <form
        id="segment-form"
        class="space-y-4"
        @submit.prevent="create"
      >
        <DsInput
          id="segment-name"
          v-model="form.name"
          label="分组名称"
          autocomplete="off"
        />
        <DsInput
          id="segment-description"
          v-model="form.description"
          label="说明"
          autocomplete="off"
        />
        <DsInput
          id="segment-category"
          v-model="form.category"
          label="arXiv 主分类"
          placeholder="例如 cs.AI"
          autocomplete="off"
        />
        <DsSelect
          id="segment-confidence"
          v-model="form.confidence"
          label="邮箱置信度"
          :options="[
            { label: '不限', value: '' }, { label: '高', value: 'HIGH' }, { label: '中', value: 'MEDIUM' },
          ]"
        />
        <DsSelect
          id="segment-verification"
          v-model="form.verificationStatus"
          label="联系人验证状态"
          :options="[
            { label: '不限', value: '' }, { label: '已确认', value: 'CONFIRMED' }, { label: '未验证', value: 'UNVERIFIED' },
          ]"
        />
        <DsSelect
          id="segment-corresponding"
          v-model="form.corresponding"
          label="通讯作者"
          :options="[
            { label: '不限', value: '' }, { label: '是', value: 'true' }, { label: '否', value: 'false' },
          ]"
        />
        <div
          v-if="previewCount !== null"
          class="rounded-lg bg-brand-50 p-4 text-sm text-brand-900"
        >
          当前条件匹配 <strong>{{ previewCount }}</strong> 位可用联系人。
          <ul
            v-if="previewSample.length"
            class="mt-2 space-y-1 text-xs text-brand-800"
          >
            <li
              v-for="contact in previewSample.slice(0, 3)"
              :key="contact.contactId"
            >
              {{ contact.authorName }} · {{ contact.paperTitle }} · {{ contact.emailDomain }}
            </li>
          </ul>
        </div>
      </form>
      <template #actions>
        <DsButton
          variant="secondary"
          :busy="saving"
          @click="preview"
        >
          预览人数
        </DsButton>
        <DsButton
          type="submit"
          form="segment-form"
          :busy="saving"
        >
          创建分组
        </DsButton>
      </template>
    </DsModal>
  </section>
</template>
