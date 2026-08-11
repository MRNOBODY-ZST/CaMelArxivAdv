<script setup lang="ts">
import { CheckCircleIcon, Cog6ToothIcon, XCircleIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { RuntimeStatus } from '@/modules/campaigns/campaigns.types'

const loading = ref(true)
const error = ref('')
const runtime = ref<RuntimeStatus | null>(null)
const runtimeItems = computed(() => runtime.value ? [
  { label: 'Ray 分布式计算', ready: runtime.value.rayConfigured, readyLabel: '已连接', blockedLabel: '未连接' },
  { label: 'Kafka 消息平台', ready: runtime.value.kafkaConfigured, readyLabel: '已连接', blockedLabel: '未连接' },
  { label: '公网 SMTP 外发', ready: runtime.value.liveSmtpAllowed, readyLabel: '允许', blockedLabel: '禁止（安全）' },
  { label: '公网 IMAP / POP3', ready: runtime.value.publicMailboxAllowed, readyLabel: '允许（强制 TLS）', blockedLabel: '禁止' },
] : [])

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    runtime.value = await campaignsApi.runtimeStatus()
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '运行配置加载失败。')
  } finally {
    loading.value = false
  }
}

function providerName(provider: string): string {
  return provider.toLowerCase() === 'openai' ? 'OpenAI' : provider
}

onMounted(load)
</script>

<template>
  <section
    data-testid="system-settings-view"
    aria-labelledby="system-settings-title"
    class="space-y-6"
  >
    <header>
      <h1
        id="system-settings-title"
        class="text-2xl font-semibold tracking-tight text-slate-950"
      >
        系统设置
      </h1><p class="mt-2 max-w-2xl text-sm/6 text-slate-600">
        查看个性化生成、Ray、Kafka 与邮件协议安全开关；接口不返回任何密钥或凭据。
      </p>
    </header>
    <DsAlert
      v-if="error"
      tone="danger"
    >
      {{ error }}
    </DsAlert>
    <div
      v-if="loading"
      class="h-48 animate-pulse rounded-lg bg-slate-100"
      aria-label="运行状态加载中"
    />
    <template v-else-if="runtime">
      <DsAlert
        v-if="!runtime.generationReady"
        tone="warning"
        title="个性化生成当前未启用"
      >
        本机未配置邮件生成服务密钥或运行依赖尚未就绪。可以继续管理论文、联系人、分组、模板与活动，但生成按钮会保持禁用。
      </DsAlert>
      <DsAlert
        v-else
        tone="success"
        title="个性化生成已就绪"
      >
        活动可提交到 Kafka，并由 Ray 集群分发生成任务。
      </DsAlert>
      <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <DsCard padding="sm">
          <div class="flex items-center justify-between gap-3">
            <p class="text-sm font-semibold text-slate-900">
              生成服务
            </p><DsBadge
              :tone="runtime.personalizationEnabled ? 'positive' : 'warning'"
              dot
            >
              {{ runtime.personalizationEnabled ? '已启用' : '未启用' }}
            </DsBadge>
          </div>
          <p class="mt-3 text-sm text-slate-600">
            {{ providerName(runtime.provider) }}
          </p><p class="mt-1 font-mono text-xs text-slate-500">
            {{ runtime.model }}
          </p>
        </DsCard>
        <DsCard
          v-for="item in runtimeItems"
          :key="item.label"
          padding="sm"
        >
          <div class="flex items-start gap-3">
            <component
              :is="item.ready ? CheckCircleIcon : XCircleIcon"
              :class="['mt-0.5 size-5 shrink-0', item.ready ? 'text-emerald-600' : 'text-rose-500']"
            />
            <div>
              <p class="text-sm font-semibold text-slate-900">
                {{ item.label }}
              </p><p :class="['mt-1 text-xs', item.ready ? 'text-emerald-700' : 'text-slate-500']">
                {{ item.ready ? item.readyLabel : item.blockedLabel }}
              </p>
            </div>
          </div>
        </DsCard>
      </div>
      <DsCard>
        <div class="flex items-start gap-3">
          <span class="grid size-10 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-600"><Cog6ToothIcon class="size-5" /></span><div>
            <h2 class="text-sm font-semibold text-slate-900">
              安全配置说明
            </h2><p class="mt-1 text-sm/6 text-slate-500">
              状态页仅报告开关、服务名和模型名。密钥只通过运行环境注入，不会写入前端包或日志。当前 SMTP 外发为“{{ runtime.liveSmtpAllowed ? '允许' : '禁止' }}”，IMAP/POP3 公网连接为“{{ runtime.publicMailboxAllowed ? '允许（强制 TLS）' : '禁止' }}”。
            </p>
          </div>
        </div>
      </DsCard>
    </template>
  </section>
</template>
