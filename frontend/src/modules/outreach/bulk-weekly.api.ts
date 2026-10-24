import type { BulkOutreachCommand, BulkOutreachRun, BulkOutreachPage, WeeklyOutreachCommand, WeeklyOutreachSchedule, WeeklyOutreachPage } from './bulk-weekly.types'
import { apiClient } from '@/api/client'
export const bulkOutreachApi = {
  async create(request: BulkOutreachCommand): Promise<BulkOutreachRun> { return (await apiClient.post<BulkOutreachRun>('/outreach-bulk-runs', request)).data },
  async list(page = 1, pageSize = 10): Promise<BulkOutreachPage> { return (await apiClient.get<BulkOutreachPage>('/outreach-bulk-runs', { params: { page, pageSize } })).data },
  async get(id: string): Promise<BulkOutreachRun> { return (await apiClient.get<BulkOutreachRun>(`/outreach-bulk-runs/${encodeURIComponent(id)}`)).data },
  async cancel(id: string, expectedVersion: number): Promise<BulkOutreachRun> { return (await apiClient.post<BulkOutreachRun>(`/outreach-bulk-runs/${encodeURIComponent(id)}/cancel`, { expectedVersion })).data },
}
export const weeklyOutreachApi = {
  async create(request: WeeklyOutreachCommand): Promise<WeeklyOutreachSchedule> { return (await apiClient.post<WeeklyOutreachSchedule>('/outreach-schedules', request)).data },
  async list(page = 1, pageSize = 10): Promise<WeeklyOutreachPage> { return (await apiClient.get<WeeklyOutreachPage>('/outreach-schedules', { params: { page, pageSize } })).data },
  async get(id: string): Promise<WeeklyOutreachSchedule> { return (await apiClient.get<WeeklyOutreachSchedule>(`/outreach-schedules/${encodeURIComponent(id)}`)).data },
  async pause(id: string, expectedVersion: number): Promise<WeeklyOutreachSchedule> { return (await apiClient.post<WeeklyOutreachSchedule>(`/outreach-schedules/${encodeURIComponent(id)}/pause`, { expectedVersion })).data },
  async resume(id: string, expectedVersion: number): Promise<WeeklyOutreachSchedule> { return (await apiClient.post<WeeklyOutreachSchedule>(`/outreach-schedules/${encodeURIComponent(id)}/resume`, { expectedVersion })).data },
}
