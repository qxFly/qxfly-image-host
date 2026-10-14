import { get } from './http'
import type { AuditLogVO, PageResult } from '@/types'

export interface AuditQuery {
  page: number
  size: number
  keyword?: string
  action?: string
  success?: boolean
}

/** 审计日志（仅超级管理员） */
export const fetchAuditLogs = (params: AuditQuery) =>
  get<PageResult<AuditLogVO>>('/audit-logs', params as unknown as Record<string, unknown>)
