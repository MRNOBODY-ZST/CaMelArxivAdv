<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsCheckbox from '@/components/design-skill/DsCheckbox.vue'
import { mailTrackingApi, mailTrackingErrorMessage } from '@/modules/email/mail-tracking.api'
import type { MailTrackingStatus } from '@/modules/email/mail-tracking.types'

const props = withDefaults(defineProps<{ id?: string; modelValue: boolean }>(), { id: 'mail-track-opens' })
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const loading = ref(true)
const error = ref('')
const status = ref<MailTrackingStatus | null>(null)
const trackingAvailable = computed(() => status.value?.enabled === true && !error.value)

watch(trackingAvailable, (available) => {
  if (!available && props.modelValue) emit('update:modelValue', false)
}, { immediate: true })

onMounted(() => {
  void loadStatus()
})

async function loadStatus(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    status.value = await mailTrackingApi.getStatus()
  } catch (cause) {
    status.value = null
    error.value = mailTrackingErrorMessage(cause, '图片加载检测配置暂时无法加载；本次邮件仍可不检测发送。')
  } finally {
    loading.value = false
  }
}

function update(value: boolean): void {
  emit('update:modelValue', trackingAvailable.value ? value : false)
}
</script>

<template>
  <div class="space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-4">
    <DsCheckbox
      :id="id"
      :model-value="trackingAvailable ? modelValue : false"
      :disabled="!trackingAvailable"
      label="检测图片加载与链接点击（可选）"
      description="记录远程图片请求和安全重定向回传；两者都不能证明人工阅读或点击。"
      @update:model-value="update"
    />
    <DsAlert
      v-if="loading"
      tone="info"
      title="正在检查图片加载检测配置"
    >
      配置确认前不会启用检测；不检测发送不受影响。
    </DsAlert>
    <DsAlert
      v-else-if="error"
      tone="warning"
      title="图片加载检测不可用"
    >
      {{ error }}
    </DsAlert>
    <DsAlert
      v-else-if="!status?.enabled"
      tone="warning"
      title="当前配置未启用图片加载检测"
    >
      此次测试仍可不检测发送；已选检测会保持关闭。
    </DsAlert>
    <DsAlert
      v-else-if="status.callbackScope === 'LOCAL_ONLY'"
      tone="warning"
      title="回传仅限本机或私有网络"
    >
      回传地址：{{ status.callbackBaseUrl }}。外部收件箱通常无法回传图片加载；该状态不代表公网可达。
    </DsAlert>
    <DsAlert
      v-else
      tone="info"
      title="公网 HTTPS 回传已配置"
    >
      回传地址：{{ status.callbackBaseUrl }}。实际可达性以发送记录收到的图片加载与链接点击回传为准。
    </DsAlert>
  </div>
</template>
