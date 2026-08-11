<script setup lang="ts">
import {
  CheckCircleIcon, EnvelopeOpenIcon, InboxStackIcon, KeyIcon, PaperClipIcon, PlusIcon,
  ShieldCheckIcon,
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
import { createMailboxDraft, maskEmailAddress, passwordForUpdate } from '@/modules/email/email.editor'
import type {
  MailboxAccountRequest, MailboxAccountView, MailboxMessageHeader, MailboxProtocol, SmtpTlsMode,
} from '@/modules/email/email.types'

const auth = useAuthStore()
const accounts = ref<MailboxAccountView[]>([])
const loading = ref(true)
const busy = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const modalOpen = ref(false)
const editing = ref<MailboxAccountView | null>(null)
const deleteCandidate = ref<MailboxAccountView | null>(null)
const previewAccount = ref<MailboxAccountView | null>(null)
const previewMessages = ref<MailboxMessageHeader[]>([])
const previewLoading = ref(false)
const form = reactive(createMailboxDraft())
const passwordInput = ref('')

const protocolOptions = [
  { value: 'IMAP', label: 'IMAP（推荐，可选文件夹）' },
  { value: 'POP3', label: 'POP3（仅 INBOX）' },
]
const tlsOptions = [
  { value: 'TLS_IMPLICIT', label: 'TLS 隐式连接' },
  { value: 'STARTTLS_REQUIRED', label: 'STARTTLS（必须）' },
  { value: 'PLAIN_LOCAL_ONLY', label: '仅本地白名单明文' },
]
const port = computed({ get: () => String(form.port), set: (value: string) => { form.port = Number(value) } })
const modalTitle = computed(() => editing.value ? '编辑收件账户' : '新增收件账户')

function changeProtocol(value: string): void {
  const protocol = value as MailboxProtocol
  form.protocol = protocol
  form.folderName = 'INBOX'
  form.port = protocol === 'IMAP' ? implicitPort(form.tlsMode, 143, 993) : implicitPort(form.tlsMode, 110, 995)
}

function changeTlsMode(value: string): void {
  const mode = value as SmtpTlsMode
  form.tlsMode = mode
  form.port = form.protocol === 'IMAP' ? implicitPort(mode, 143, 993) : implicitPort(mode, 110, 995)
}

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    accounts.value = (await emailApi.listMailboxAccounts()).items
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, 'IMAP/POP3 账户暂时无法加载。')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  Object.assign(form, createMailboxDraft())
  passwordInput.value = ''
  modalOpen.value = true
}

function openEdit(account: MailboxAccountView): void {
  editing.value = account
  Object.assign(form, {
    name: account.name, protocol: account.protocol, host: account.host, port: account.port,
    tlsMode: account.tlsMode, username: account.username, password: null,
    folderName: account.folderName, enabled: account.enabled,
  })
  passwordInput.value = ''
  modalOpen.value = true
}

async function save(): Promise<void> {
  busy.value = true
  errorMessage.value = ''
  try {
    const request: MailboxAccountRequest = {
      ...form, port: Number(form.port),
      password: passwordForUpdate(passwordInput.value, editing.value?.passwordConfigured ?? false),
      folderName: form.protocol === 'POP3' ? 'INBOX' : form.folderName,
    }
    if (editing.value) {
      await emailApi.updateMailboxAccount(editing.value.id, editing.value.lockVersion, request)
    } else {
      await emailApi.createMailboxAccount(request)
    }
    modalOpen.value = false
    successMessage.value = editing.value ? '收件账户已更新；密码留空时保留原密钥。' : '收件账户已创建。'
    await load()
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '收件账户保存失败。')
  } finally {
    busy.value = false
  }
}

async function testConnection(account: MailboxAccountView): Promise<void> {
  busy.value = true
  errorMessage.value = ''
  try {
    const result = await emailApi.testMailboxConnection(account.id)
    successMessage.value = `连接测试成功（${result.status}）。未读取或修改邮件。`
    await load()
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '邮箱连接失败，请检查协议、端口、TLS 与凭据。')
  } finally {
    busy.value = false
  }
}

async function openPreview(account: MailboxAccountView): Promise<void> {
  previewAccount.value = account
  previewMessages.value = []
  previewLoading.value = true
  errorMessage.value = ''
  try {
    previewMessages.value = await emailApi.previewMailboxMessages(account.id, 20)
  } catch (error) {
    previewAccount.value = null
    errorMessage.value = emailErrorMessage(error, '邮件头预览失败；服务器未读取正文或修改邮件。')
  } finally {
    previewLoading.value = false
  }
}

