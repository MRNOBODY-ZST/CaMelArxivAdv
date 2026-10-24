<script setup lang="ts">
import { PencilSquareIcon } from '@heroicons/vue/24/outline'
import axios from 'axios'
import { computed, reactive, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import { redactPartnerClaimUrl } from '@/modules/email/email.editor'
import type {
  CampaignRecipient, CampaignRecipientContentEditResult, CampaignView,
} from '@/modules/campaigns/campaigns.types'

const props = defineProps<{ campaign: CampaignView; recipient: CampaignRecipient; disabled?: boolean }>()
const emit = defineEmits<{ saved: [result: CampaignRecipientContentEditResult]; editing: [active: boolean] }>()
const auth = useAuthStore()
const open = ref(false)
const saving = ref(false)
const conflict = ref(false)
const error = ref('')
const snapshot = ref<{ campaignId: string; recipientId: string; lockVersion: number } | null>(null)
const draft = reactive({ subject: '', html: '', text: '', rationale: '', reason: '' })
const unsubscribePlaceholder = '{{unsubscribe_url}}'
const formId = computed(() => `recipient-${props.recipient.id}-content-form`)
const fieldId = (field: string) => `recipient-${props.recipient.id}-${field}`
const fields = [
  { key: 'html', label: 'HTML 正文', rows: 12, limit: 200_000, help: '直接编辑 HTML 源码，保留邮件原有排版。' },
  { key: 'text', label: '纯文本正文', rows: 7, limit: 100_000, help: '用于不显示 HTML 的邮件客户端，请与 HTML 内容保持一致。' },
  { key: 'rationale', label: '个性化说明', rows: 3, limit: 2_000, help: '说明这封邮件与作者及论文的关联，不会作为邮件正文发送。' },
  { key: 'reason', label: '修改原因（审计记录）', rows: 3, limit: 1_000, help: '记录本次人工修改的原因，便于后续审核。' },
] as const

const unavailableReason = computed(() => {
  if (!auth.hasPermission('campaign:create')) return '当前账号没有修改草稿的权限。'
  if (props.disabled) return '活动正在执行其他操作，请稍后再编辑。'
  if (props.campaign.status !== 'DRAFT') return '仅草稿活动允许修改，已提交审核的内容不可编辑。'
  if (!['COMPLETED', 'PARTIALLY_FAILED', 'FAILED'].includes(props.campaign.generationStatus)) {
    return '请等待个性化生成结束后再编辑。'
  }
  if (props.recipient.personalizationStatus !== 'GENERATED') return '仅已生成的收件人内容可以编辑。'
  // Delivery totals include pristine QUEUED recipients; actual attempt checks remain authoritative on the server.
  if (props.recipient.trackingArtifactsRedacted) {
    return '已生成跟踪信息的收件人不可编辑。'
  }
  return ''
})
const canEdit = computed(() => !unavailableReason.value)

// This is an isolated, network-free layout preview only. Never rewrite the source being saved.
const previewHtml = computed(() => {
  if (!open.value) return ''
  const document = globalThis.document.implementation.createHTMLDocument('')
  const template = document.createElement('template')
  // Template contents stay inert while URLs are stripped, before entering any document body.
  template.innerHTML = draft.html
  template.content.querySelectorAll('script, meta, base, link, iframe, object, embed, form, template, svg, math')
    .forEach((element) => element.remove())
  template.content.querySelectorAll('*').forEach((element) => {
    for (const attribute of [...element.attributes]) {
      if (/^on/i.test(attribute.name)
        || ['src', 'srcset', 'srcdoc', 'href', 'xlink:href', 'action', 'formaction'].includes(attribute.name)) {
        element.removeAttribute(attribute.name)
      }
    }
  })
  const policy = document.createElement('meta')
  policy.httpEquiv = 'Content-Security-Policy'
  policy.content = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'"
  document.head.prepend(policy)
  document.body.append(template.content)
  return redactPartnerClaimUrl(`<!doctype html>${document.documentElement.outerHTML}`)
})

function openEditor(): void {
  if (!canEdit.value || saving.value || open.value) return
  snapshot.value = { campaignId: props.campaign.id, recipientId: props.recipient.id, lockVersion: props.campaign.lockVersion }
  draft.subject = props.recipient.subject ?? ''
  draft.html = props.recipient.html ?? ''
  draft.text = props.recipient.text ?? ''
  draft.rationale = props.recipient.rationale ?? ''
  draft.reason = ''
  error.value = ''
  conflict.value = false
  open.value = true
  emit('editing', true)
}

function closeEditor(): void {
  if (saving.value || !open.value) return
  open.value = false
  emit('editing', false)
}

function hasUnsafeControls(value: string, multiline = false): boolean {
  return [...value].some((character) => {
    const code = character.charCodeAt(0)
    return (code < 32 || (code >= 127 && code <= 159)) && !(multiline && [9, 10, 13].includes(code))
  })
}

function validationError(): string {
  if (!draft.subject.trim() || draft.subject.length > 998) return '邮件主题为必填项，最多 998 字符。'
  if (hasUnsafeControls(draft.subject) || /\{\{[\s\S]*?}}/.test(draft.subject)) {
    return '邮件主题不能包含控制字符或模板占位符。'
  }
  for (const field of fields) {
    const value = draft[field.key]
    if (!value.trim() || value.length > field.limit) return `${field.label}为必填项，最多 ${field.limit.toLocaleString()} 字符。`
    if (['html', 'text'].includes(field.key) && !value.includes(unsubscribePlaceholder)) {
      return `${field.label}必须保留精确的退订占位符 ${unsubscribePlaceholder}。`
    }
    if (['rationale', 'reason'].includes(field.key) && hasUnsafeControls(value.normalize('NFKC'), true)) {
      return `${field.label}不能包含不安全的控制字符。`
    }
  }
  return ''
}

async function save(): Promise<void> {
  if (saving.value || conflict.value || !open.value || !snapshot.value) return
  if (!canEdit.value || snapshot.value.campaignId !== props.campaign.id || snapshot.value.recipientId !== props.recipient.id) {
    error.value = unavailableReason.value || '当前收件人已变化，请关闭后重新打开编辑器。'
    return
  }
  error.value = validationError()
  if (error.value) return
  saving.value = true
  try {
    const result = await campaignsApi.updateRecipientContent(snapshot.value.campaignId, snapshot.value.recipientId, {
      expectedLockVersion: snapshot.value.lockVersion, ...draft,
    })
    open.value = false
    emit('editing', false)
    emit('saved', result)
  } catch (cause) {
    conflict.value = axios.isAxiosError(cause) && cause.response?.status === 409
    error.value = conflict.value
      ? '活动版本或收件人状态已变化。修改内容已保留，请先复制备份，再关闭并刷新活动后重新编辑；不会自动重试保存。'
      : campaignErrorMessage(cause, '草稿保存失败，修改内容已保留，请检查后重试。')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <DsButton
      v-if="auth.hasPermission('campaign:create')"
      variant="secondary"
      size="sm"
      :disabled="!canEdit || saving || open"
      :title="unavailableReason || '修改这位收件人的已生成邮件草稿'"
      data-testid="edit-recipient-content"
      @click="openEditor"
    >
      <PencilSquareIcon class="size-4" />
      编辑草稿
    </DsButton>

    <DsModal
      :open="open"
      size="xl"
      title="编辑收件人邮件草稿"
      :description="`${recipient.authorName} · ${recipient.paperTitle}`"
      @close="closeEditor"
    >
      <form
        :id="formId"
        class="max-h-[65vh] space-y-5 overflow-y-auto px-0.5 pb-1"
        data-testid="recipient-content-form"
        novalidate
        @submit.prevent="save"
      >
        <p class="text-sm/6 text-slate-600">
          仅修改当前收件人的草稿，不会发送邮件或重新生成内容。保存后会重新校验发送条件。
          HTML 和纯文本正文都必须保留 <code class="rounded bg-slate-100 px-1 text-xs text-slate-800">{{ unsubscribePlaceholder }}</code>。
        </p>
        <DsAlert
          v-if="error || !canEdit"
          tone="danger"
        >
          {{ error || unavailableReason }}
        </DsAlert>
        <DsInput
          :id="fieldId('subject')"
          v-model="draft.subject"
          label="邮件主题"
          description="最多 998 字符，不得包含模板占位符或控制字符。"
          :disabled="saving"
        />

        <div class="grid min-w-0 grid-cols-1 gap-6 lg:grid-cols-2">
          <div class="min-w-0 space-y-5">
            <div
              v-for="field in fields"
              :key="field.key"
              class="min-w-0"
            >
              <label
                :for="fieldId(field.key)"
                class="block text-sm/6 font-medium text-slate-900"
              >{{ field.label }}</label>
              <p
                :id="`${fieldId(field.key)}-description`"
                class="mt-1 text-xs/5 text-slate-500"
              >
                {{ field.help }}最多 {{ field.limit.toLocaleString() }} 字符。
              </p>
              <textarea
                :id="fieldId(field.key)"
                v-model="draft[field.key]"
                :rows="field.rows"
                :disabled="saving"
                :aria-describedby="`${fieldId(field.key)}-description`"
                :spellcheck="field.key !== 'html'"
                :class="[
                  'mt-2 block w-full resize-y rounded-md bg-white px-3 py-2 text-sm/6 text-slate-900 shadow-xs outline-1 -outline-offset-1 outline-slate-300 focus:outline-2 focus:-outline-offset-2 focus:outline-brand-500 disabled:bg-slate-50 disabled:text-slate-500',
                  field.key === 'html' ? 'font-mono text-xs/6' : '',
                ]"
              />
            </div>
          </div>

          <section class="min-w-0 lg:sticky lg:top-0 lg:self-start">
            <h3 class="text-sm/6 font-medium text-slate-900">
              HTML 排版预览
            </h3>
            <p class="mt-1 text-xs/5 text-slate-500">
              隔离预览，不加载外部图片、不执行脚本或打开链接；实际内容仍须通过服务端安全校验。
            </p>
            <iframe
              class="mt-2 h-96 w-full rounded-lg border border-slate-200 bg-white sm:h-[32rem]"
              :title="`${recipient.authorName} 的邮件草稿预览`"
              sandbox=""
              referrerpolicy="no-referrer"
              :srcdoc="previewHtml"
            />
          </section>
        </div>
      </form>
      <template #actions>
        <DsButton
          variant="secondary"
          :disabled="saving"
          @click="closeEditor"
        >
          取消
        </DsButton>
        <DsButton
          type="submit"
          :form="formId"
          :busy="saving"
          :disabled="!canEdit || conflict"
          data-testid="save-recipient-content"
        >
          保存草稿
        </DsButton>
      </template>
    </DsModal>
  </div>
</template>
