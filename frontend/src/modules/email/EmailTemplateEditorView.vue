<script setup lang="ts">
import {
  ArchiveBoxIcon, ArrowLeftIcon, BeakerIcon, CheckCircleIcon, ClockIcon, CodeBracketIcon,
  ComputerDesktopIcon, DocumentDuplicateIcon, ExclamationTriangleIcon, MoonIcon, PaperClipIcon,
  PaperAirplaneIcon, PhotoIcon, QueueListIcon, SparklesIcon,
} from '@heroicons/vue/24/outline'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsModal from '@/components/design-skill/DsModal.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import DsSwitch from '@/components/design-skill/DsSwitch.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { emailApi, emailErrorMessage } from '@/modules/email/email.api'
import { createSampleValues, createTemplateDraft, insertPlaceholder, previewWidthClass } from '@/modules/email/email.editor'
import type {
  PreviewDevice, SmtpAccountView, TemplateAsset, TemplatePreview, TemplateSampleValues,
  TemplateUpsertRequest, TemplateVariable, TemplateVersionView, TemplateView,
} from '@/modules/email/email.types'
import { TEMPLATE_VARIABLES } from '@/modules/email/email.types'
import TemplateRichTextEditor from '@/modules/email/TemplateRichTextEditor.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const routeId = computed(() => String(route.params.id ?? 'new'))
const isNew = computed(() => routeId.value === 'new')
const draft = reactive<TemplateUpsertRequest>(createTemplateDraft())
const sampleValues = reactive<TemplateSampleValues>(createSampleValues())
const template = ref<TemplateView | null>(null)
const preview = ref<TemplatePreview | null>(null)
const versions = ref<TemplateVersionView[]>([])
const smtpAccounts = ref<SmtpAccountView[]>([])
const assets = ref<TemplateAsset[]>([])
const loading = ref(!isNew.value)
const saving = ref(false)
const saveState = ref<'idle' | 'dirty' | 'saving' | 'saved' | 'error'>('idle')
const errorMessage = ref('')
const successMessage = ref('')
const editorMode = ref<'rich' | 'html' | 'text'>('rich')
const previewDevice = ref<PreviewDevice>('desktop')
const darkPreview = ref(false)
const versionsOpen = ref(false)
const testSendOpen = ref(false)
const copyOpen = ref(false)
const archiveOpen = ref(false)
const copyName = ref('')
const testRecipient = ref('qa@example.org')
const selectedSmtpId = ref('')
const activeScalar = ref<'subjectTemplate' | 'fromNameTemplate' | 'textContent'>('subjectTemplate')
const richEditor = ref<InstanceType<typeof TemplateRichTextEditor> | null>(null)
const uploadInput = ref<globalThis.HTMLInputElement | null>(null)
const unsubscribeToken = '{{unsubscribe_url}}'
let initialized = false
let autosaveTimer: number | undefined
let previewTimer: number | undefined

const canManage = computed(() => auth.hasPermission('template:manage'))
const editorTitle = computed(() => isNew.value ? '新建邮件模板' : draft.name || '邮件模板')
const bytesPercent = computed(() => Math.min(100, Math.round(((preview.value?.contentSizeBytes ?? template.value?.contentSizeBytes ?? 0) / 102_400) * 100)))
const hasUnsubscribe = computed(() => draft.content.htmlContent.includes('{{unsubscribe_url}}') || draft.content.textContent?.includes('{{unsubscribe_url}}'))
const previewHtml = computed(() => preview.value?.rendered.html ?? '<p style="color:#64748b">填写必填字段后点击“刷新预览”。</p>')
const smtpOptions = computed(() => smtpAccounts.value.map((account) => ({ label: `${account.name} · ${account.host}:${account.port}`, value: account.id })))
const statusOptions = [
  { label: '草稿', value: 'DRAFT' }, { label: '启用', value: 'ACTIVE' }, { label: '归档', value: 'ARCHIVED' },
]
const variableLabels: Record<TemplateVariable, string> = {
  author_name: '作者全名', first_name: '名字', paper_title: '论文标题', arxiv_id: 'arXiv ID',
  primary_category: '主分类', paper_url: '论文链接', organization: '机构名称', unsubscribe_url: '退订链接',
}

