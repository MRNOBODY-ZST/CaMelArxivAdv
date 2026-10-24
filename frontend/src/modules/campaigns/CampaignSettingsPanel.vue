<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsSelect, { type SelectOption } from '@/components/design-skill/DsSelect.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { CampaignView } from '@/modules/campaigns/campaigns.types'
import { emailApi, emailErrorMessage } from '@/modules/email/email.api'
import type { MailboxAccountView } from '@/modules/email/email.types'

const props = withDefaults(defineProps<{ campaign: CampaignView; disabled?: boolean }>(), { disabled: false })
const emit = defineEmits<{ saved: []; editing: [value: boolean] }>()
const auth = useAuthStore()
const open = ref(false)
const saving = ref(false)
const loadingMailboxes = ref(false)
const error = ref('')
const mailboxError = ref('')
const mailboxes = ref<MailboxAccountView[]>([])
const editCampaignId = ref('')
const expectedLockVersion = ref(0)
let mailboxRequest = 0
const form = reactive({
  name: '', purpose: '', mailboxAccountId: '', fromName: '', replyTo: '',
  trackingOpensEnabled: false, trackingClicksEnabled: false,
})

const canEdit = computed(() => auth.hasPermission('campaign:create')
  && ['DRAFT', 'REJECTED'].includes(props.campaign.status))
const formDisabled = computed(() => saving.value || props.disabled || !canEdit.value
  || editCampaignId.value !== props.campaign.id)
const mailboxSummary = computed(() => {
  if (!props.campaign.mailboxAccountId) return '未绑定收信邮箱'
  return mailboxes.value.find((account) => account.id === props.campaign.mailboxAccountId)?.name
    ?? `已绑定：${props.campaign.mailboxAccountId}`
})
const mailboxOptions = computed<SelectOption[]>(() => {
  const options: SelectOption[] = [{ value: '', label: '请选择已启用且连接测试成功的收信邮箱', disabled: true }]
  if (form.mailboxAccountId && !mailboxes.value.some((account) => account.id === form.mailboxAccountId)) {
    options.push({ value: form.mailboxAccountId, label: '当前绑定的收信邮箱不可用', disabled: true })
  }
  return options.concat(mailboxes.value.map((account) => ({
    value: account.id,
    label: `${account.name} · ${account.protocol}${!account.enabled ? '（已禁用）' : account.lastTestStatus !== 'SUCCEEDED' ? '（连接测试未成功）' : ''}`,
    disabled: !mailboxReady(account),
  })))
})
const selectedMailboxReady = computed(() => mailboxes.value.some((account) => account.id === form.mailboxAccountId
  && mailboxReady(account)))
const fieldsValid = computed(() => Boolean(
  form.name.trim() && form.name.trim().length <= 200
  && form.purpose.trim() && form.purpose.trim().length <= 4000
  && form.fromName.trim() && form.fromName.trim().length <= 160
  && /^[^\s@]+@[^\s@]+$/.test(form.replyTo.trim()) && form.replyTo.trim().length <= 320,
))
const canSave = computed(() => open.value && !formDisabled.value && !loadingMailboxes.value
  && !mailboxError.value && selectedMailboxReady.value && fieldsValid.value)

function mailboxReady(account: MailboxAccountView): boolean {
  return account.enabled && account.lastTestStatus === 'SUCCEEDED'
}

async function openSettings(): Promise<void> {
  if (!canEdit.value || props.disabled || open.value) return
  const current = props.campaign
  editCampaignId.value = current.id
  expectedLockVersion.value = current.lockVersion
  Object.assign(form, {
    name: current.name, purpose: current.purpose, mailboxAccountId: current.mailboxAccountId ?? '',
    fromName: current.fromName, replyTo: current.replyTo,
    trackingOpensEnabled: current.trackingOpensEnabled, trackingClicksEnabled: current.trackingClicksEnabled,
  })
  error.value = ''
  mailboxError.value = ''
  mailboxes.value = []
  loadingMailboxes.value = true
  open.value = true
  emit('editing', true)
  const request = ++mailboxRequest
  try {
    const result = await emailApi.listMailboxAccounts(1, 100)
    if (request === mailboxRequest) mailboxes.value = result.items
  } catch (failure) {
    if (request === mailboxRequest) mailboxError.value = emailErrorMessage(failure, '收信邮箱加载失败，请关闭后重新打开。')
  } finally {
    if (request === mailboxRequest) loadingMailboxes.value = false
  }
}

function closeSettings(): void {
  if (saving.value) return
  mailboxRequest++
  open.value = false
  emit('editing', false)
}

async function saveSettings(): Promise<void> {
  if (!canSave.value) return
  saving.value = true
  error.value = ''
  try {
    await campaignsApi.updateCampaign(editCampaignId.value, {
      expectedLockVersion: expectedLockVersion.value,
      name: form.name.trim(), purpose: form.purpose.trim(), mailboxAccountId: form.mailboxAccountId,
      fromName: form.fromName.trim(), replyTo: form.replyTo.trim(),
      trackingOpensEnabled: form.trackingOpensEnabled, trackingClicksEnabled: form.trackingClicksEnabled,
    })
    open.value = false
    emit('editing', false)
    emit('saved')
  } catch (failure) {
    error.value = campaignErrorMessage(failure, '活动设置保存失败，请检查后重试。若活动已被修改，请关闭后刷新再编辑。')
  } finally {
    saving.value = false
  }
}

