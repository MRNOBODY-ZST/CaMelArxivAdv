<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import DsSelect from '@/components/design-skill/DsSelect.vue'
import DsSwitch from '@/components/design-skill/DsSwitch.vue'
import {
  adminApi,
  administrationErrorMessage,
  isAdministrationConflict,
  type PersonalizationAuthScheme,
  type PersonalizationConnectionResult,
  type PersonalizationProvider,
  type PersonalizationSettings,
  type PersonalizationSettingsRequest,
} from '@/modules/admin/admin.api'
import { normalizedPersonalizationOrigin as normalizedDestination } from '@/modules/admin/personalization-origin'

const emit = defineEmits<{ refreshed: [] }>()
const providerOptions = [
  { label: 'OpenAI 兼容接口', value: 'openai' },
  { label: 'Anthropic Messages', value: 'anthropic' },
] as const
const authOptions = [
  { label: 'Bearer Token', value: 'bearer' },
  { label: 'x-api-key', value: 'x-api-key' },
] as const

const form = reactive({
  enabled: false,
  provider: 'openai' as PersonalizationProvider,
  model: '',
  apiOrigin: '',
  authScheme: 'bearer' as PersonalizationAuthScheme,
  apiKey: '',
})
const persisted = ref<PersonalizationSettings | null>(null)
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const loadError = ref('')
const saveMessage = ref('')
const saveError = ref('')
const testResult = ref<PersonalizationConnectionResult | null>(null)
const testError = ref('')
const conflict = ref(false)
const fieldErrors = reactive({ model: '', apiOrigin: '', apiKey: '' })

const mutationDisabled = computed(() => loading.value || !persisted.value || conflict.value)

watch(form, () => {
  saveMessage.value = ''
  testResult.value = null
  testError.value = ''
}, { flush: 'sync' })

function applySettings(value: PersonalizationSettings): void {
  persisted.value = value
  form.enabled = value.enabled
  form.provider = value.provider
  form.model = value.model
  form.apiOrigin = value.apiOrigin
  form.authScheme = value.authScheme
  form.apiKey = ''
}

async function load(): Promise<void> {
  if (loading.value && persisted.value) return
  loading.value = true
  loadError.value = ''
  saveError.value = ''
  saveMessage.value = ''
  testError.value = ''
  testResult.value = null
  try {
    applySettings(await adminApi.getPersonalizationSettings())
    conflict.value = false
  } catch (cause) {
    loadError.value = `配置加载失败：${administrationErrorMessage(cause, '请稍后重试。')}`
  } finally {
    loading.value = false
  }
}

function validate(forConnectionTest = false): boolean {
  fieldErrors.model = form.model.trim() ? '' : '请输入模型名称。'
  fieldErrors.apiOrigin = normalizedDestination(form.apiOrigin) ? '' : '请输入公网 HTTPS API Origin，且不要包含凭据、查询参数或片段。'
  const baseline = persisted.value
  const changedDestination = baseline && (
    form.provider !== baseline.provider || normalizedDestination(form.apiOrigin) !== normalizedDestination(baseline.apiOrigin)
  )
  fieldErrors.apiKey = ''
  if (!form.apiKey.trim()) {
    if (changedDestination) fieldErrors.apiKey = '更换服务商或 API Origin 时必须输入新密钥。'
    else if (!baseline?.keyConfigured && forConnectionTest) fieldErrors.apiKey = '连接测试需要 API 密钥，请输入新密钥。'
    else if (!baseline?.keyConfigured && form.enabled) fieldErrors.apiKey = '当前目标尚未配置密钥，请输入新密钥。'
  }
  return !fieldErrors.model && !fieldErrors.apiOrigin && !fieldErrors.apiKey
}

function payload(): PersonalizationSettingsRequest {
  const baseline = persisted.value
  if (!baseline) throw new Error('Settings have not loaded')
  return {
    version: baseline.version,
    enabled: form.enabled,
    provider: form.provider,
    model: form.model.trim(),
    apiOrigin: normalizedDestination(form.apiOrigin) ?? form.apiOrigin.trim(),
    authScheme: form.authScheme,
    ...(form.apiKey.trim() ? { apiKey: form.apiKey.trim() } : {}),
  }
}

async function save(): Promise<void> {
  if (saving.value || testing.value || mutationDisabled.value || !validate()) return
  saving.value = true
  saveError.value = ''
  saveMessage.value = ''
  testError.value = ''
  testResult.value = null
  try {
    applySettings(await adminApi.updatePersonalizationSettings(payload()))
    saveMessage.value = '设置已保存；仅新建生成任务使用新配置，已创建任务继续使用原快照。'
    emit('refreshed')
  } catch (cause) {
    if (isAdministrationConflict(cause)) {
      conflict.value = true
      saveError.value = '设置已被其他管理员修改。请显式重新加载后再编辑，系统不会自动重试。'
    } else {
      saveError.value = administrationErrorMessage(cause, '设置保存失败，请稍后重试。')
    }
  } finally {
    saving.value = false
  }
}

