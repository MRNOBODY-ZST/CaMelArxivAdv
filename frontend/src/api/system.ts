import { apiClient } from '@/api/client'

export interface SystemHealth {
  status: string
  checkedAt?: string
  components?: Readonly<Record<string, string>>
}

export async function getSystemHealth(): Promise<SystemHealth> {
  const { data } = await apiClient.get<SystemHealth>('/system/health')
  return data
}
