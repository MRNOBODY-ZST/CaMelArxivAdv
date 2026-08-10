import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { authApi } from '@/modules/auth/auth.api'
import type { AuthSessionResponse, CurrentUser, Permission } from '@/modules/auth/auth.types'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const user = ref<CurrentUser | null>(null)
  const initialized = ref(false)
  const busy = ref(false)
  let bootstrapPromise: Promise<void> | undefined

  const authenticated = computed(() => Boolean(accessToken.value && user.value))

  function acceptSession(session: AuthSessionResponse): void {
    accessToken.value = session.accessToken
    user.value = session.user
    initialized.value = true
  }

  function clearSession(): void {
    accessToken.value = null
    user.value = null
  }

  function hasPermission(permission: Permission): boolean {
    return user.value?.permissions.includes(permission) ?? false
  }

  function hasEveryPermission(required: readonly Permission[]): boolean {
    return required.every(hasPermission)
  }

  async function login(principal: string, password: string): Promise<void> {
    busy.value = true
    try {
      acceptSession(await authApi.login(principal, password))
      initialized.value = true
    } finally {
      busy.value = false
    }
  }

  async function refresh(): Promise<string> {
    try {
      const session = await authApi.refresh()
      acceptSession(session)
      return session.accessToken
    } catch (error) {
      clearSession()
      throw error
    }
  }

  async function bootstrap(): Promise<void> {
    if (initialized.value) return
    if (!bootstrapPromise) {
      bootstrapPromise = (async () => {
        try {
          const token = await refresh()
          user.value = await authApi.me(token)
        } catch {
          clearSession()
        } finally {
          initialized.value = true
          bootstrapPromise = undefined
        }
      })()
    }
    await bootstrapPromise
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      clearSession()
      initialized.value = true
    }
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    if (!accessToken.value) throw new Error('Authentication is required')
    await authApi.changePassword(accessToken.value, currentPassword, newPassword)
    clearSession()
  }

  return {
    accessToken,
    authenticated,
    busy,
    initialized,
    user,
    acceptSession,
    bootstrap,
    changePassword,
    clearSession,
    hasEveryPermission,
    hasPermission,
    login,
    logout,
    refresh,
  }
})
