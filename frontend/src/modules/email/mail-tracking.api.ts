import axios from 'axios'

import { apiClient } from '@/api/client'
import type { MailSendRecordDetail, MailSendRecordPage, MailTrackingStatus } from '@/modules/email/mail-tracking.types'

export const mailTrackingApi = {
  async getStatus(): Promise<MailTrackingStatus> {
    return (await apiClient.get<MailTrackingStatus>('/mail-tracking/status')).data
  },
  async listSendRecords(page = 1, pageSize = 20): Promise<MailSendRecordPage> {
    return (await apiClient.get<MailSendRecordPage>('/mail-send-records', { params: { page, pageSize } })).data
  },
  async getSendRecord(id: string): Promise<MailSendRecordDetail> {
    return (await apiClient.get<MailSendRecordDetail>(`/mail-send-records/${id}`)).data
  },
}

export function mailTrackingErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError(error)) return fallback
  const detail = (error.response?.data as { detail?: unknown } | undefined)?.detail
  return typeof detail === 'string' && detail.length <= 300 ? detail : fallback
}