async function testConnection(): Promise<void> {
  if (saving.value || testing.value || mutationDisabled.value || !validate(true)) return
  testing.value = true
  testError.value = ''
  testResult.value = null
  try {
    testResult.value = await adminApi.testPersonalizationSettings(payload())
  } catch (cause) {
    if (isAdministrationConflict(cause)) {
      conflict.value = true
      testError.value = '设置已被其他管理员修改。请显式重新加载后再测试。'
    } else {
      testError.value = administrationErrorMessage(cause, '连接测试失败，请检查配置。')
    }
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<template>
  <DsCard>
    <form
      class="space-y-8"
      @submit.prevent="save"
    >
      <div class="border-b border-slate-200 pb-8">
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 class="text-base/7 font-semibold text-slate-950">
              个性化模型配置
            </h2>
            <p class="mt-1 max-w-3xl text-sm/6 text-slate-600">
              保存后仅影响新创建的生成任务；旧任务继续使用创建时冻结的配置。
            </p>
          </div>
          <div
            v-if="persisted"
            class="flex gap-2"
          >
            <DsBadge
              :tone="persisted.keyConfigured ? 'positive' : 'warning'"
              dot
            >
              {{ persisted.keyConfigured ? '密钥已配置' : '密钥未配置' }}
            </DsBadge>
            <DsBadge tone="neutral">
              {{ persisted.source === 'DATABASE' ? '数据库' : '环境变量' }}
            </DsBadge>
          </div>
        </div>

        <div
          v-if="loading"
          class="mt-8 h-44 animate-pulse rounded-lg bg-slate-100"
          aria-label="个性化配置加载中"
        />
        <div
          v-else
          class="mt-8 grid grid-cols-1 gap-x-6 gap-y-6 sm:grid-cols-6"
        >
          <div class="sm:col-span-6">
            <DsSwitch
              v-model="form.enabled"
              label="启用个性化生成"
              description="关闭后，新任务不会调用模型服务。"
              :disabled="mutationDisabled || saving || testing"
            />
          </div>
          <div class="sm:col-span-3">
            <DsSelect
              id="personalization-provider"
              v-model="form.provider"
              label="服务商"
              :options="providerOptions"
              :disabled="mutationDisabled || saving || testing"
            />
          </div>
          <div class="sm:col-span-3">
            <DsSelect
              id="personalization-auth-scheme"
              v-model="form.authScheme"
              label="认证方式"
              :options="authOptions"
              :disabled="mutationDisabled || saving || testing"
            />
          </div>
          <div class="sm:col-span-3">
            <DsInput
              id="personalization-model"
              v-model="form.model"
              label="模型"
              placeholder="例如 gpt-5.6-luna"
              :error="fieldErrors.model"
              :disabled="mutationDisabled || saving || testing"
            />
          </div>
          <div class="sm:col-span-3">
            <DsInput
              id="personalization-api-origin"
              v-model="form.apiOrigin"
              type="url"
              label="API Origin"
              description="仅允许公网 HTTPS；根路径会由服务端规范化为 /v1。"
              :error="fieldErrors.apiOrigin"
              :disabled="mutationDisabled || saving || testing"
            />
          </div>
          <div class="sm:col-span-6">
            <DsInput
              id="personalization-api-key"
              v-model="form.apiKey"
              type="password"
              label="新 API 密钥"
              autocomplete="new-password"
              description="留空会保留同一服务商和 Origin 的现有密钥。系统绝不会回显密钥。"
              :error="fieldErrors.apiKey"
              :disabled="mutationDisabled || saving || testing"
            />
          </div>
        </div>
      </div>

      <DsAlert
        v-if="loadError"
        tone="danger"
      >
        {{ loadError }}
      </DsAlert>
      <DsAlert
        v-if="conflict"
        tone="warning"
        title="需要重新加载"
      >
        设置冲突后保存和测试均已锁定，避免覆盖其他管理员的更改。
      </DsAlert>
      <DsAlert
        v-if="saveError"
        tone="danger"
      >
        {{ saveError }}
      </DsAlert>
      <DsAlert
        v-if="saveMessage"
        tone="success"
      >
        {{ saveMessage }}
      </DsAlert>
      <DsAlert
        v-if="testError"
        tone="danger"
      >
        {{ testError }}
      </DsAlert>
      <DsAlert
        v-if="testResult"
        :tone="testResult.success ? 'success' : 'warning'"
        title="连接测试结果"
      >
        {{ testResult.message }} · {{ testResult.provider }} / {{ testResult.model }} · {{ testResult.latencyMs }} ms
      </DsAlert>

      <div class="flex flex-col-reverse justify-between gap-4 sm:flex-row sm:items-center">
        <p class="text-xs/5 text-slate-500">
          连接测试会发起一次小型模型调用，不会创建生成任务，也不会发送邮件。
        </p>
        <div class="flex flex-wrap justify-end gap-3">
          <DsButton
            data-testid="reload-personalization"
            variant="ghost"
            :busy="loading"
            :disabled="saving || testing"
            @click="load"
          >
            重新加载
          </DsButton>
          <DsButton
            data-testid="test-personalization"
            variant="secondary"
            :busy="testing"
            :disabled="mutationDisabled || saving"
            @click="testConnection"
          >
            测试连接
          </DsButton>
          <DsButton
            data-testid="save-personalization"
            type="submit"
            :busy="saving"
            :disabled="mutationDisabled || testing"
          >
            保存设置
          </DsButton>
        </div>
      </div>
    </form>
  </DsCard>
</template>
