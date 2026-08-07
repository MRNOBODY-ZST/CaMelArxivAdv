import type {
  PreviewDevice,
  SmtpAccountRequest,
  TemplateSampleValues,
  TemplateUpsertRequest,
  TemplateVariable,
} from '@/modules/email/email.types'

export function createTemplateDraft(): TemplateUpsertRequest {
  return {
    name: '',
    description: '',
    status: 'DRAFT',
    content: {
      subjectTemplate: '关于您的论文《{{paper_title}}》',
      fromNameTemplate: '{{organization}} 研究合作团队',
      replyTo: '',
      htmlContent: '<p>您好 {{author_name}}，</p><p>我们关注到您的论文《{{paper_title}}》，希望与您进一步交流。</p><p><a href="{{paper_url}}">查看论文</a></p><p><a href="{{unsubscribe_url}}">不再接收此类邮件</a></p>',
      textContent: '',
      autoGenerateText: true,
    },
  }
}

export function createSampleValues(): TemplateSampleValues {
  return {
    author_name: 'Ada Lovelace', first_name: 'Ada', paper_title: 'Analytical Engines for Modern Science',
    arxiv_id: '2608.01234', primary_category: 'cs.AI', paper_url: 'https://arxiv.org/abs/2608.01234',
    organization: 'CaMel Research', unsubscribe_url: 'https://example.org/unsubscribe/preview-token',
  }
}

export function insertPlaceholder(value: string, variable: TemplateVariable, start?: number, end?: number): string {
  const from = start ?? value.length
  const to = end ?? from
  return `${value.slice(0, from)}{{${variable}}}${value.slice(to)}`
}

export function previewWidthClass(device: PreviewDevice): string {
  return device === 'mobile' ? 'mx-auto max-w-sm' : 'mx-auto max-w-2xl'
}

export function passwordForUpdate(value: string, passwordConfigured: boolean): string | null {
  if (!value && passwordConfigured) return null
  return value || null
}

export function maskEmailAddress(value: string): string {
  const at = value.lastIndexOf('@')
  if (at <= 0 || at === value.length - 1) return '***'
  const local = value.slice(0, at)
  const visible = local.slice(0, Math.min(2, local.length))
  return `${visible}***${value.slice(at)}`
}

export function createSmtpDraft(): SmtpAccountRequest {
  return {
    name: '本机 Mailpit', host: 'mailpit', port: 1025, tlsMode: 'PLAIN_LOCAL_ONLY', username: null,
    password: null, fromEmail: 'research@example.org', defaultFromName: 'Research Team',
    replyTo: 'reply@example.org', perMinuteLimit: 10, perHourLimit: 100, perDayLimit: 1000,
    perDomainHourLimit: 50, enabled: true,
  }
}
