<script setup lang="ts">
import { ArrowRightIcon, CheckCircleIcon, ShieldCheckIcon } from '@heroicons/vue/24/outline'
import axios from 'axios'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { safePostLoginRedirect } from '@/modules/auth/loginRedirect'
import type { ApiErrorResponse } from '@/modules/auth/auth.types'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const principal = ref('')
const password = ref('')
const errorMessage = ref('')

async function submit(): Promise<void> {
  errorMessage.value = ''
  try {
    await auth.login(principal.value, password.value)
    if (auth.user?.mustChangePassword) {
      await router.replace({ name: 'change-password' })
      return
    }
    await router.replace(safePostLoginRedirect(route.query.redirect))
  } catch (error) {
    if (axios.isAxiosError<ApiErrorResponse>(error)) {
      errorMessage.value = error.response?.data.detail ?? '登录失败，请稍后重试。'
    } else {
      errorMessage.value = '登录失败，请稍后重试。'
    }
  }
}
</script>

<template>
  <main class="grid min-h-screen bg-slate-950 lg:grid-cols-[minmax(0,1.05fr)_minmax(28rem,0.95fr)]">
    <section class="relative hidden overflow-hidden border-r border-white/10 px-12 py-10 text-white lg:flex lg:flex-col">
      <div class="absolute inset-0 bg-[radial-gradient(circle_at_25%_20%,rgba(79,110,247,0.32),transparent_34%),radial-gradient(circle_at_75%_70%,rgba(14,165,233,0.16),transparent_38%)]" />
      <div class="relative flex items-center gap-3">
        <span class="grid size-10 place-items-center rounded-xl bg-brand-500 font-bold">C</span>
        <div>
          <p class="font-semibold tracking-tight">
            CaMel Arxiv
          </p><p class="text-xs text-slate-400">
            Research outreach operations
          </p>
        </div>
      </div>
      <div class="relative my-auto max-w-xl">
        <p class="text-sm font-semibold text-indigo-300">
          受控科研联络工作台
        </p>
        <h1 class="mt-4 text-4xl font-semibold tracking-tight text-balance">
          从 arXiv 发现到合规邮件活动，一条可审计的数据链路。
        </h1>
        <p class="mt-5 max-w-lg text-base/7 text-slate-300">
          管理论文证据、个性化草稿、邮件账户与分析；正式活动发送流程仍在开发中。
        </p>
        <ul class="mt-10 grid gap-4 text-sm text-slate-200">
          <li class="flex items-center gap-3">
            <CheckCircleIcon class="size-5 text-indigo-300" />短时 access token 与单次 refresh 轮换
          </li>
          <li class="flex items-center gap-3">
            <CheckCircleIcon class="size-5 text-indigo-300" />细粒度 RBAC 与实时账号状态校验
          </li>
          <li class="flex items-center gap-3">
            <CheckCircleIcon class="size-5 text-indigo-300" />敏感字段脱敏和完整操作审计
          </li>
        </ul>
      </div>
      <p class="relative text-xs text-slate-500">
        公网邮件强制 TLS · 个性化草稿不会自动发送
      </p>
    </section>

    <section class="flex min-h-screen items-center justify-center bg-slate-50 px-5 py-12 sm:px-8">
      <div class="w-full max-w-md">
        <div class="mb-8 flex items-center gap-3 lg:hidden">
          <span class="grid size-10 place-items-center rounded-xl bg-brand-500 font-bold text-white">C</span>
          <span class="font-semibold text-slate-900">CaMel Arxiv</span>
        </div>
        <div class="rounded-xl bg-white p-6 shadow-sm ring-1 ring-slate-200 sm:p-8">
          <div class="flex size-11 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
            <ShieldCheckIcon class="size-6" />
          </div>
          <h2 class="mt-5 text-2xl font-semibold tracking-tight text-slate-950">
            登录管理平台
          </h2>
          <p class="mt-2 text-sm/6 text-slate-500">
            使用用户名或邮箱继续。会话令牌不会写入浏览器存储。
          </p>
          <DsAlert
            v-if="errorMessage"
            class="mt-5"
            tone="danger"
            title="无法登录"
          >
            {{ errorMessage }}
          </DsAlert>
          <form
            class="mt-7 space-y-5"
            @submit.prevent="submit"
          >
            <DsInput
              id="login-principal"
              v-model="principal"
              label="用户名或邮箱"
              autocomplete="username"
              :disabled="auth.busy"
            />
            <DsInput
              id="login-password"
              v-model="password"
              label="密码"
              type="password"
              autocomplete="current-password"
              :disabled="auth.busy"
            />
            <DsButton
              class="w-full"
              type="submit"
              :busy="auth.busy"
              :disabled="!principal || !password"
            >
              登录<ArrowRightIcon class="size-4" />
            </DsButton>
          </form>
          <p class="mt-6 text-xs/5 text-slate-400">
            连续失败会触发账号与 IP 维度限流。如需重置密码，请联系平台管理员。
          </p>
        </div>
      </div>
    </section>
  </main>
</template>
