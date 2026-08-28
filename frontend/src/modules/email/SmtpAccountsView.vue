<script setup lang="ts">
import {
  BeakerIcon, CheckCircleIcon, KeyIcon, PlusIcon, ServerStackIcon, ShieldCheckIcon,
} from '@heroicons/vue/24/outline'
import { computed, onMounted, reactive, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import DsSwitch from '@/components/design-skill/DsSwitch.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { emailApi, emailErrorMessage } from '@/modules/email/email.api'
import { createSmtpDraft, maskEmailAddress, passwordForUpdate } from '@/modules/email/email.editor'
import MailTrackingOption from '@/modules/email/MailTrackingOption.vue'
import type { SmtpAccountRequest, SmtpAccountView, SmtpTlsMode } from '@/modules/email/email.types'

withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })

const auth = useAuthStore()
const accounts = ref<SmtpAccountView[]>([])
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const modalOpen = ref(false)
const editing = ref<SmtpAccountView | null>(null)
const testRecipient = ref('')
const trackOpens = ref(false)
const testingAccount = ref<SmtpAccountView | null>(null)
const deleteCandidate = ref<SmtpAccountView | null>(null)
const lastTestRecordId = ref<string | null>(null)
const form = reactive(createSmtpDraft())
const passwordInput = ref('')

const tlsOptions = [
  { value: 'PLAIN_LOCAL_ONLY', label: '仅本机明文（Mailpit）' },
  { value: 'STARTTLS_REQUIRED', label: 'STARTTLS（必须）' },
  { value: 'TLS_IMPLICIT', label: 'TLS 隐式连接' },
]
const modalTitle = computed(() => editing.value ? '编辑 SMTP 账户' : '新增 SMTP 账户')
const hostPort = computed({ get: () => String(form.port), set: (value: string) => { form.port = Number(value) } })
const username = computed({ get: () => form.username ?? '', set: (value: string) => { form.username = value || null } })
const minuteLimit = computed({ get: () => String(form.perMinuteLimit), set: (value: string) => { form.perMinuteLimit = Number(value) } })
const hourLimit = computed({ get: () => String(form.perHourLimit), set: (value: string) => { form.perHourLimit = Number(value) } })
const dayLimit = computed({ get: () => String(form.perDayLimit), set: (value: string) => { form.perDayLimit = Number(value) } })
const domainLimit = computed({ get: () => String(form.perDomainHourLimit), set: (value: string) => { form.perDomainHourLimit = Number(value) } })

onMounted(() => load())

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    accounts.value = (await emailApi.listSmtpAccounts()).items
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, 'SMTP 账户暂时无法加载。')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  Object.assign(form, createSmtpDraft())
  passwordInput.value = ''
  modalOpen.value = true
}

function openEdit(account: SmtpAccountView): void {
  editing.value = account
  Object.assign(form, {
    name: account.name, host: account.host, port: account.port, tlsMode: account.tlsMode,
    username: account.username, password: null, fromEmail: account.fromEmail,
    defaultFromName: account.defaultFromName, replyTo: account.replyTo,
    perMinuteLimit: account.perMinuteLimit, perHourLimit: account.perHourLimit,
    perDayLimit: account.perDayLimit, perDomainHourLimit: account.perDomainHourLimit, enabled: account.enabled,
  })
  passwordInput.value = ''
  modalOpen.value = true
}

async function save(): Promise<void> {
  saving.value = true
  errorMessage.value = ''
  try {
    const request: SmtpAccountRequest = {
      ...form,
      port: Number(form.port), perMinuteLimit: Number(form.perMinuteLimit),
      perHourLimit: Number(form.perHourLimit), perDayLimit: Number(form.perDayLimit),
      perDomainHourLimit: Number(form.perDomainHourLimit), username: form.username || null,
      password: passwordForUpdate(passwordInput.value, editing.value?.passwordConfigured ?? false),
    }
    if (editing.value) await emailApi.updateSmtpAccount(editing.value.id, editing.value.lockVersion, request)
    else await emailApi.createSmtpAccount(request)
    modalOpen.value = false
    lastTestRecordId.value = null
    successMessage.value = editing.value ? 'SMTP 账户已更新，未填写密码时原密钥保持不变。' : 'SMTP 账户已创建。'
    await load()
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, 'SMTP 账户保存失败。')
  } finally {
    saving.value = false
  }
}

