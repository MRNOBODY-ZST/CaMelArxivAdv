import axios from 'axios'

import type { AuthSessionResponse, CurrentUser } from '@/modules/auth/auth.types'

const authTransport = axios.create({
  baseURL: '/api/v1/auth',
  timeout: 15_000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
})

export const authApi = {
  async login(principal: string, password: string): Promise<AuthSessionResponse> {
    const { data } = await authTransport.post<AuthSessionResponse>('/login', { principal, password })
    return data
  },

  async refresh(): Promise<AuthSessionResponse> {
    const { data } = await authTransport.post<AuthSessionResponse>('/refresh')
    return data
  },

  async me(accessToken: string): Promise<CurrentUser> {
    const { data } = await authTransport.get<CurrentUser>('/me', {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
    return data
  },

  async logout(): Promise<void> {
    await authTransport.post('/logout')
  },

  async changePassword(
    accessToken: string,
    currentPassword: string,
    newPassword: string,
  ): Promise<void> {
    await authTransport.post('/change-password', { currentPassword, newPassword }, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
  },
}