onBeforeUnmount(() => { mailboxRequest++ })
</script>

<template>
  <DsCard>
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 class="text-base font-semibold text-slate-900">
          发送设置
        </h2>
        <p class="mt-1 text-sm text-slate-500">
          检查发件身份与回复收集配置。仅草稿或已拒绝活动可修改。
        </p>
      </div>
      <DsButton
        v-if="canEdit"
        variant="secondary"
        data-testid="edit-campaign-settings"
        :disabled="disabled || open"
        @click="openSettings"
      >
        编辑设置
      </DsButton>
    </div>
    <dl class="mt-5 grid gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
      <div>
        <dt class="text-slate-500">
          发件邮箱（只读）
        </dt>
        <dd class="mt-1 break-all font-medium text-slate-900">
          {{ campaign.fromEmail }}
        </dd>
      </div>
      <div>
        <dt class="text-slate-500">
          SMTP 账户（只读）
        </dt>
        <dd class="mt-1 break-words font-medium text-slate-900">
          {{ campaign.smtpName }}
        </dd>
      </div>
      <div>
        <dt class="text-slate-500">
          回复地址
        </dt>
        <dd class="mt-1 break-all text-slate-900">
          {{ campaign.replyTo || '未设置' }}
        </dd>
      </div>
      <div>
        <dt class="text-slate-500">
          收信邮箱绑定
        </dt>
        <dd
          class="mt-1 break-all"
          :class="campaign.mailboxAccountId ? 'text-slate-900' : 'text-amber-700'"
        >
          {{ mailboxSummary }}
        </dd>
      </div>
    </dl>
  </DsCard>

  <DsModal
    :open="open"
    title="编辑发送设置"
    description="发件邮箱与 SMTP 账户保持不变；此处不会修改邮箱凭据或发送邮件。"
    @close="closeSettings"
  >
    <form
      id="campaign-settings-form"
      class="space-y-4"
      @submit.prevent="saveSettings"
    >
      <DsAlert
        v-if="error || mailboxError"
        tone="danger"
      >
        {{ error || mailboxError }}
      </DsAlert>
      <DsInput
        id="campaign-settings-name"
        v-model="form.name"
        label="活动名称"
        description="必填，最多 200 字。"
        :disabled="formDisabled"
      />
      <div>
        <label
          for="campaign-settings-purpose"
          class="block text-sm/6 font-medium text-slate-900"
        >活动目的</label>
        <p
          id="campaign-settings-purpose-description"
          class="mt-1 text-xs/5 text-slate-500"
        >
          必填，最多 4000 字。修改目的不会重新生成已有个性化内容。
        </p>
        <textarea
          id="campaign-settings-purpose"
          v-model="form.purpose"
          rows="4"
          required
          maxlength="4000"
          aria-describedby="campaign-settings-purpose-description"
          :disabled="formDisabled"
          class="mt-2 block w-full rounded-md bg-white px-3 py-2 text-sm text-slate-900 shadow-xs outline-1 -outline-offset-1 outline-slate-300 focus:outline-2 focus:-outline-offset-2 focus:outline-brand-500 disabled:bg-slate-50 disabled:text-slate-500"
        />
      </div>
      <DsSelect
        id="campaign-settings-mailbox"
        v-model="form.mailboxAccountId"
        label="收信邮箱（必选）"
        :options="mailboxOptions"
        :disabled="formDisabled || loadingMailboxes || Boolean(mailboxError)"
      />
      <p
        class="text-xs/5 text-slate-500"
        aria-live="polite"
      >
        {{ loadingMailboxes ? '正在加载收信邮箱…' : '仅可选择已启用且连接测试成功的邮箱。请确认该邮箱用于接收当前回复地址的邮件。' }}
      </p>
      <DsInput
        id="campaign-settings-from-name"
        v-model="form.fromName"
        label="发件人名称"
        description="必填，最多 160 字。"
        :disabled="formDisabled"
      />
      <DsInput
        id="campaign-settings-reply-to"
        v-model="form.replyTo"
        type="email"
        label="回复地址"
        description="必填，请填写完整邮箱地址。"
        :disabled="formDisabled"
      />
      <fieldset
        class="space-y-3"
        :disabled="formDisabled"
      >
        <legend class="mb-2 text-sm font-medium text-slate-900">
          互动追踪
        </legend>
        <label
          for="campaign-settings-track-opens"
          class="flex items-center gap-3 text-sm text-slate-700"
        >
          <input
            id="campaign-settings-track-opens"
            v-model="form.trackingOpensEnabled"
            type="checkbox"
            class="size-4 accent-brand-500"
          >
          启用打开追踪
        </label>
        <label
          for="campaign-settings-track-clicks"
          class="flex items-center gap-3 text-sm text-slate-700"
        >
          <input
            id="campaign-settings-track-clicks"
            v-model="form.trackingClicksEnabled"
            type="checkbox"
            class="size-4 accent-brand-500"
          >
          启用链接点击追踪
        </label>
      </fieldset>
    </form>
    <template #actions>
      <DsButton
        variant="secondary"
        data-testid="cancel-campaign-settings"
        :disabled="saving"
        @click="closeSettings"
      >
        取消
      </DsButton>
      <DsButton
        type="submit"
        form="campaign-settings-form"
        data-testid="save-campaign-settings"
        :disabled="!canSave"
        :busy="saving"
      >
        保存设置
      </DsButton>
    </template>
  </DsModal>
</template>