async function testConnection(account: SmtpAccountView): Promise<void> {
  saving.value = true
  successMessage.value = ''
  lastTestRecordId.value = null
  try {
    const result = await emailApi.testSmtpConnection(account.id)
    successMessage.value = `连接测试成功（${result.status}）。这不代表邮件已投递。`
    await load()
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, 'SMTP 连接测试失败，请检查主机、端口和 TLS 模式。')
  } finally {
    saving.value = false
  }
}

function openDiagnostic(account: SmtpAccountView): void {
  testRecipient.value = ''
  trackOpens.value = false
  lastTestRecordId.value = null
  testingAccount.value = account
}

function closeDiagnostic(): void {
  testRecipient.value = ''
  trackOpens.value = false
  testingAccount.value = null
}

async function sendDiagnostic(): Promise<void> {
  if (!testingAccount.value || !testRecipient.value.trim()) return
  saving.value = true
  try {
    const result = await emailApi.sendSmtpDiagnostic(testingAccount.value.id, testRecipient.value.trim(), trackOpens.value)
    lastTestRecordId.value = result.correlationId
    successMessage.value = `SMTP 已接受测试邮件，不代表最终投递。关联 ID：${result.correlationId}`
    closeDiagnostic()
    await load()
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '测试邮件未被 SMTP 接受。')
  } finally {
    saving.value = false
  }
}

async function removeAccount(): Promise<void> {
  if (!deleteCandidate.value) return
  saving.value = true
  try {
    await emailApi.deleteSmtpAccount(deleteCandidate.value.id, deleteCandidate.value.lockVersion)
    deleteCandidate.value = null
    lastTestRecordId.value = null
    successMessage.value = 'SMTP 账户已删除。'
    await load()
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, 'SMTP 账户无法删除，可能已被邮件活动引用。')
  } finally {
    saving.value = false
  }
}

