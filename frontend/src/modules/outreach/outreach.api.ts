import { apiClient } from '@/api/client'
import type { OutreachCreateRequest, OutreachOptions, OutreachRun, OutreachRunPage } from './outreach.types'

export const outreachApi = {
  async options(): Promise<OutreachOptions> {
    return (await apiClient.get<OutreachOptions>('/outreach-runs/options')).data
  },
  async list(page = 1, pageSize = 10): Promise<OutreachRunPage> {
    return (await apiClient.get<OutreachRunPage>('/outreach-runs', { params: { page, pageSize } })).data
  },
  async get(id: string): Promise<OutreachRun> {
    return (await apiClient.get<OutreachRun>(`/outreach-runs/${id}`)).data
  },
  async create(request: OutreachCreateRequest): Promise<OutreachRun> {
    return (await apiClient.post<OutreachRun>('/outreach-runs', request)).data
  },
  async cancel(id: string, expectedLockVersion: number): Promise<OutreachRun> {
    return (await apiClient.post<OutreachRun>(`/outreach-runs/${id}/cancel`, { expectedLockVersion })).data
  },
}
