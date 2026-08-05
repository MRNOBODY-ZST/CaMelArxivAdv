import { apiClient } from '@/api/client'
import type { JobAction, JobEvent, JobView, PageResponse } from './jobs.types'

export const jobsApi = {
  async list(page = 1, pageSize = 20): Promise<PageResponse<JobView>> {
    return (await apiClient.get<PageResponse<JobView>>('/jobs', { params: { page, pageSize } })).data
  },
  async get(id: string): Promise<JobView> {
    return (await apiClient.get<JobView>(`/jobs/${id}`)).data
  },
  async events(id: string, afterId = 0): Promise<JobEvent[]> {
    return (await apiClient.get<JobEvent[]>(`/jobs/${id}/events`, { params: { afterId, limit: 200 } })).data
  },
  async control(id: string, action: JobAction): Promise<JobView> {
    return (await apiClient.post<JobView>(`/jobs/${id}/${action.toLowerCase()}`)).data
  },
}
