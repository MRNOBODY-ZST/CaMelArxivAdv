export const PERMISSION_CODES = [
  'user:read', 'user:create', 'user:update', 'user:disable',
  'role:read', 'role:manage',
  'paper:read', 'paper:import', 'paper:delete',
  'contact:read_masked', 'contact:read_full', 'contact:verify', 'contact:export',
  'job:manage', 'template:read', 'template:manage',
  'smtp:read', 'smtp:manage',
  'mailbox:read', 'mailbox:manage',
  'campaign:read', 'campaign:create', 'campaign:approve', 'campaign:send', 'campaign:pause',
  'analytics:read', 'audit:read', 'system:manage',
] as const

export type Permission = (typeof PERMISSION_CODES)[number]

export interface CurrentUser {
  id: string
  username: string
  displayName: string
  roles: string[]
  permissions: Permission[]
  mustChangePassword: boolean
}

export interface AuthSessionResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
  user: CurrentUser
}

export interface ApiErrorResponse {
  type: string
  title: string
  status: number
  detail: string
  instance: string
  traceId: string
  fieldErrors: Record<string, string[]>
}
