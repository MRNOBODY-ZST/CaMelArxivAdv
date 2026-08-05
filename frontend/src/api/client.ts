import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'

import { createRefreshCoordinator } from '@/api/refreshCoordinator'
import { useAuthStore } from '@/stores/auth'

interface RetriableRequest extends InternalAxiosRequestConfig {
  _retry?: boolean
}

interface RefreshResponse {
  accessToken: string
}

export const apiClient = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
})

const refreshCoordinator = createRefreshCoordinator(async () => {
  const { data } = await axios.post<RefreshResponse>('/api/v1/auth/refresh', undefined, {
    withCredentials: true,
  })
  useAuthStore().setAccessToken(data.accessToken)
  return data.accessToken
})

apiClient.interceptors.request.use((config) => {
  const token = useAuthStore().accessToken
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

apiClient.interceptors.response.use(undefined, async (error: AxiosError) => {
  const request = error.config as RetriableRequest | undefined
  if (error.response?.status !== 401 || !request || request._retry || request.url === '/auth/refresh') {
    throw error
  }

  request._retry = true
  const token = await refreshCoordinator.refresh()
  request.headers.Authorization = `Bearer ${token}`
  return apiClient(request)
})
