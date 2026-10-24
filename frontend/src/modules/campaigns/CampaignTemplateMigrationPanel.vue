<script setup lang="ts">
import axios from 'axios'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { emailApi } from '@/modules/email/email.api'
import type { TemplateVersionView, TemplateView } from '@/modules/email/email.types'
import type { CampaignRecipient, CampaignView } from './campaigns.types'
import { templateMigrationsApi } from './template-migrations.api'
import type { MigrationPreview, MigrationResult } from './template-migrations.types'
import { createMigrationReconciliationStore, MIGRATION_RECOVERY_KEY, type MigrationReconciliationRecord, type ReconciliationSnapshot } from './template-migrations.reconciliation'

const props = withDefaults(defineProps<{
  sourceCampaignId: string
  campaign: CampaignView | null
  recipients: CampaignRecipient[]
  disabled?: boolean
}>(), { disabled: false })
const emit = defineEmits<{ completed: [result: MigrationResult]; editing: [value: boolean] }>()
const auth = useAuthStore()
const open = ref(false)
const templates = ref<TemplateView[]>([])
const versions = ref<TemplateVersionView[]>([])
const templateId = ref('')
const versionId = ref('')
const selected = ref<string[]>([])
const preview = ref<MigrationPreview | null>(null)
const result = ref<MigrationResult | null>(null)
const error = ref('')
const catalogError = ref('')
const loading = ref(false)
const busy = ref(false)
// Metadata is durably reserved before POST; restoring it never grants execution permission.
const pending = ref<MigrationReconciliationRecord | null>(null)
const reconciliation = createMigrationReconciliationStore()
const recoveryReady = ref(false)
const recoveryError = ref('')
let request = 0
let catalogRequest = 0
let alive = true
const authorized = computed(() => auth.hasEveryPermission(['campaign:read', 'campaign:create']))
const sourceReady = computed(() => props.campaign?.id === props.sourceCampaignId && !props.disabled)
const frozen = computed(() => busy.value || Boolean(pending.value) || !recoveryReady.value || Boolean(recoveryError.value))
const templateOptions = computed(() => [{ value: '', label: '请选择目标模板', disabled: true }, ...templates.value.map((t) => ({ value: t.id, label: t.name, disabled: t.status === 'ARCHIVED' }))])
const versionOptions = computed(() => [{ value: '', label: '请选择不可变版本', disabled: true }, ...versions.value.map((v) => ({ value: v.id, label: `v${v.versionNumber} · ${v.id}` }))])
const selection = computed(() => [...selected.value].sort())
const canPreview = computed(() => authorized.value && sourceReady.value && !frozen.value && !loading.value && Boolean(templateId.value && versionId.value) && selected.value.length <= 120)
const canCommit = computed(() => canPreview.value && Boolean(preview.value?.ready))
const canReadback = computed(() => pending.value && pending.value.actor === auth.user?.id && auth.hasPermission('campaign:read') && !busy.value)
const summary = computed(() => ({ MIGRATED: '独立草稿已创建；未提交审核、未排期、未发送。', CONFIG_UPDATED: '空草稿配置已更新；没有迁移邮件。', NO_MAIL: '没有可迁移邮件；未创建新活动。', BLOCKED: '迁移被安全条件阻止；未创建新活动。' })[result.value?.status ?? 'NO_MAIL'])

function applyRecovery(snapshot: ReconciliationSnapshot): void {
  if (!alive) return
  recoveryReady.value = true
  if (snapshot.kind === 'blocked') {
    invalidate()
    recoveryError.value = '持久恢复记录或跨标签页锁不可用、损坏或已变化；迁移已暂停。请保留原记录并核验，不要清除记录或重新提交。'
    emit('editing', open.value || Boolean(pending.value))
  } else if (snapshot.kind === 'pending') {
    invalidate()
    pending.value = snapshot.record
    emit('editing', true)
  }
}
async function refreshRecovery(): Promise<void> { applyRecovery(await reconciliation.refresh()) }
function storageChanged(event: { key?: string | null }): void {
  if (event.key === MIGRATION_RECOVERY_KEY || event.key === null) void refreshRecovery()
}

function invalidate(): void { request++; preview.value = null }
watch([() => props.sourceCampaignId, () => auth.user?.id], () => {
  invalidate()
  result.value = null
  selected.value = []
  void refreshRecovery()
})
watch(() => props.campaign?.lockVersion, invalidate)
watch([templateId, versionId, selection], () => { invalidate(); result.value = null })
watch(templateId, async (id) => {
  versionId.value = ''; versions.value = []; catalogError.value = ''
  const token = ++catalogRequest
  if (!id) return
  loading.value = true
  try {
    const value = await emailApi.listTemplateVersions(id)
    if (alive && token === catalogRequest) versions.value = value
  } catch { if (alive && token === catalogRequest) catalogError.value = '模板版本加载失败，请重新选择模板。' }
  finally { if (alive && token === catalogRequest) loading.value = false }
})

