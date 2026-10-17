import { del, get, put } from './http'
import type { SettingsVO } from '@/types'

/** 读取系统设置（仅超级管理员） */
export function fetchSettings() {
  return get<SettingsVO>('/settings')
}

/** 缩略图缓存占用（仅超级管理员） */
export const fetchThumbCache = () =>
  get<{ dir: string; files: number; bytes: number; maxBytes: number; days: number }>('/settings/thumb-cache')

/** 清空缩略图缓存（只删磁盘缓存，不影响原图与图库记录） */
export const clearThumbCache = () => del<number>('/settings/thumb-cache')

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
