<script setup lang="ts">
import { ArrowDownTrayIcon, PaperAirplaneIcon } from '@heroicons/vue/24/outline'
import { computed, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import MailboxAccountsPanel from '@/modules/email/MailboxAccountsPanel.vue'
import SmtpAccountsView from '@/modules/email/SmtpAccountsView.vue'

type MailTab = 'smtp' | 'mailbox'

const auth = useAuthStore()
const tabs = computed(() => [
  auth.hasPermission('smtp:read')
    ? { id: 'smtp' as const, name: '发件 SMTP', icon: PaperAirplaneIcon, description: '出站连接与速率' }
    : null,
  auth.hasPermission('mailbox:read')
    ? { id: 'mailbox' as const, name: '收件 IMAP / POP3', icon: ArrowDownTrayIcon, description: '只读邮件头预览' }
    : null,
].filter((tab): tab is NonNullable<typeof tab> => tab !== null))
const activeTab = ref<MailTab>(auth.hasPermission('smtp:read') ? 'smtp' : 'mailbox')
</script>

<template>
  <main
    aria-labelledby="mail-accounts-title"
    class="space-y-6"
  >
    <header>
      <p class="text-xs font-semibold uppercase tracking-wider text-brand-600">
        系统管理
      </p>
      <h1
        id="mail-accounts-title"
        class="mt-1 text-2xl font-semibold tracking-tight text-slate-950"
      >
        邮件账户
      </h1>
      <p class="mt-2 max-w-3xl text-sm/6 text-slate-600">
        统一管理公网与本地 SMTP、IMAP 和 POP3 连接。凭据始终加密；公网连接强制 TLS，收件预览只读取脱敏邮件头。
      </p>
    </header>

    <DsAlert
      v-if="tabs.length === 0"
      tone="warning"
      title="没有邮件账户权限"
    >
      当前账号不具备 SMTP 或收件邮箱读取权限，请联系管理员分配最小所需权限。
    </DsAlert>

    <div v-else>
      <div class="grid grid-cols-1 sm:hidden">
        <select
          v-model="activeTab"
          aria-label="选择邮件协议"
          class="col-start-1 row-start-1 w-full appearance-none rounded-lg bg-white py-2.5 pr-9 pl-3 text-sm font-medium text-slate-900 outline-1 -outline-offset-1 outline-slate-300 focus:outline-2 focus:-outline-offset-2 focus:outline-brand-600"
        >
          <option
            v-for="tab in tabs"
            :key="tab.id"
            :value="tab.id"
          >
            {{ tab.name }}
          </option>
        </select>
        <span
          class="pointer-events-none col-start-1 row-start-1 mr-3 self-center justify-self-end text-slate-400"
          aria-hidden="true"
        >⌄</span>
      </div>
      <div class="hidden border-b border-slate-200 sm:block">
        <nav
          class="-mb-px flex gap-8"
          aria-label="邮件协议"
        >
          <button
            v-for="tab in tabs"
            :key="tab.id"
            type="button"
            :class="[
              activeTab === tab.id
                ? 'border-brand-600 text-brand-700'
                : 'border-transparent text-slate-500 hover:border-slate-300 hover:text-slate-700',
              'flex items-center gap-2 border-b-2 px-1 py-4 text-left text-sm font-medium whitespace-nowrap',
            ]"
            :aria-current="activeTab === tab.id ? 'page' : undefined"
            @click="activeTab = tab.id"
          >
            <component
              :is="tab.icon"
              class="size-4"
            />
            <span>{{ tab.name }}</span>
            <span class="hidden text-xs font-normal text-slate-400 lg:inline">{{ tab.description }}</span>
          </button>
        </nav>
      </div>
    </div>

    <SmtpAccountsView
      v-if="activeTab === 'smtp' && auth.hasPermission('smtp:read')"
      embedded
    />
    <MailboxAccountsPanel v-else-if="activeTab === 'mailbox' && auth.hasPermission('mailbox:read')" />
  </main>
</template>
