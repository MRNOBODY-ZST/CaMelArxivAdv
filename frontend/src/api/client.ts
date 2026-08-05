import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'

import { createRefreshCoordinator } from '@/api/refreshCoordinator'
import { useAuthStore } from '@/modules/auth/auth.store'

interface RetriableRequest extends InternalAxiosRequestConfig {
  _retry?: boolean
}

export const apiClient = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
})

const refreshCoordinator = createRefreshCoordinator(async () => {
  return useAuthStore().refresh()
})

apiClient.interceptors.request.use((config) => {
  const token = useAuthStore().accessToken
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

apiClient.interceptors.response.use(undefined, async (error: AxiosError) => {
  const request = error.config as RetriableRequest | undefined
  const authPath = request?.url ?? ''
  const shouldNotRefresh = ['/auth/login', '/auth/refresh', '/auth/logout'].includes(authPath)
  if (error.response?.status !== 401 || !request || request._retry || shouldNotRefresh) {
    throw error
  }

  request._retry = true
  let token: string
  try {
    token = await refreshCoordinator.refresh()
  } catch (refreshError) {
    useAuthStore().clearSession()
    throw refreshError
  }
  request.headers.Authorization = `Bearer ${token}`
  return apiClient(request)
})
