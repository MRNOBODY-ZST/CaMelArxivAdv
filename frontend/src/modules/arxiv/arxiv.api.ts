import { apiClient } from '@/api/client'
import type { JobSubmission, PreviewResult, SearchCriteria, TaxonomyResponse } from './arxiv.types'

export const arxivApi = {
  async taxonomy(): Promise<TaxonomyResponse> {
    return (await apiClient.get<TaxonomyResponse>('/arxiv/taxonomy')).data
  },
  async preview(criteria: SearchCriteria): Promise<PreviewResult> {
    return (await apiClient.post<PreviewResult>('/arxiv/search/preview', criteria)).data
  },
  async saveSearch(name: string, criteria: SearchCriteria): Promise<void> {
    await apiClient.post('/arxiv/saved-searches', { name, criteria })
  },
  async importSelected(arxivIds: string[]): Promise<JobSubmission> {
    return (await apiClient.post<JobSubmission>('/arxiv/imports', { arxivIds })).data
  },
  async importCriteria(criteria: SearchCriteria, maxPapers: number): Promise<JobSubmission> {
    return (await apiClient.post<JobSubmission>('/arxiv/imports', { criteria, maxPapers })).data
  },
}
