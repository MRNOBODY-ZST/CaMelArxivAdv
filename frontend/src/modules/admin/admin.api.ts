import axios from 'axios'

import { apiClient } from '@/api/client'
import type { ApiErrorResponse, Permission } from '@/modules/auth/auth.types'

export interface PageResponse<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
  totalPages: number
}

export type UserStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED'

export interface UserView {
  id: string
  username: string
  email: string
  displayName: string
  status: UserStatus
  forcePasswordChange: boolean
  tokenVersion: number
  lastLoginAt: string | null
  createdAt: string
  roles: string[]
}

export interface CreateUserRequest {
  username: string
  email: string
  displayName: string
  initialPassword: string
  roleCodes: string[]
}

export interface UpdateUserRequest {
  email: string
  displayName: string
  roleCodes: string[]
}

export interface RoleView {
  id: string
  code: string
  name: string
  description: string
  systemRole: boolean
  userCount: number
  permissions: Permission[]
  createdAt: string
}

export interface PermissionView {
  id: string
  code: Permission
  description: string
  createdAt: string
}

export interface RoleRequest {
  code: string
  name: string
  description: string
  permissionCodes: Permission[]
}

export type AuditResult = 'SUCCESS' | 'FAILURE' | 'DENIED'

export interface AuditLogView {
  id: string
  actorUserId: string | null
  actorUsername: string | null
  action: string
  resourceType: string
  resourceId: string | null
  occurredAt: string
  traceId: string
  beforeSummary: Record<string, unknown>
  afterSummary: Record<string, unknown>
  result: AuditResult
  errorType: string | null
}

export interface UserQuery {
  page?: number
  pageSize?: number
  search?: string
  status?: string
}

export interface AuditQuery {
  page?: number
  pageSize?: number
  from?: string
  to?: string
  actorId?: string
  action?: string
  resource?: string
  result?: string
}

export const adminApi = {
  async listUsers(query: UserQuery = {}): Promise<PageResponse<UserView>> {
    const { data } = await apiClient.get<PageResponse<UserView>>('/users', { params: query })
    return data
  },
  async createUser(request: CreateUserRequest): Promise<UserView> {
    const { data } = await apiClient.post<UserView>('/users', request)
    return data
  },
  async updateUser(id: string, request: UpdateUserRequest): Promise<UserView> {
    const { data } = await apiClient.put<UserView>(`/users/${id}`, request)
    return data
  },
  async disableUser(id: string): Promise<void> {
    await apiClient.post(`/users/${id}/disable`)
  },
  async enableUser(id: string): Promise<void> {
    await apiClient.post(`/users/${id}/enable`)
  },
  async resetPassword(id: string, newPassword: string): Promise<void> {
    await apiClient.post(`/users/${id}/reset-password`, { newPassword })
  },
  async listRoles(): Promise<RoleView[]> {
    const { data } = await apiClient.get<RoleView[]>('/roles')
    return data
  },
  async listPermissions(): Promise<PermissionView[]> {
    const { data } = await apiClient.get<PermissionView[]>('/permissions')
    return data
  },
  async createRole(request: RoleRequest): Promise<RoleView> {
    const { data } = await apiClient.post<RoleView>('/roles', request)
    return data
  },
  async updateRole(id: string, request: RoleRequest): Promise<RoleView> {
    const { data } = await apiClient.put<RoleView>(`/roles/${id}`, request)
    return data
  },
  async deleteRole(id: string): Promise<void> {
    await apiClient.delete(`/roles/${id}`)
  },
  async listAuditLogs(query: AuditQuery = {}): Promise<PageResponse<AuditLogView>> {
    const { data } = await apiClient.get<PageResponse<AuditLogView>>('/audit-logs', { params: query })
    return data
  },
}

export function administrationErrorMessage(error: unknown, forbiddenMessage: string): string {
  if (axios.isAxiosError<ApiErrorResponse>(error)) {
    if (error.response?.status === 403) return forbiddenMessage
    return error.response?.data.detail ?? '请求失败，请稍后重试。'
  }
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const response = (error as { response?: { status?: number } }).response
    if (response?.status === 403) return forbiddenMessage
  }
  return '请求失败，请稍后重试。'
}
