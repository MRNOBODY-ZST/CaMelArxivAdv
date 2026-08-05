<script setup lang="ts">
import axios from 'axios'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsInput from '@/components/design-skill/DsInput.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { ApiErrorResponse } from '@/modules/auth/auth.types'

const auth = useAuthStore()
const router = useRouter()
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const busy = ref(false)
const errorMessage = ref('')
const confirmationError = computed(() => confirmation.value && confirmation.value !== newPassword.value
  ? '两次输入的新密码不一致。' : '')

async function submit(): Promise<void> {
  if (confirmationError.value) return
  busy.value = true
  errorMessage.value = ''
  try {
    await auth.changePassword(currentPassword.value, newPassword.value)
    await router.replace({ name: 'login', query: { changed: '1' } })
  } catch (error) {
    errorMessage.value = axios.isAxiosError<ApiErrorResponse>(error)
      ? error.response?.data.detail ?? '密码修改失败。'
      : '密码修改失败。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl py-8">
    <DsCard>
      <h1 class="text-xl font-semibold text-slate-950">
        首次登录需修改密码
      </h1>
      <p class="mt-2 mb-6 text-sm/6 text-slate-500">
        修改后，当前 access token 和所有 refresh 会话会立即失效。
      </p>
      <DsAlert
        v-if="errorMessage"
        class="mb-5"
        tone="danger"
      >
        {{ errorMessage }}
      </DsAlert>
      <form
        class="space-y-5"
        @submit.prevent="submit"
      >
        <DsInput
          id="current-password"
          v-model="currentPassword"
          type="password"
          label="当前密码"
          autocomplete="current-password"
        />
        <DsInput
          id="new-password"
          v-model="newPassword"
          type="password"
          label="新密码"
          description="至少 12 位，包含大写、小写、数字和符号，且不得包含用户名或邮箱。"
          autocomplete="new-password"
        />
        <DsInput
          id="confirm-password"
          v-model="confirmation"
          type="password"
          label="确认新密码"
          :error="confirmationError"
          autocomplete="new-password"
        />
        <DsButton
          type="submit"
          :busy="busy"
          :disabled="!currentPassword || !newPassword || !confirmation || Boolean(confirmationError)"
        >
          修改密码并重新登录
        </DsButton>
      </form>
    </DsCard>
  </div>
</template>
