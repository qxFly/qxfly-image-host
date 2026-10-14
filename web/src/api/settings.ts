import { get, put } from './http'
import type { SettingsVO } from '@/types'

/** 读取系统设置（仅超级管理员） */
export function fetchSettings() {
  return get<SettingsVO>('/settings')
}

/** 更新系统设置：limit 传 { maxUploadSize } 或 { unlimitedUploadSize: true } */
export function updateSettings(data: {
  maxUploadSize?: number
  unlimitedUploadSize?: boolean
  loginMaxAttempts?: number
  loginLockMinutes?: number
  hotlinkEnabled?: boolean
  hotlinkDomains?: string
  hotlinkAllowEmpty?: boolean
}) {
  return put<SettingsVO>('/settings', data)
}
