import type { PageResponse } from '@/modules/jobs/jobs.types'

export type TemplateStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type SmtpTlsMode = 'STARTTLS_REQUIRED' | 'TLS_IMPLICIT' | 'PLAIN_LOCAL_ONLY'
export type PreviewDevice = 'desktop' | 'mobile'

export const TEMPLATE_VARIABLES = [
  'author_name', 'first_name', 'paper_title', 'arxiv_id', 'primary_category',
  'paper_url', 'organization', 'unsubscribe_url',
] as const
export type TemplateVariable = (typeof TEMPLATE_VARIABLES)[number]

export interface TemplateContentRequest {
  subjectTemplate: string
  fromNameTemplate: string
  replyTo: string
  htmlContent: string
  textContent: string | null
  autoGenerateText: boolean
}

export interface TemplateUpsertRequest {
  name: string
  description: string
  status: TemplateStatus
  content: TemplateContentRequest
}

export interface TemplateValidation {
  valid: boolean
  errors: string[]
  warnings: string[]
  variables: string[]
}

export interface TemplateView {
  id: string
  name: string
  description: string | null
  status: TemplateStatus
  currentVersion: number
  lockVersion: number
  subjectTemplate: string
  fromNameTemplate: string
  replyTo: string
  htmlContent: string
  textContent: string
  autoGenerateText: boolean
  contentSizeBytes: number
  validation: TemplateValidation
  createdAt: string
  updatedAt: string
  versionCreatedAt: string
}

export interface TemplateVersionView {
  id: string
  versionNumber: number
  subjectTemplate: string
  fromNameTemplate: string
  replyTo: string
  htmlContent: string
  textContent: string
  autoGenerateText: boolean
  contentSizeBytes: number
  validation: TemplateValidation
  createdBy: string
  createdAt: string
}

export interface RenderedTemplate {
  subject: string
  fromName: string
  replyTo: string
  html: string
  text: string
}

export interface TemplatePreview {
  rendered: RenderedTemplate
  validation: TemplateValidation
  contentSizeBytes: number
}

export interface TemplateAsset {
  id: string
  templateId: string
  originalFilename: string
  contentType: string
  sizeBytes: number
  objectUrl: string
  createdAt: string
}

export interface SmtpAccountRequest {
  name: string
  host: string
  port: number
  tlsMode: SmtpTlsMode
  username: string | null
  password: string | null
  fromEmail: string
  defaultFromName: string
  replyTo: string
  perMinuteLimit: number
  perHourLimit: number
  perDayLimit: number
  perDomainHourLimit: number
  enabled: boolean
}

export interface SmtpAccountView extends Omit<SmtpAccountRequest, 'password'> {
  id: string
  passwordConfigured: boolean
  lastTestedAt: string | null
  lastTestStatus: string | null
  lastTestError: string | null
  lockVersion: number
  createdAt: string
  updatedAt: string
}

export interface SmtpTestResult {
  status: 'CONNECTION_SUCCEEDED' | 'SMTP_ACCEPTED'
  errorCategory: string | null
  correlationId: string
}

export type TemplatePage = PageResponse<TemplateView>
export type SmtpPage = PageResponse<SmtpAccountView>

export type TemplateSampleValues = Record<TemplateVariable, string>