onMounted(async () => {
  if (!isNew.value) await loadTemplate()
  else {
    initialized = true
    loading.value = false
  }
})

onBeforeUnmount(() => {
  if (autosaveTimer) globalThis.window.clearTimeout(autosaveTimer)
  if (previewTimer) globalThis.window.clearTimeout(previewTimer)
})

watch(draft, () => {
  if (!initialized || !canManage.value) return
  saveState.value = 'dirty'
  if (autosaveTimer) globalThis.window.clearTimeout(autosaveTimer)
  if (!isNew.value) autosaveTimer = globalThis.window.setTimeout(() => save(false), 1_200)
}, { deep: true })

watch(routeId, (current, previous) => {
  if (current === previous) return
  if (autosaveTimer) globalThis.window.clearTimeout(autosaveTimer)
  if (previewTimer) globalThis.window.clearTimeout(previewTimer)
  initialized = false
  template.value = null
  preview.value = null
  versions.value = []
  assets.value = []
  errorMessage.value = ''
  successMessage.value = ''
  if (current === 'new') {
    Object.assign(draft, createTemplateDraft())
    initialized = true
    loading.value = false
    return
  }
  void loadTemplate()
})

async function loadTemplate(): Promise<void> {
  const requestedId = routeId.value
  loading.value = true
  try {
    const loaded = await emailApi.getTemplate(requestedId)
    if (routeId.value !== requestedId) return
    applyTemplate(loaded)
    await Promise.all([loadVersions(), loadAssets()])
    await refreshPreview(false)
  } catch (error) {
    if (routeId.value === requestedId) {
      errorMessage.value = emailErrorMessage(error, '邮件模板无法加载。')
    }
  } finally {
    if (routeId.value === requestedId) {
      initialized = true
      loading.value = false
    }
  }
}

function applyTemplate(value: TemplateView): void {
  initialized = false
  template.value = value
  Object.assign(draft, {
    name: value.name, description: value.description ?? '', status: value.status,
    content: {
      subjectTemplate: value.subjectTemplate, fromNameTemplate: value.fromNameTemplate,
      replyTo: value.replyTo, htmlContent: value.htmlContent, textContent: value.textContent,
      autoGenerateText: value.autoGenerateText,
    },
  })
  void nextTick(() => { initialized = true })
}

async function save(showMessage = true): Promise<void> {
  if (saving.value || !canManage.value) return
  saving.value = true
  saveState.value = 'saving'
  errorMessage.value = ''
  try {
    const saved = isNew.value
      ? await emailApi.createTemplate(draft)
      : await emailApi.updateTemplate(routeId.value, template.value?.lockVersion ?? 0, draft)
    applyTemplate(saved)
    saveState.value = 'saved'
    if (showMessage) successMessage.value = `已保存为版本 ${saved.currentVersion}`
    if (isNew.value) await router.replace(`/email/templates/${saved.id}`)
    await Promise.all([loadVersions(), refreshPreview(false)])
  } catch (error) {
    saveState.value = 'error'
    errorMessage.value = emailErrorMessage(error, '模板保存失败；如版本已变更，请刷新后重试。')
  } finally {
    saving.value = false
  }
}

async function refreshPreview(showErrors = true): Promise<void> {
  if (!draft.name || !draft.content.replyTo) return
  try {
    preview.value = await emailApi.previewTemplate(draft, { ...sampleValues })
  } catch (error) {
    if (showErrors) errorMessage.value = emailErrorMessage(error, '预览生成失败，请检查模板变量和 HTML。')
  }
}