async function openPanel(): Promise<void> {
  if (!authorized.value || !sourceReady.value || frozen.value || open.value) return
  open.value = true; emit('editing', true); loading.value = true; catalogError.value = ''
  const token = ++catalogRequest
  try {
    const all: TemplateView[] = []
    let page = 1
    let pages = 1
    do {
      const response = await emailApi.listTemplates(page, 100)
      if (!alive || token !== catalogRequest) return
      all.push(...response.items); pages = response.totalPages; page++
    } while (page <= pages)
    templates.value = all
    if (!all.length) catalogError.value = '没有可用模板，请先配置模板后再迁移。'
  } catch { if (alive && token === catalogRequest) catalogError.value = '模板加载失败，请确认模板查看权限后关闭重试。' }
  finally { if (alive && token === catalogRequest) loading.value = false }
}
function closePanel(): void {
  if (frozen.value) return
  catalogRequest++; loading.value = false; invalidate(); open.value = false; emit('editing', false)
}
function toggle(id: string, checked: boolean): void {
  if (frozen.value || !sourceReady.value) return
  selected.value = checked ? [...new Set([...selected.value, id])] : selected.value.filter((value) => value !== id)
}
function sameIds(left: string[], right: string[]): boolean {
  return left.length === right.length && [...left].sort().every((id, i) => id === [...right].sort()[i])
}
async function runPreview(): Promise<void> {
  if (!canPreview.value) return
  invalidate(); error.value = ''; const token = request
  const source = props.sourceCampaignId; const actor = auth.user?.id
  const command = { targetTemplateId: templateId.value, targetTemplateVersionId: versionId.value, recipientIds: selection.value }
  busy.value = true
  try {
    const value = await templateMigrationsApi.preview(source, command)
    if (!alive || token !== request || actor !== auth.user?.id) return
    if (value.sourceCampaignId !== source || value.expectedLockVersion !== props.campaign?.lockVersion || value.targetTemplateId !== command.targetTemplateId || value.targetTemplateVersionId !== command.targetTemplateVersionId || !sameIds(value.recipientIds, command.recipientIds)) {
      error.value = '预检与当前选择不匹配，请刷新后重新预检。'; return
    }
    preview.value = value
  } catch { if (alive && token === request) error.value = '迁移预检失败，请检查权限、功能开关与当前版本后重试。' }
  finally { if (alive) busy.value = false }
}
function matches(value: MigrationResult, original: NonNullable<typeof pending.value>): boolean {
  const c = original.command
  return Boolean(value.id && value.sourceCampaignId === original.source && value.targetTemplateId === c.targetTemplateId && value.targetTemplateVersionId === c.targetTemplateVersionId && value.selectedCount === c.recipientIds.length && ['MIGRATED', 'CONFIG_UPDATED', 'BLOCKED', 'NO_MAIL'].includes(value.status)
    && (value.status === 'MIGRATED' ? value.targetCampaignId && value.targetCampaignId !== original.source && value.migratedCount > 0 && value.migratedCount === value.selectedCount : value.targetCampaignId === null && value.migratedCount === 0))
}
async function accept(value: MigrationResult, original: NonNullable<typeof pending.value>, matchingReadback = false): Promise<void> {
  if (auth.user?.id !== original.actor || !matches(value, original)) {
    error.value = '读回结果与原提交不匹配，仍待对账；请保持原账号核验，不要重复提交。'; return
  }
  if (!await reconciliation.clearMatched(original, () => alive && auth.user?.id === original.actor, matchingReadback)) {
    applyRecovery(await reconciliation.refresh())
    error.value = '已取得结果，但原恢复记录无法安全清除，仍待对账；不要重新提交。'
    return
  }
  if (!alive || auth.user?.id !== original.actor) return
  recoveryError.value = ''
  result.value = value; pending.value = null; preview.value = null
  emit('editing', open.value)
  emit('completed', value)
}
async function commit(): Promise<void> {
  if (!canCommit.value || !preview.value || !auth.user?.id) return
  const p = preview.value
  const token = request
  const original: MigrationReconciliationRecord = { protocol: 'campaign-template-migration', version: 1, actor: auth.user.id, source: p.sourceCampaignId, command: { expectedLockVersion: p.expectedLockVersion, targetTemplateId: p.targetTemplateId, targetTemplateVersionId: p.targetTemplateVersionId, expectedTemplateLockVersion: p.expectedTemplateLockVersion, recipientIds: [...p.recipientIds], expectedMembershipDigest: p.expectedMembershipDigest, idempotencyKey: globalThis.crypto.randomUUID() } }
  busy.value = true; error.value = ''
  try {
    const reservation = await reconciliation.reserve(original)
    // Lock is released before any network call. A losing tab only restores readback metadata.
    if (!alive) return
    if (!reservation.created) { applyRecovery(reservation.snapshot); return }
    pending.value = original
    emit('editing', true)
    if (token !== request || auth.user?.id !== original.actor || !sourceReady.value || props.sourceCampaignId !== original.source) {
      invalidate(); error.value = '提交前页面或账号已变化，原记录已保留待核验；未重新提交。'; return
    }
    const value = await templateMigrationsApi.migrate(original.source, original.command)
    if (alive) await accept(value, original)
  } catch (cause) {
    if (!alive) return
    const status = axios.isAxiosError(cause) ? cause.response?.status : undefined
    if (status && [400, 401, 403, 404, 409].includes(status)) {
      invalidate()
      // Only this exact original POST's definite noncommitting rejection permits conditional release.
      if (await reconciliation.clearMatched(original, () => alive && auth.user?.id === original.actor)) {
        pending.value = null
        error.value = status === 409 ? '版本或名单已变化，旧预检已失效；请刷新并重新预检。' : '迁移被拒绝，未创建新草稿；请检查权限与请求后重新预检。'
      } else {
        applyRecovery(await reconciliation.refresh())
        error.value = '迁移被拒绝，但原恢复记录无法安全清除；仍待对账，不可重新提交。'
      }
    } else {
      preview.value = null
      error.value = '提交结果未知，仍待对账。只可按原键读回，不可重复提交。'
    }
  } finally { if (alive) busy.value = false }
}
async function readback(): Promise<void> {
  if (!canReadback.value || !pending.value) return
  const original = pending.value; busy.value = true; error.value = ''
  try {
    const value = await templateMigrationsApi.getByKey(original.source, original.command.idempotencyKey)
    if (alive) await accept(value, original, true)
  } catch { if (alive) error.value = '原键暂未取得结果，仍待对账；请稍后读回，不要重复提交。' }
  finally { if (alive) busy.value = false }
}
onMounted(() => { globalThis.addEventListener('storage', storageChanged); void refreshRecovery() })
onBeforeUnmount(() => { alive = false; request++; catalogRequest++; globalThis.removeEventListener('storage', storageChanged) })
</script>