async function removeAccount(): Promise<void> {
  if (!deleteCandidate.value) return
  busy.value = true
  try {
    await emailApi.deleteMailboxAccount(deleteCandidate.value.id, deleteCandidate.value.lockVersion)
    deleteCandidate.value = null
    successMessage.value = '收件账户已删除。'
    await load()
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '收件账户删除失败，请刷新后重试。')
  } finally {
    busy.value = false
  }
}

function implicitPort(mode: SmtpTlsMode, standard: number, implicit: number): number {
  return mode === 'TLS_IMPLICIT' ? implicit : standard
}

function protocolLabel(protocol: MailboxProtocol): string {
  return protocol === 'IMAP' ? 'IMAP' : 'POP3'
}

function tlsLabel(mode: SmtpTlsMode): string {
  return {
    PLAIN_LOCAL_ONLY: '本地白名单', STARTTLS_REQUIRED: 'STARTTLS', TLS_IMPLICIT: '隐式 TLS',
  }[mode]
}

function displayUser(value: string): string {
  return value.includes('@') ? maskEmailAddress(value) : `${value.slice(0, 2)}***`
}

function displayDate(value: string | null): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '时间未知'
}
</script>

<template>
  <section
    aria-labelledby="mailbox-panel-title"
    class="space-y-5"
  >
    <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h2
          id="mailbox-panel-title"
          class="text-lg font-semibold tracking-tight text-slate-950"
        >
          收件 IMAP / POP3
        </h2>
        <p class="mt-1 max-w-2xl text-sm/6 text-slate-500">
          测试公网或本地邮箱连接，并以只读方式预览最多 20 条脱敏邮件头；不读取正文、下载附件或修改已读状态。
        </p>
      </div>
      <DsButton
        v-if="auth.hasPermission('mailbox:manage')"
        @click="openCreate"
      >
        <PlusIcon class="size-4" />新增收件账户
      </DsButton>
    </div>

    <DsAlert
      tone="info"
      title="公网收件协议强制 TLS"
    >
      公网主机只接受 STARTTLS 或隐式 TLS；PLAIN_LOCAL_ONLY 仅适用于 GreenMail 等精确匹配的本地白名单。
    </DsAlert>
    <DsAlert
      v-if="errorMessage"
      tone="danger"
      title="邮箱操作失败"
    >
      {{ errorMessage }}
    </DsAlert>
    <DsAlert
      v-if="successMessage"
      tone="success"
      title="操作完成"
    >
      {{ successMessage }}
    </DsAlert>

    <div
      v-if="loading"
      class="grid gap-4 lg:grid-cols-2"
    >
      <DsSkeleton
        v-for="index in 2"
        :key="index"
        class="h-64"
      />
    </div>
    <DsCard v-else-if="accounts.length === 0">
      <DsEmptyState
        title="尚未配置收件账户"
        description="添加 IMAP 或 POP3 账户后可测试连接和只读邮件头预览。"
      >
        <template #icon>
          <InboxStackIcon class="size-6" />
        </template>
        <template #actions>
          <DsButton
            v-if="auth.hasPermission('mailbox:manage')"
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
        :data-testid="`mailbox-account-${account.id}`"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="flex min-w-0 items-start gap-3">
            <span class="grid size-10 shrink-0 place-items-center rounded-lg bg-brand-50 text-brand-700"><InboxStackIcon class="size-5" /></span>
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <h3 class="truncate font-semibold text-slate-900">
                  {{ account.name }}
                </h3>
                <DsBadge tone="info">
                  {{ protocolLabel(account.protocol) }}
                </DsBadge>
                <DsBadge :tone="account.enabled ? 'positive' : 'neutral'">
                  {{ account.enabled ? '已启用' : '已停用' }}
                </DsBadge>
              </div>
              <p class="mt-1 break-all text-sm text-slate-500">
                {{ account.host }}:{{ account.port }} · {{ tlsLabel(account.tlsMode) }}
              </p>
            </div>
          </div>
          <ShieldCheckIcon class="size-5 shrink-0 text-emerald-500" />
        </div>
        <dl class="mt-5 grid gap-3 rounded-lg bg-slate-50 p-4 text-sm sm:grid-cols-2">
          <div>
            <dt class="text-xs text-slate-400">
              用户名
            </dt><dd class="mt-1 font-medium text-slate-700">
              {{ displayUser(account.username) }}
            </dd>
          </div>
          <div>
            <dt class="text-xs text-slate-400">
              文件夹
            </dt><dd class="mt-1 font-medium text-slate-700">
              {{ account.folderName }}
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
          <div>
            <dt class="text-xs text-slate-400">
              读取模式
            </dt><dd class="mt-1 font-medium text-slate-700">
              只读邮件头
            </dd>
          </div>
        </dl>
        <div class="mt-4 flex items-center gap-2 text-xs text-slate-500">
          <KeyIcon class="size-4" /><span>{{ account.passwordConfigured ? '密码已加密配置；编辑留空会保留原密码' : '未配置密码' }}</span>
        </div>
        <div class="mt-5 flex flex-wrap gap-2 border-t border-slate-100 pt-4">
          <DsButton
            :data-testid="`preview-${account.id}`"
            size="sm"
            variant="secondary"
            :disabled="!account.enabled"
            @click="openPreview(account)"
          >
            <EnvelopeOpenIcon class="size-4" />预览邮件头
          </DsButton>
          <template v-if="auth.hasPermission('mailbox:manage')">
            <DsButton
              size="sm"
              variant="secondary"
              :busy="busy"
              @click="testConnection(account)"
            >
              <CheckCircleIcon class="size-4" />测试连接
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
          </template>
        </div>
      </DsCard>
    </div>

    <DsModal
      :open="modalOpen"
      :title="modalTitle"
      description="密码只写入加密存储；公网主机必须使用 TLS。"
      @close="modalOpen = false"
    >
      <form
        id="mailbox-form"
        class="space-y-4"
        @submit.prevent="save"
      >
        <div class="grid gap-4 sm:grid-cols-2">
          <DsInput
            id="mailbox-name"
            v-model="form.name"
            label="账户名称"
          />
          <DsSelect
            id="mailbox-protocol"
            :model-value="form.protocol"
            label="协议"
            :options="protocolOptions"
            @update:model-value="changeProtocol"
          />
        </div>
        <div class="grid gap-4 sm:grid-cols-[1fr_8rem]">
          <DsInput
            id="mailbox-host"
            v-model="form.host"
            label="主机"
          />
          <DsInput
            id="mailbox-port"
            v-model="port"
            type="number"
            label="端口"
          />
        </div>
        <DsSelect
          id="mailbox-tls"
          :model-value="form.tlsMode"
          label="TLS 模式"
          :options="tlsOptions"
          @update:model-value="changeTlsMode"
        />
        <div class="grid gap-4 sm:grid-cols-2">
          <DsInput
            id="mailbox-username"
            v-model="form.username"
            label="用户名"
            autocomplete="off"
          />
          <DsInput
            id="mailbox-password"
            v-model="passwordInput"
            type="password"
            label="密码"
            :placeholder="editing?.passwordConfigured ? '留空以保留原密码' : '必填'"
            autocomplete="new-password"
          />
        </div>
        <DsInput
          id="mailbox-folder"
          v-model="form.folderName"
          label="文件夹"
          :disabled="form.protocol === 'POP3'"
        />
        <DsSwitch
          v-model="form.enabled"
          label="启用此账户"
          description="停用后不可预览邮件头，但仍可编辑和测试连接。"
        />
      </form>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="modalOpen = false"
        >
          取消
        </DsButton>
        <DsButton
          type="submit"
          form="mailbox-form"
          :busy="busy"
        >
          保存
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="Boolean(previewAccount)"
      :title="`${previewAccount?.name ?? ''} · 邮件头预览`"
      description="服务器只以 READ_ONLY 模式返回有界邮件头；发件人已脱敏。"
      @close="previewAccount = null"
    >
      <div
        v-if="previewLoading"
        class="space-y-3"
      >
        <DsSkeleton
          v-for="index in 3"
          :key="index"
          class="h-16"
        />
      </div>
      <DsEmptyState
        v-else-if="previewMessages.length === 0"
        title="邮箱暂无邮件"
        description="当前文件夹没有可预览的邮件头。"
      >
        <template #icon>
          <EnvelopeOpenIcon class="size-6" />
        </template>
      </DsEmptyState>
      <ul
        v-else
        class="max-h-[50vh] divide-y divide-slate-100 overflow-y-auto rounded-lg border border-slate-200"
      >
        <li
          v-for="message in previewMessages"
          :key="message.remoteId"
          class="p-4"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <p class="truncate text-sm font-semibold text-slate-900">
                {{ message.subject }}
              </p><p class="mt-1 text-xs text-slate-500">
                {{ message.fromMasked }} · {{ displayDate(message.receivedAt ?? message.sentAt) }}
              </p>
            </div>
            <PaperClipIcon
              v-if="message.hasAttachments"
              class="size-4 shrink-0 text-slate-400"
              aria-label="包含附件"
            />
          </div>
        </li>
      </ul>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="previewAccount = null"
        >
          关闭
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="Boolean(deleteCandidate)"
      title="删除收件账户？"
      description="删除只移除账户配置，不会删除远端邮件。"
      @close="deleteCandidate = null"
    >
      <template #actions>
        <DsButton
          variant="secondary"
          @click="deleteCandidate = null"
        >
          取消
        </DsButton>
        <DsButton
          variant="danger"
          :busy="busy"
          @click="removeAccount"
        >
          确认删除
        </DsButton>
      </template>
    </DsModal>
  </section>
</template>
