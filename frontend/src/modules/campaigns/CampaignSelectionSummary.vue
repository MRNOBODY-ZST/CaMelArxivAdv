<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { countLabel, selectionFields } from './campaign.selection-counts'
import type { MigrationReference } from './campaigns.types'
import type { SelectionCounts } from './template-migrations.types'

defineProps<{
  counts?: Partial<SelectionCounts> | null | undefined
  references?: MigrationReference[] | null | undefined
  campaignId?: string
}>()
</script>

<template>
  <div data-testid="selection-inventory">
    <dl class="grid grid-cols-2 gap-x-5 gap-y-2 text-sm text-slate-700">
      <div
        v-for="field in selectionFields"
        :key="field.key"
        class="flex flex-wrap items-baseline gap-x-2"
      >
        <dt>{{ field.label }}</dt>
        <dd class="font-semibold tabular-nums text-slate-900">
          {{ countLabel(counts?.[field.key]) }}
        </dd>
      </div>
    </dl>
    <template v-if="campaignId">
      <p
        v-if="references == null"
        class="mt-2 text-xs text-slate-600"
      >
        迁移引用不可用
      </p>
      <ul
        v-else-if="references.length"
        class="mt-2 space-y-1"
        aria-label="行政迁移引用"
      >
        <li
          v-for="(reference, index) in references"
          :key="reference.migrationId"
        >
          <RouterLink
            :to="`/email/campaigns/${reference.sourceCampaignId === campaignId ? reference.targetCampaignId : reference.sourceCampaignId}`"
            class="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline decoration-brand-200 underline-offset-4 hover:text-brand-800 focus-visible:rounded focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-600"
          >
            {{ reference.sourceCampaignId === campaignId ? '查看迁入目标' : '查看原始来源' }}{{ references.length > 1 ? ` ${index + 1}` : '' }}
          </RouterLink>
        </li>
      </ul>
    </template>
  </div>
</template>
