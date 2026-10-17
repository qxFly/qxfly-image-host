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

/** 保留策略：最早 / 最新 / 最大（内容一样，差别是留哪条记录） */
export type KeepStrategy = 'earliest' | 'latest' | 'largest'

/** 重复图片分组（仅超级管理员）；可按存储后端 / 目录筛选 */
export const fetchDuplicates = (
  page: number,
  size: number,
  storageConfigId?: number,
  directory?: string,
) =>
  get<DuplicateResult>('/images/duplicates', {
    page,
    size,
    ...(storageConfigId ? { storageConfigId } : {}),
    ...(directory ? { directory } : {}),
  })

/** 清理：每组按保留策略留一张，其余移入回收站；传 hash 只清理这一组 */
export const cleanupDuplicates = (
  keep: KeepStrategy = 'earliest',
  hash?: string,
  storageConfigId?: number,
) => {
  const params = new URLSearchParams()
  params.set('keep', keep)
  if (hash) params.set('hash', hash)
  if (storageConfigId) params.set('storageConfigId', String(storageConfigId))
  return del<number>('/images/duplicates?' + params.toString())
}
