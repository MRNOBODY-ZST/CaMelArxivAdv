import { apiClient } from '@/api/client'
import type {
  AnalyticsQuery,
  AuthorsResponse,
  ContactsResponse,
  FilterOptionsResponse,
  IngestionResponse,
  OverviewResponse,
  PapersResponse,
} from '@/modules/analytics/analytics.types'

export const analyticsApi = {
  async authors(query: AnalyticsQuery): Promise<AuthorsResponse> {
    return (await apiClient.get<AuthorsResponse>('/analytics/authors', { params: query })).data
  },
  async overview(query: AnalyticsQuery): Promise<OverviewResponse> {
    return (await apiClient.get<OverviewResponse>('/analytics/overview', { params: query })).data
  },
  async ingestion(query: AnalyticsQuery): Promise<IngestionResponse> {
    return (await apiClient.get<IngestionResponse>('/analytics/ingestion', { params: query })).data
  },
  async papers(query: AnalyticsQuery): Promise<PapersResponse> {
    return (await apiClient.get<PapersResponse>('/analytics/papers', { params: query })).data
  },
  async contacts(query: AnalyticsQuery): Promise<ContactsResponse> {
    return (await apiClient.get<ContactsResponse>('/analytics/contacts', { params: query })).data
  },
  async filters(query: AnalyticsQuery): Promise<FilterOptionsResponse> {
    return (await apiClient.get<FilterOptionsResponse>('/analytics/filters', { params: query })).data
  },
  async export(view: string, query: AnalyticsQuery): Promise<void> {
    const response = await apiClient.get<Blob>(`/analytics/${view}/export`, {
      params: { ...query, dataset: 'all' },
      responseType: 'blob',
      headers: { Accept: 'text/csv' },
    })
    const disposition = response.headers['content-disposition'] as string | undefined
    const encoded = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
    const filename = encoded ? decodeURIComponent(encoded) : `camel-arxiv-${view}.csv`
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.click()
    URL.revokeObjectURL(url)
  },
}
