import { apiClient } from '@/api/client'
import type { PageResponse } from '@/modules/jobs/jobs.types'

export interface PaperSummary {
  id: string; arxivId: string; title: string; primaryCategory: string; authors: string[]
  submittedAt: string; updatedAt: string; doi: string | null; journalReference: string | null
  sourceStatus: string; versionCount: number
}

export type PaperDetail = Omit<PaperSummary, 'authors'> & {
  authors: Array<{ order: number; name: string; affiliations: string[] }>
  abstractText: string; comment: string | null; licenseUrl: string | null; pdfUrl: string
  sourceFormat: string | null
  categories: Array<{ categoryId: string; categoryName: string; relationType: string }>
  versions: Array<{ version: number; submittedAt: string; sizeBytes: number | null; sourceFormat: string | null }>
  imports: Array<{ jobId: string; metadataSource: string; sourceDatestamp: string; importedAt: string }>
  rawMetadata: Record<string, unknown>
}

export interface PaperQuery { page: number; pageSize: number; category?: string; title?: string; author?: string; hasDoi?: boolean }

export const papersApi = {
  async list(query: PaperQuery): Promise<PageResponse<PaperSummary>> {
    return (await apiClient.get<PageResponse<PaperSummary>>('/papers', { params: query })).data
  },
  async get(id: string): Promise<PaperDetail> {
    return (await apiClient.get<PaperDetail>(`/papers/${id}`)).data
  },
}