<template>
  <DsCard data-testid="migration-panel">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div class="min-w-0">
        <h2 class="text-base font-semibold text-slate-900">
          迁移未发送名单到新模板
        </h2>
        <p class="mt-1 max-w-prose text-sm/6 text-slate-600">
          保留原活动与发送、试发、回传证据。仅按精确名单创建独立草稿，不触发审核或发送。
        </p>
      </div>
      <DsButton
        v-if="authorized && !open && !pending && !recoveryError"
        variant="secondary"
        data-testid="migration-open"
        :disabled="!sourceReady || frozen"
        @click="openPanel"
      >
        选择迁移名单
      </DsButton>
      <DsButton
        v-if="open && !pending"
        variant="ghost"
        :disabled="busy"
        @click="closePanel"
      >
        关闭迁移面板
      </DsButton>
    </div>
    <DsAlert
      v-if="!authorized"
      class="mt-4"
      tone="warning"
    >
      缺少活动创建权限或活动查看权限；可以查看已有证据，不能预检或提交迁移。
    </DsAlert>
    <DsAlert
      v-if="recoveryError"
      class="mt-4"
      tone="danger"
      aria-live="polite"
    >
      {{ recoveryError }}
    </DsAlert>
    <div
      v-if="open || pending || result"
      class="mt-5 space-y-4"
    >
      <DsAlert
        v-if="error"
        tone="danger"
        aria-live="polite"
      >
        {{ error }}
      </DsAlert>
      <DsAlert
        v-if="pending"
        tone="warning"
        title="原提交待对账"
      >
        <p class="break-all">
          来源 {{ pending.source }} · 原键 {{ pending.command.idempotencyKey }}
        </p>
        <p
          v-if="pending.actor !== auth.user?.id"
          class="mt-2"
        >
          当前账号与原提交账号不同，请恢复原账号后读回。
        </p>
        <DsButton
          class="mt-3"
          data-testid="migration-readback"
          variant="secondary"
          :disabled="!canReadback"
          :busy="busy"
          @click="readback"
        >
          按原键读回结果
        </DsButton>
      </DsAlert>
      <template v-if="!pending && !recoveryError">
        <div class="grid min-w-0 gap-4 sm:grid-cols-2">
          <DsSelect
            id="migration-template"
            v-model="templateId"
            label="目标模板"
            :options="templateOptions"
            :error="catalogError"
            :disabled="frozen || loading || !sourceReady"
          />
          <DsSelect
            id="migration-version"
            v-model="versionId"
            label="冻结模板版本"
            :options="versionOptions"
            :disabled="frozen || loading || !sourceReady"
          />
        </div>
        <p
          class="text-sm text-slate-600"
          aria-live="polite"
        >
          {{ loading ? '正在加载模板…' : `跨页已选择 ${selected.length} 位（最多 120 位、100 篇论文）；翻页不会丢失选择。` }}
        </p>
        <fieldset
          :disabled="frozen || !sourceReady"
          aria-describedby="migration-selection-help"
          class="space-y-2"
        >
          <legend class="mb-2 text-sm font-medium text-slate-900">
            当前页精确收件人
          </legend>
          <p
            id="migration-selection-help"
            class="mb-3 text-xs/5 text-slate-600"
          >
            只迁移勾选的 ID；空名单不会自动选择全部，服务端将校验正式尝试与安全状态。
          </p>
          <label
            v-for="r in recipients"
            :key="r.id"
            class="flex min-h-11 items-start gap-3 text-sm/6 text-slate-700"
          >
            <input
              type="checkbox"
              class="mt-1 size-4 shrink-0 accent-brand-500"
              :data-testid="`migration-select-${r.id}`"
              :checked="selected.includes(r.id)"
              @change="toggle(r.id, ($event.target as HTMLInputElement).checked)"
            >
            <span class="min-w-0 break-words">{{ r.authorName || '未确认作者' }} · {{ r.paperTitle || '来源论文不可用' }}</span>
          </label>
          <p
            v-if="!recipients.length"
            class="text-sm text-slate-600"
          >
            当前页没有收件人；可预检空草稿配置，不会生成邮件。
          </p>
        </fieldset>
        <DsAlert
          v-if="selected.length > 120"
          tone="warning"
        >
          选择超过 120 位，请取消部分选择后预检。
        </DsAlert>
        <DsButton
          data-testid="migration-preview"
          variant="secondary"
          :disabled="!canPreview"
          :busy="busy"
          @click="runPreview"
        >
          预检精确名单
        </DsButton>
        <div
          v-if="preview"
          data-testid="migration-preview-result"
          class="space-y-3 border-t border-slate-200 pt-4"
          aria-live="polite"
        >
          <p class="break-all text-sm font-medium text-slate-900">
            已冻结版本：{{ preview.targetTemplateVersionId }}
          </p>
          <dl class="grid grid-cols-2 gap-3 text-sm tabular-nums sm:grid-cols-4">
            <div>
              <dt class="text-slate-600">
                历史选中
              </dt><dd class="mt-1 font-semibold">
                {{ preview.counts.historicalSelected }}
              </dd>
            </div>
            <div>
              <dt class="text-slate-600">
                可行动排队
              </dt><dd class="mt-1 font-semibold">
                {{ preview.counts.actionableQueued }}
              </dd>
            </div>
            <div>
              <dt class="text-slate-600">
                已转移
              </dt><dd class="mt-1 font-semibold">
                {{ preview.counts.transferred }}
              </dd>
            </div>
            <div>
              <dt class="text-slate-600">
                迁移阻止
              </dt><dd class="mt-1 font-semibold">
                {{ preview.counts.migrationBlocked }}
              </dd>
            </div>
          </dl>
          <DsAlert
            v-if="!preview.ready"
            tone="warning"
            title="迁移条件未满足"
          >
            没有创建新草稿。<ul class="mt-2 space-y-1">
              <li
                v-for="reason in preview.reasons"
                :key="reason.code"
                class="break-all"
              >
                {{ reason.code }}：{{ reason.count }}
              </li>
            </ul>
          </DsAlert>
          <DsButton
            v-if="preview.ready"
            data-testid="migration-commit"
            :disabled="!canCommit"
            :busy="busy"
            @click="commit"
          >
            确认迁移（仅草稿）
          </DsButton>
        </div>
      </template>
      <DsAlert
        v-if="result"
        :tone="result.status === 'MIGRATED' ? 'success' : 'info'"
        aria-live="polite"
      >
        <p>{{ summary }}</p>
        <RouterLink
          v-if="result.status === 'MIGRATED' && result.targetCampaignId"
          :to="`/email/campaigns/${result.targetCampaignId}`"
          class="mt-2 inline-block font-semibold underline underline-offset-4"
        >
          查看新草稿
        </RouterLink>
        <ul
          v-if="result.reasons.length"
          class="mt-2 space-y-1"
        >
          <li
            v-for="reason in result.reasons"
            :key="reason.code"
            class="break-all"
          >
            {{ reason.code }}：{{ reason.count }}
          </li>
        </ul>
      </DsAlert>
    </div>
  </DsCard>
</template>
