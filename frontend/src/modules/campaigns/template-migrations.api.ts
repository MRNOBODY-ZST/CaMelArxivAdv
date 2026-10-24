import type { AxiosRequestConfig } from 'axios'
import { apiClient } from '@/api/client'
import type { MigrationCommand, MigrationPreview, MigrationResult, PreviewCommand } from './template-migrations.types'

// The shared client's 401 handler must never refresh/replay this actor-bound operation.
const noReplay: AxiosRequestConfig & { _retry: boolean } = { _retry: true }
const prefix = (source: string) => `/campaigns/${encodeURIComponent(source)}/template-migrations`
export const templateMigrationsApi = {
  async preview(source: string, command: PreviewCommand): Promise<MigrationPreview> {
    return (await apiClient.post<MigrationPreview>(`${prefix(source)}/preview`, command, noReplay)).data
  },
  async migrate(source: string, command: MigrationCommand): Promise<MigrationResult> {
    return (await apiClient.post<MigrationResult>(prefix(source), command, noReplay)).data
  },
  async get(source: string, migrationId: string): Promise<MigrationResult> {
    return (await apiClient.get<MigrationResult>(`${prefix(source)}/${encodeURIComponent(migrationId)}`, noReplay)).data
  },
  async getByKey(source: string, key: string): Promise<MigrationResult> {
    return (await apiClient.get<MigrationResult>(`${prefix(source)}/by-key`, { ...noReplay, params: { key } })).data
  },
}
