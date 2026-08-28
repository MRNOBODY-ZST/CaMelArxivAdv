import type { MailSendRecord } from '@/modules/email/mail-tracking.types'

export function formatMailTrackingDate(value: string | null): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

export function mailSendSourceLabel(value: MailSendRecord): string {
  return value.source === 'SMTP_DIAGNOSTIC' ? 'SMTP 诊断' : '模板测试'
}

export function mailSendStatusLabel(value: MailSendRecord): string {
  return {
    SENDING: '发送中', SMTP_ACCEPTED: 'SMTP 已接受', FAILED: '发送失败', UNKNOWN: '发送状态未知',
  }[value.status]
}

export function isMailTrackingExpired(value: MailSendRecord): boolean {
  return Boolean(value.trackingExpiresAt && new Date(value.trackingExpiresAt).getTime() < Date.now())
}

export function mailTrackingState(value: MailSendRecord): string {
  if (!value.trackingEnabled) return '未启用检测'
  if (value.status === 'FAILED') return '发送失败，未确认检测'
  if (value.status === 'UNKNOWN') return '发送状态未知'
  if (isMailTrackingExpired(value)) return '检测期已过期'
  if (value.rawOpenCount === 0) return '尚无回传'
  return `检测到图片加载（${value.rawOpenCount}）`
}