async function loadVersions(): Promise<void> {
  if (isNew.value) return
  versions.value = await emailApi.listTemplateVersions(routeId.value)
}

async function openVersions(): Promise<void> {
  await loadVersions()
  versionsOpen.value = true
}

async function restore(version: number): Promise<void> {
  if (!template.value || !globalThis.window.confirm(`将版本 ${version} 恢复为新的最新版本？历史记录不会被覆盖。`)) return
  try {
    const restored = await emailApi.restoreTemplate(template.value.id, version, template.value.lockVersion)
    applyTemplate(restored)
    versionsOpen.value = false
    successMessage.value = `版本 ${version} 已恢复为新版本 ${restored.currentVersion}`
    await Promise.all([loadVersions(), refreshPreview(false)])
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '版本恢复失败。')
  }
}

async function duplicate(): Promise<void> {
  if (!template.value || !copyName.value) return
  try {
    const copied = await emailApi.copyTemplate(template.value.id, copyName.value)
    copyOpen.value = false
    await router.push(`/email/templates/${copied.id}`)
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '模板复制失败。')
  }
}

async function archive(): Promise<void> {
  if (!template.value) return
  try {
    await emailApi.archiveTemplate(template.value.id, template.value.lockVersion)
    await router.push('/email/templates')
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '模板无法归档，可能已被邮件活动引用。')
  }
}

function insertVariable(variable: TemplateVariable): void {
  if (editorMode.value === 'rich') {
    richEditor.value?.insertContent(`{{${variable}}}`)
    return
  }
  if (editorMode.value === 'html') {
    draft.content.htmlContent = insertPlaceholder(draft.content.htmlContent, variable)
    return
  }
  const key = activeScalar.value
  const current = draft.content[key] ?? ''
  draft.content[key] = insertPlaceholder(current, variable)
}

async function loadAssets(): Promise<void> {
  if (isNew.value) return
  assets.value = await emailApi.listAssets(routeId.value)
}

async function uploadAsset(event: globalThis.Event): Promise<void> {
  const file = (event.target as globalThis.HTMLInputElement).files?.[0]
  if (!file || isNew.value) return
  try {
    const asset = await emailApi.uploadAsset(routeId.value, file)
    assets.value = [asset, ...assets.value]
    richEditor.value?.insertImage(asset.objectUrl, asset.originalFilename)
    successMessage.value = '图片已上传到私有资产库并插入正文。'
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '图片上传失败；仅支持 5 MB 内的 PNG/JPEG/GIF/WebP。')
  } finally {
    if (uploadInput.value) uploadInput.value.value = ''
  }
}

async function openTestSend(): Promise<void> {
  if (isNew.value) {
    errorMessage.value = '请先保存模板，再发送内部测试。'
    return
  }
  try {
    smtpAccounts.value = (await emailApi.listSmtpAccounts()).items.filter((item) => item.enabled)
    selectedSmtpId.value = smtpAccounts.value[0]?.id ?? ''
    testSendOpen.value = true
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, 'SMTP 账户无法加载。')
  }
}

