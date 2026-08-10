import { apiClient } from '@/api/client'
import type { PageResponse } from '@/modules/jobs/jobs.types'

export interface ContactSummary {
  id: string; email: string; domain: string; exampleAddress: boolean; suppressionStatus: string
  mappingId: string; version: number; confidence: string; corresponding: boolean
  verificationStatus: string; humanVerified: boolean; paperId: string; arxivId: string
  paperTitle: string; authorName: string | null; categoryId: string; ruleName: string
  lastExtractedAt: string
}

export interface ContactEvidence {
  sourceRelativePath: string; ruleName: string; lineNumber: number | null
  logicalLocation: string | null; maskedContext: string
}

export type ContactDetail = ContactSummary & { evidence: ContactEvidence[] }

export interface ContactQuery {
  page: number; pageSize: number; domain?: string; confidence?: string
  verificationStatus?: string; corresponding?: boolean; paperId?: string
}

export interface VerificationCommand {
  mappingId: string; expectedVersion: number; status: 'CONFIRMED' | 'REJECTED'
}

export interface BatchVerificationItem {
  contactId: string; mappingId: string; expectedVersion: number
}

export interface BatchVerificationResponse {
  updatedCount: number; status: 'CONFIRMED' | 'REJECTED'
}

export const contactsApi = {
  async list(query: ContactQuery): Promise<PageResponse<ContactSummary>> {
    return (await apiClient.get<PageResponse<ContactSummary>>('/contacts', { params: query })).data
  },
  async get(id: string, full = false): Promise<ContactDetail> {
    return (await apiClient.get<ContactDetail>(`/contacts/${id}`, { params: { full } })).data
  },
  async verify(id: string, command: VerificationCommand): Promise<ContactDetail> {
    return (await apiClient.patch<ContactDetail>(`/contacts/${id}/verification`, command)).data
  },
  async batchVerify(
    items: BatchVerificationItem[],
    status: 'CONFIRMED' | 'REJECTED',
  ): Promise<BatchVerificationResponse> {
    return (await apiClient.patch<BatchVerificationResponse>('/contacts/batch-verification', {
      items, status,
    })).data
  },
}