function tlsLabel(mode: SmtpTlsMode): string {
  return { PLAIN_LOCAL_ONLY: '本机专用', STARTTLS_REQUIRED: 'STARTTLS', TLS_IMPLICIT: 'TLS' }[mode]
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p
          v-if="!embedded"
          class="text-xs font-semibold uppercase tracking-wider text-brand-600"
        >
          系统管理
        </p>
        <h1
          v-if="!embedded"
          class="mt-1 text-2xl font-semibold tracking-tight text-slate-950"
        >
          SMTP 账户
        </h1>
        <h2
          v-else
          class="mt-1 text-lg font-semibold tracking-tight text-slate-950"
        >
          出站 SMTP
        </h2>
        <p class="mt-2 max-w-2xl text-sm/6 text-slate-500">
          管理出站连接和速率上限。密码以 AES-GCM 加密保存，读取接口永不返回密码或密文。
        </p>
      </div>
      <DsButton
        v-if="auth.hasPermission('smtp:manage')"
        @click="openCreate"
      >
        <PlusIcon class="size-4" />新增账户
      </DsButton>
    </div>

    <DsAlert
      data-testid="public-smtp-banner"
      tone="info"
      title="公网 SMTP 已启用安全策略"
    >
      公网主机必须使用 STARTTLS 或隐式 TLS 并校验证书主机名；明文连接只允许 Mailpit 等精确匹配的本地白名单。
    </DsAlert>
    <DsAlert
      v-if="errorMessage"
      tone="danger"
      title="SMTP 操作失败"
    >
      {{ errorMessage }}
    </DsAlert>
    <DsAlert
      v-if="successMessage"
      tone="success"
      title="操作完成"
    >
      <p>{{ successMessage }}</p>
      <a
        v-if="lastTestRecordId"
        :href="`/email/deliveries?record=${lastTestRecordId}`"
        class="mt-2 inline-flex min-h-11 items-center font-semibold underline underline-offset-2"
      >
        查看测试邮件记录
      </a>
    </DsAlert>

    <div
      v-if="loading"
      class="grid gap-4 lg:grid-cols-2"
    >
      <DsSkeleton
        v-for="index in 4"
        :key="index"
        class="h-64"
      />
    </div>
    <DsCard v-else-if="accounts.length === 0">
      <DsEmptyState
        title="尚未配置 SMTP"
        description="添加 SMTP 账户后可测试连接；批量发送仍受活动审批与发送状态机控制。"
      >
        <template #icon>
          <ServerStackIcon class="size-6" />
        </template>
        <template #actions>
          <DsButton
            v-if="auth.hasPermission('smtp:manage')"
            @click="openCreate"
          >
            添加账户
          </DsButton>
        </template>
      </DsEmptyState>
    </DsCard>
    <div
      v-else
      class="grid gap-4 lg:grid-cols-2"
    >
      <DsCard
        v-for="account in accounts"
        :key="account.id"
        :data-testid="`smtp-account-${account.id}`"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="flex items-start gap-3">
            <span class="grid size-10 place-items-center rounded-lg bg-slate-100 text-slate-600"><ServerStackIcon class="size-5" /></span>
            <div>
              <div class="flex flex-wrap items-center gap-2">
                <h2 class="font-semibold text-slate-900">
                  {{ account.name }}
                </h2><DsBadge :tone="account.enabled ? 'positive' : 'neutral'">
                  {{ account.enabled ? '已启用' : '已停用' }}
                </DsBadge>
              </div>
              <p class="mt-1 text-sm text-slate-500">
                {{ account.host }}:{{ account.port }} · {{ tlsLabel(account.tlsMode) }}
              </p>
            </div>
          </div>
          <ShieldCheckIcon class="size-5 text-emerald-500" />
        </div>
        <dl class="mt-5 grid gap-3 rounded-lg bg-slate-50 p-4 text-sm sm:grid-cols-2">
          <div>
            <dt class="text-xs text-slate-400">
              发件地址
            </dt><dd class="mt-1 truncate font-medium text-slate-700">
              {{ maskEmailAddress(account.fromEmail) }}
            </dd>
          </div>
          <div>
            <dt class="text-xs text-slate-400">
              Reply-To
            </dt><dd class="mt-1 truncate font-medium text-slate-700">
              {{ maskEmailAddress(account.replyTo) }}
            </dd>
          </div>
          <div>
            <dt class="text-xs text-slate-400">
              每分钟 / 每小时
            </dt><dd class="mt-1 font-medium text-slate-700">
              {{ account.perMinuteLimit }} / {{ account.perHourLimit }}
            </dd>
          </div>
          <div>
            <dt class="text-xs text-slate-400">
              最近测试
            </dt><dd
              class="mt-1 font-medium"
              :class="account.lastTestStatus === 'SUCCEEDED' ? 'text-emerald-700' : 'text-slate-700'"
            >
              {{ account.lastTestStatus || '尚未测试' }}
            </dd>
          </div>
        </dl>
        <div
          data-testid="password-sentinel"
          class="mt-4 flex items-center gap-2 text-xs text-slate-500"
        >
          <KeyIcon class="size-4" /><span>{{ account.passwordConfigured ? '密码已安全配置；编辑留空会保留原密码' : '未配置认证密码' }}</span>
        </div>
        <div
          v-if="auth.hasPermission('smtp:manage')"
          class="mt-5 flex flex-wrap gap-2 border-t border-slate-100 pt-4"
        >
          <DsButton
            size="sm"
            variant="secondary"
            :busy="saving"
            @click="testConnection(account)"
          >
            <CheckCircleIcon class="size-4" />测试连接
          </DsButton>
          <DsButton
            size="sm"
            variant="secondary"
            @click="openDiagnostic(account)"
          >
            <BeakerIcon class="size-4" />测试邮件
          </DsButton>
          <DsButton
            size="sm"
            variant="ghost"
            @click="openEdit(account)"
          >
            编辑
          </DsButton>
          <DsButton
            size="sm"
            variant="ghost"
            @click="deleteCandidate = account"
          >
            删除
          </DsButton>
        </div>
      </DsCard>
    </div>

    <DsModal
      :open="modalOpen"
      :title="modalTitle"
      description="公网目标必须选择 STARTTLS 或隐式 TLS；PLAIN_LOCAL_ONLY 仅用于本地白名单。"
      @close="modalOpen = false"
    >
      <form
        id="smtp-form"
        class="space-y-4"
        @submit.prevent="save"
      >
        <div class="grid gap-4 sm:grid-cols-2">
          <DsInput
            id="smtp-name"
            v-model="form.name"
            label="账户名称"
          /><DsSelect
            id="smtp-tls"
            v-model="form.tlsMode"
            label="TLS 模式"
            :options="tlsOptions"
          />
        </div>
        <div class="grid gap-4 sm:grid-cols-[1fr_8rem]">
          <DsInput
            id="smtp-host"
            v-model="form.host"
            label="主机"
          /><DsInput
            id="smtp-port"
            v-model="hostPort"
            type="number"
            label="端口"
          />
        </div>
        <div class="grid gap-4 sm:grid-cols-2">
          <DsInput
            id="smtp-username"
            v-model="username"
            label="用户名（可选）"
            autocomplete="off"
          /><DsInput
            id="smtp-password"
            v-model="passwordInput"
            type="password"
            label="密码"
            :placeholder="editing?.passwordConfigured ? '留空以保留原密码' : '认证时必填'"
            autocomplete="new-password"
          />
        </div>
        <div class="grid gap-4 sm:grid-cols-2">
          <DsInput
            id="smtp-from"
            v-model="form.fromEmail"
            type="email"
            label="发件地址"
          /><DsInput
            id="smtp-from-name"
            v-model="form.defaultFromName"
            label="默认发件人名称"
          />
        </div>
        <DsInput
          id="smtp-reply"
          v-model="form.replyTo"
          type="email"
          label="Reply-To"
        />
        <div class="grid gap-4 sm:grid-cols-2">
          <DsInput
            id="limit-minute"
            v-model="minuteLimit"
            type="number"
            label="每分钟上限"
          /><DsInput
            id="limit-hour"
            v-model="hourLimit"
            type="number"
            label="每小时上限"
          /><DsInput
            id="limit-day"
            v-model="dayLimit"
            type="number"
            label="每日上限"
          /><DsInput
            id="limit-domain"
            v-model="domainLimit"
            type="number"
            label="每域每小时上限"
          />
        </div>
        <DsSwitch
          v-model="form.enabled"
          label="启用此账户"
          description="连接仍受公网协议开关、TLS 与本地主机白名单约束。"
        />
      </form>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="modalOpen = false"
        >
          取消
        </DsButton><DsButton
          type="submit"
          form="smtp-form"
          :busy="saving"
        >
          保存
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="Boolean(testingAccount)"
      title="发送 SMTP 测试邮件"
      description="公网账户会真实发信。请确认当前账户与收件人；SMTP 接受不代表最终投递。"
      @close="closeDiagnostic"
    >
      <p class="mb-4 text-sm text-slate-600">
        账户：{{ testingAccount?.name }} · {{ testingAccount?.host }}:{{ testingAccount?.port }}
      </p>
      <DsInput
        id="smtp-test-recipient"
        v-model="testRecipient"
        type="email"
        label="测试收件地址"
      />
      <div class="mt-4">
        <MailTrackingOption
          id="smtp-diagnostic-track-opens"
          v-model="trackOpens"
        />
      </div>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="closeDiagnostic"
        >
          取消
        </DsButton><DsButton
          :busy="saving"
          :disabled="!testRecipient.trim()"
          @click="sendDiagnostic"
        >
          发送测试邮件
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="Boolean(deleteCandidate)"
      title="删除 SMTP 账户？"
      description="如果账户已被邮件活动引用，服务端会拒绝删除。"
      @close="deleteCandidate = null"
    >
      <template #actions>
        <DsButton
          variant="secondary"
          @click="deleteCandidate = null"
        >
          取消
        </DsButton><DsButton
          variant="danger"
          :busy="saving"
          @click="removeAccount"
        >
          确认删除
        </DsButton>
      </template>
    </DsModal>
  </div>
</template>
