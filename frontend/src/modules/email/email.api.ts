import axios from 'axios'

import { apiClient } from '@/api/client'
import type {
  MailboxAccountRequest,
  MailboxAccountView,
  MailboxMessageHeader,
  MailboxPage,
  SmtpAccountRequest,
  SmtpAccountView,
  SmtpPage,
  SmtpTestResult,
  TemplateAsset,
  TemplatePage,
  TemplatePreview,
  TemplateSampleValues,
  TemplateUpsertRequest,
  TemplateVersionView,
  TemplateView,
} from '@/modules/email/email.types'

export const emailApi = {
  async listTemplates(page = 1, pageSize = 20): Promise<TemplatePage> {
    return (await apiClient.get<TemplatePage>('/templates', { params: { page, pageSize } })).data
  },
  async getTemplate(id: string): Promise<TemplateView> {
    return (await apiClient.get<TemplateView>(`/templates/${id}`)).data
  },
  async createTemplate(template: TemplateUpsertRequest): Promise<TemplateView> {
    return (await apiClient.post<TemplateView>('/templates', template)).data
  },
  async updateTemplate(id: string, expectedLockVersion: number, template: TemplateUpsertRequest): Promise<TemplateView> {
    return (await apiClient.put<TemplateView>(`/templates/${id}`, { expectedLockVersion, template })).data
  },
  async previewTemplate(template: TemplateUpsertRequest, variables: TemplateSampleValues): Promise<TemplatePreview> {
    return (await apiClient.post<TemplatePreview>('/templates/preview', { template, variables })).data
  },
  async listTemplateVersions(id: string): Promise<TemplateVersionView[]> {
    return (await apiClient.get<TemplateVersionView[]>(`/templates/${id}/versions`)).data
  },
  async restoreTemplate(id: string, version: number, expectedLockVersion: number): Promise<TemplateView> {
    return (await apiClient.post<TemplateView>(`/templates/${id}/versions/${version}/restore`, { expectedLockVersion })).data
  },
  async copyTemplate(id: string, name: string): Promise<TemplateView> {
    return (await apiClient.post<TemplateView>(`/templates/${id}/copy`, { name })).data
  },
  async archiveTemplate(id: string, expectedLockVersion: number): Promise<void> {
    await apiClient.delete(`/templates/${id}`, { params: { expectedLockVersion } })
  },
  async testSendTemplate(
    id: string, smtpAccountId: string, recipient: string, variables: TemplateSampleValues, trackOpens = false,
  ): Promise<SmtpTestResult> {
    return (await apiClient.post<SmtpTestResult>(`/templates/${id}/test-send`, {
      smtpAccountId, recipient, variables, trackOpens,
    })).data
  },
  async listAssets(templateId: string): Promise<TemplateAsset[]> {
    return (await apiClient.get<TemplateAsset[]>(`/templates/${templateId}/assets`)).data
  },
  async uploadAsset(templateId: string, file: File): Promise<TemplateAsset> {
    const body = new FormData()
    body.append('file', file)
    return (await apiClient.post<TemplateAsset>(`/templates/${templateId}/assets`, body)).data
  },
  async deleteAsset(templateId: string, assetId: string): Promise<void> {
    await apiClient.delete(`/templates/${templateId}/assets/${assetId}`)
  },
  async listSmtpAccounts(page = 1, pageSize = 100): Promise<SmtpPage> {
    return (await apiClient.get<SmtpPage>('/smtp-accounts', { params: { page, pageSize } })).data
  },
  async createSmtpAccount(account: SmtpAccountRequest): Promise<SmtpAccountView> {
    return (await apiClient.post<SmtpAccountView>('/smtp-accounts', account)).data
  },
  async updateSmtpAccount(id: string, expectedLockVersion: number, account: SmtpAccountRequest): Promise<SmtpAccountView> {
    return (await apiClient.put<SmtpAccountView>(`/smtp-accounts/${id}`, { expectedLockVersion, account })).data
  },
  async testSmtpConnection(id: string): Promise<SmtpTestResult> {
    return (await apiClient.post<SmtpTestResult>(`/smtp-accounts/${id}/test-connection`)).data
  },
  async sendSmtpDiagnostic(id: string, recipient: string, trackOpens = false): Promise<SmtpTestResult> {
    return (await apiClient.post<SmtpTestResult>(`/smtp-accounts/${id}/test-email`, {
      recipient, subject: 'CaMel arXiv SMTP 内部测试', body: '本消息只用于验证本机 SMTP 接收链路。', trackOpens,
    })).data
  },
  async deleteSmtpAccount(id: string, expectedLockVersion: number): Promise<void> {
    await apiClient.delete(`/smtp-accounts/${id}`, { params: { expectedLockVersion } })
  },
  async listMailboxAccounts(page = 1, pageSize = 100): Promise<MailboxPage> {
    return (await apiClient.get<MailboxPage>('/mailbox-accounts', { params: { page, pageSize } })).data
  },
  async createMailboxAccount(account: MailboxAccountRequest): Promise<MailboxAccountView> {
    return (await apiClient.post<MailboxAccountView>('/mailbox-accounts', account)).data
  },
  async updateMailboxAccount(
    id: string, expectedLockVersion: number, account: MailboxAccountRequest,
  ): Promise<MailboxAccountView> {
    return (await apiClient.put<MailboxAccountView>(`/mailbox-accounts/${id}`, {
      expectedLockVersion, account,
    })).data
  },
  async testMailboxConnection(id: string): Promise<SmtpTestResult> {
    return (await apiClient.post<SmtpTestResult>(`/mailbox-accounts/${id}/test-connection`)).data
  },
  async previewMailboxMessages(id: string, limit = 20): Promise<MailboxMessageHeader[]> {
    return (await apiClient.get<MailboxMessageHeader[]>(`/mailbox-accounts/${id}/messages`, {
      params: { limit },
    })).data
  },
  async deleteMailboxAccount(id: string, expectedLockVersion: number): Promise<void> {
    await apiClient.delete(`/mailbox-accounts/${id}`, { params: { expectedLockVersion } })
  },
}

export function emailErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError(error)) return fallback
  const detail = (error.response?.data as { detail?: unknown } | undefined)?.detail
  return typeof detail === 'string' && detail.length <= 300 ? detail : fallback
}
