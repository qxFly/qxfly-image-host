import { del, get } from './http'

export interface DuplicateImage {
  id: number
  originalName?: string
  objectKey: string
  url?: string
  size?: number
  createdAt?: string
  storageConfigId?: number
  keep: boolean
}

export interface DuplicateGroup {
  hash: string
  count: number
  totalSize: number
  images: DuplicateImage[]
}

export interface DuplicateResult {
  total: number
  wastedSize: number
  page: number
  size: number
  pages: number
  groups: DuplicateGroup[]
}

/** 重复图片分组（仅超级管理员） */
export const fetchDuplicates = (page: number, size: number) =>
  get<DuplicateResult>('/images/duplicates', { page, size })

/** 清理：每组保留最早的一张，其余移入回收站；传 hash 只清理这一组 */
export const cleanupDuplicates = (hash?: string) =>
  del<number>('/images/duplicates' + (hash ? '?hash=' + encodeURIComponent(hash) : ''))