async function testSend(): Promise<void> {
  if (!template.value || !selectedSmtpId.value) return
  saving.value = true
  try {
    const result = await emailApi.testSendTemplate(template.value.id, selectedSmtpId.value, testRecipient.value, { ...sampleValues })
    testSendOpen.value = false
    successMessage.value = `Mailpit 已接受测试邮件（${result.correlationId}）；这不代表最终投递。`
  } catch (error) {
    errorMessage.value = emailErrorMessage(error, '测试邮件未被 SMTP 接受。')
  } finally {
    saving.value = false
  }
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
</script>

<template>
  <div class="space-y-5">
    <div class="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
      <div class="flex items-start gap-3">
        <RouterLink
          to="/email/templates"
          class="grid min-h-11 min-w-11 place-items-center rounded-md text-slate-500 hover:bg-white hover:text-slate-800"
          aria-label="返回模板列表"
        >
          <ArrowLeftIcon class="size-5" />
        </RouterLink>
        <div>
          <div class="flex flex-wrap items-center gap-2">
            <h1 class="text-2xl font-semibold tracking-tight text-slate-950">
              {{ editorTitle }}
            </h1>
            <DsBadge
              v-if="template"
              :tone="template.status === 'ACTIVE' ? 'positive' : 'warning'"
            >
              v{{ template.currentVersion }} · {{ template.status === 'ACTIVE' ? '已启用' : '草稿' }}
            </DsBadge>
          </div>
          <p class="mt-1 flex items-center gap-1.5 text-xs text-slate-500">
            <ClockIcon class="size-4" />
            <span v-if="saveState === 'saving'">正在安全保存…</span><span v-else-if="saveState === 'dirty'">有未保存更改</span><span v-else-if="saveState === 'saved'">已自动保存</span><span v-else>每次保存都会创建新版本</span>
          </p>
        </div>
      </div>
      <div
        v-if="canManage"
        class="flex flex-wrap gap-2"
      >
        <DsButton
          v-if="!isNew"
          variant="secondary"
          @click="openVersions"
        >
          <QueueListIcon class="size-4" />版本
        </DsButton>
        <DsButton
          v-if="!isNew"
          variant="secondary"
          @click="copyName = `${draft.name} 副本`; copyOpen = true"
        >
          <DocumentDuplicateIcon class="size-4" />复制
        </DsButton>
        <DsButton
          v-if="!isNew && auth.hasPermission('smtp:manage')"
          variant="secondary"
          @click="openTestSend"
        >
          <PaperAirplaneIcon class="size-4" />测试发送
        </DsButton>
        <DsButton
          :busy="saving"
          @click="save(true)"
        >
          <CheckCircleIcon class="size-4" />{{ isNew ? '创建模板' : '立即保存' }}
        </DsButton>
      </div>
    </div>

    <DsAlert
      v-if="errorMessage"
      tone="danger"
      title="需要处理"
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
    <DsAlert
      v-if="!hasUnsubscribe"
      tone="warning"
      title="缺少退订链接"
    >
      <ExclamationTriangleIcon class="mr-1 inline size-4" />建议在正文中加入 {{ unsubscribeToken }}，正式活动将要求此变量。
    </DsAlert>

    <div
      v-if="loading"
      class="grid min-h-96 place-items-center rounded-xl bg-white ring-1 ring-slate-200"
    >
      <p class="text-sm text-slate-500">
        正在加载安全编辑器…
      </p>
    </div>
    <div
      v-else
      class="grid items-start gap-5 2xl:grid-cols-[minmax(0,1.1fr)_minmax(25rem,.9fr)]"
    >
      <div class="min-w-0 space-y-5">
        <DsCard>
          <div class="flex items-center gap-2">
            <SparklesIcon class="size-5 text-brand-500" /><h2 class="font-semibold text-slate-900">
              模板信息
            </h2>
          </div>
          <div class="mt-5 grid gap-4 sm:grid-cols-[minmax(0,1fr)_11rem]">
            <DsInput
              id="template-name"
              v-model="draft.name"
              label="模板名称"
              placeholder="例如：AI 论文合作邀请"
            />
            <DsSelect
              id="template-status"
              v-model="draft.status"
              label="状态"
              :options="statusOptions"
            />
          </div>
          <label
            for="template-description"
            class="mt-4 block text-sm font-medium text-slate-900"
          >内部说明</label>
          <textarea
            id="template-description"
            v-model="draft.description"
            rows="2"
            class="mt-2 block w-full rounded-md bg-white px-3 py-2 text-sm outline-1 -outline-offset-1 outline-slate-300 focus:outline-2 focus:-outline-offset-2 focus:outline-brand-500"
            placeholder="仅内部可见，不会写入邮件"
          />
        </DsCard>

        <DsCard>
          <div class="flex items-center justify-between gap-3">
            <h2 class="font-semibold text-slate-900">
              邮件头信息
            </h2><span class="text-xs text-slate-400">禁止换行，防止 Header 注入</span>
          </div>
          <div class="mt-5 space-y-4">
            <DsInput
              id="template-subject"
              v-model="draft.content.subjectTemplate"
              label="主题"
              @focus="activeScalar = 'subjectTemplate'"
            />
            <div class="grid gap-4 sm:grid-cols-2">
              <DsInput
                id="template-from-name"
                v-model="draft.content.fromNameTemplate"
                label="发件人名称"
                @focus="activeScalar = 'fromNameTemplate'"
              /><DsInput
                id="template-reply"
                v-model="draft.content.replyTo"
                type="email"
                label="Reply-To"
              />
            </div>
          </div>
        </DsCard>

        <DsCard padding="none">
          <div class="flex flex-col gap-3 border-b border-slate-200 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h2 class="font-semibold text-slate-900">
                邮件正文
              </h2><p class="mt-0.5 text-xs text-slate-500">
                服务端保存时会再次净化 HTML。
              </p>
            </div>
            <div class="flex rounded-lg bg-slate-100 p-1 text-xs font-medium">
              <button
                v-for="mode in (['rich', 'html', 'text'] as const)"
                :key="mode"
                type="button"
                :class="['min-h-9 rounded-md px-3', editorMode === mode ? 'bg-white text-brand-700 shadow-xs' : 'text-slate-500']"
                @click="editorMode = mode"
              >
                {{ { rich: '富文本', html: 'HTML', text: '纯文本' }[mode] }}
              </button>
            </div>
          </div>
          <div class="p-5">
            <TemplateRichTextEditor
              v-show="editorMode === 'rich'"
              ref="richEditor"
              v-model="draft.content.htmlContent"
            />
            <textarea
              v-if="editorMode === 'html'"
              v-model="draft.content.htmlContent"
              rows="18"
              class="block w-full rounded-lg bg-slate-950 p-4 font-mono text-xs/6 text-slate-100 outline-2 -outline-offset-2 outline-slate-700 focus:outline-brand-400"
              spellcheck="false"
              aria-label="HTML 源码"
            />
            <div
              v-if="editorMode === 'text'"
              class="space-y-3"
            >
              <DsSwitch
                v-model="draft.content.autoGenerateText"
                label="自动从 HTML 生成纯文本"
                description="发送时仍会包含 multipart/alternative 的纯文本部分。"
              />
              <textarea
                v-model="draft.content.textContent"
                :disabled="draft.content.autoGenerateText"
                rows="16"
                class="block w-full rounded-lg bg-white p-4 font-mono text-sm/6 outline-1 -outline-offset-1 outline-slate-300 focus:outline-2 focus:-outline-offset-2 focus:outline-brand-500 disabled:bg-slate-50 disabled:text-slate-400"
                @focus="activeScalar = 'textContent'"
              />
            </div>
            <div class="mt-4 flex flex-wrap items-center gap-2">
              <input
                v-if="!isNew"
                ref="uploadInput"
                type="file"
                class="sr-only"
                accept="image/png,image/jpeg,image/gif,image/webp"
                @change="uploadAsset"
              >
              <DsButton
                v-if="!isNew"
                size="sm"
                variant="secondary"
                @click="uploadInput?.click()"
              >
                <PhotoIcon class="size-4" />上传图片
              </DsButton>
              <span
                v-if="isNew"
                class="text-xs text-slate-400"
              ><PaperClipIcon class="mr-1 inline size-4" />保存模板后可上传私有图片资产</span>
              <span
                v-else
                class="text-xs text-slate-400"
              >{{ assets.length }} 个私有图片资产</span>
            </div>
          </div>
        </DsCard>

        <DsCard>
          <div class="flex items-center justify-between gap-3">
            <h2 class="font-semibold text-slate-900">
              可用变量
            </h2><span class="text-xs text-slate-400">文本会转义，URL 必须是完整 HTTP(S)</span>
          </div>
          <div class="mt-4 flex flex-wrap gap-2">
            <button
              v-for="variable in TEMPLATE_VARIABLES"
              :key="variable"
              type="button"
              class="min-h-9 rounded-full bg-brand-50 px-3 font-mono text-xs font-medium text-brand-700 ring-1 ring-brand-100 hover:bg-brand-100"
              @click="insertVariable(variable)"
            >
              {{ variableLabels[variable] }}
            </button>
          </div>
        </DsCard>
      </div>

      <aside class="min-w-0 space-y-5 2xl:sticky 2xl:top-20">
        <DsCard padding="none">
          <div class="flex flex-col gap-3 border-b border-slate-200 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h2 class="font-semibold text-slate-900">
                安全预览
              </h2><p class="mt-0.5 text-xs text-slate-500">
                来自服务端净化和变量渲染结果
              </p>
            </div>
            <div class="flex items-center gap-1">
              <button
                type="button"
                :class="['preview-toggle', previewDevice === 'desktop' && 'preview-toggle-active']"
                aria-label="桌面预览"
                @click="previewDevice = 'desktop'"
              >
                <ComputerDesktopIcon class="size-4" />
              </button>
              <button
                type="button"
                :class="['preview-toggle text-xs font-bold', previewDevice === 'mobile' && 'preview-toggle-active']"
                aria-label="移动预览"
                @click="previewDevice = 'mobile'"
              >
                M
              </button>
              <button
                type="button"
                :class="['preview-toggle', darkPreview && 'preview-toggle-active']"
                aria-label="深色背景预览"
                @click="darkPreview = !darkPreview"
              >
                <MoonIcon class="size-4" />
              </button>
            </div>
          </div>
          <div :class="['min-h-[32rem] overflow-auto p-4 sm:p-6', darkPreview ? 'bg-slate-900' : 'bg-slate-100']">
            <div :class="[previewWidthClass(previewDevice), 'overflow-hidden rounded-lg bg-white shadow-lg transition-all']">
              <div class="border-b border-slate-100 px-5 py-4 text-xs text-slate-500">
                <p class="truncate">
                  <span class="font-medium text-slate-700">主题：</span>{{ preview?.rendered.subject || draft.content.subjectTemplate }}
                </p>
                <p class="mt-1 truncate">
                  <span class="font-medium text-slate-700">来自：</span>{{ preview?.rendered.fromName || draft.content.fromNameTemplate }}
                </p>
              </div>
              <iframe
                title="邮件正文预览"
                :srcdoc="previewHtml"
                sandbox=""
                class="h-[28rem] w-full border-0 bg-white"
              />
            </div>
          </div>
          <div class="flex items-center justify-between border-t border-slate-200 px-5 py-4">
            <div class="text-xs text-slate-500">
              <span class="font-medium text-slate-700">{{ preview?.contentSizeBytes ?? template?.contentSizeBytes ?? 0 }}</span> / 102,400 B · {{ bytesPercent }}%
            </div>
            <DsButton
              size="sm"
              variant="secondary"
              @click="refreshPreview(true)"
            >
              <CodeBracketIcon class="size-4" />刷新预览
            </DsButton>
          </div>
        </DsCard>

        <DsCard>
          <h2 class="font-semibold text-slate-900">
            预览样例值
          </h2>
          <p class="mt-1 text-xs text-slate-500">
            仅用于预览和内部测试，不会保存真实联系人数据。
          </p>
          <div class="mt-4 grid gap-3 sm:grid-cols-2 2xl:grid-cols-1">
            <DsInput
              v-for="variable in TEMPLATE_VARIABLES"
              :id="`sample-${variable}`"
              :key="variable"
              v-model="sampleValues[variable]"
              :label="variableLabels[variable]"
            />
          </div>
        </DsCard>

        <DsButton
          v-if="!isNew && canManage"
          variant="ghost"
          class="w-full text-red-600"
          @click="archiveOpen = true"
        >
          <ArchiveBoxIcon class="size-4" />归档模板
        </DsButton>
      </aside>
    </div>

    <DsModal
      :open="versionsOpen"
      title="版本历史"
      description="恢复操作会创建一个新的最新版本，不会覆盖历史内容。"
      @close="versionsOpen = false"
    >
      <ol class="max-h-96 space-y-3 overflow-auto">
        <li
          v-for="version in versions"
          :key="version.id"
          class="flex items-center justify-between gap-4 rounded-lg border border-slate-200 p-3"
        >
          <div>
            <p class="text-sm font-semibold text-slate-800">
              版本 {{ version.versionNumber }}<DsBadge
                v-if="version.versionNumber === template?.currentVersion"
                class="ml-2"
                tone="positive"
              >
                当前
              </DsBadge>
            </p><p class="mt-1 text-xs text-slate-500">
              {{ formatDate(version.createdAt) }} · {{ version.contentSizeBytes }} B
            </p>
          </div>
          <DsButton
            v-if="version.versionNumber !== template?.currentVersion"
            size="sm"
            variant="secondary"
            @click="restore(version.versionNumber)"
          >
            恢复
          </DsButton>
        </li>
      </ol>
    </DsModal>

    <DsModal
      :open="copyOpen"
      title="复制模板"
      description="副本会从当前版本创建独立草稿。"
      @close="copyOpen = false"
    >
      <DsInput
        id="copy-template-name"
        v-model="copyName"
        label="副本名称"
      />
      <template #actions>
        <DsButton
          variant="secondary"
          @click="copyOpen = false"
        >
          取消
        </DsButton><DsButton @click="duplicate">
          创建副本
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="testSendOpen"
      title="发送到本机 Mailpit"
      description="要求模板管理和 SMTP 管理权限；SMTP 接受不等于投递。"
      @close="testSendOpen = false"
    >
      <div class="space-y-4">
        <DsSelect
          id="test-smtp"
          v-model="selectedSmtpId"
          label="SMTP 账户"
          :options="smtpOptions"
        />
        <DsInput
          id="test-recipient"
          v-model="testRecipient"
          type="email"
          label="测试收件地址"
        />
        <DsAlert
          tone="info"
          title="仅内部诊断"
        >
          <BeakerIcon class="mr-1 inline size-4" />将使用右侧样例值生成 HTML 与纯文本两个 MIME 部分。
        </DsAlert>
      </div>
      <template #actions>
        <DsButton
          variant="secondary"
          @click="testSendOpen = false"
        >
          取消
        </DsButton><DsButton
          :busy="saving"
          :disabled="!selectedSmtpId"
          @click="testSend"
        >
          发送测试
        </DsButton>
      </template>
    </DsModal>

    <DsModal
      :open="archiveOpen"
      title="归档此模板？"
      description="已被邮件活动引用的模板不能删除；归档后默认列表不再显示。"
      @close="archiveOpen = false"
    >
      <template #actions>
        <DsButton
          variant="secondary"
          @click="archiveOpen = false"
        >
          取消
        </DsButton><DsButton
          variant="danger"
          @click="archive"
        >
          确认归档
        </DsButton>
      </template>
    </DsModal>
  </div>
</template>

<style scoped>
.preview-toggle { display: inline-grid; min-height: 2.25rem; min-width: 2.25rem; place-items: center; border-radius: .375rem; color: #64748b; }
.preview-toggle:hover, .preview-toggle-active { background: #e0e7ff; color: #4058d8; }
</style>
