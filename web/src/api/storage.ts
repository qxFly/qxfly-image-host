import { del, get, post, put } from './http'
import type { CdnVO, StorageConfigVO, StorageTypeVO } from '@/types'

export interface StorageConfigPayload {
  name: string
  type: string
  config: Record<string, string>
  isDefault?: boolean
  enabled?: boolean
  remark?: string
}

export const fetchStorageTypes = () => get<StorageTypeVO[]>('/storage-configs/types')
export const fetchStorageConfigs = () => get<StorageConfigVO[]>('/storage-configs')
export const createStorageConfig = (data: StorageConfigPayload) => post<StorageConfigVO>('/storage-configs', data)
export const updateStorageConfig = (id: number, data: StorageConfigPayload) =>
  put<StorageConfigVO>('/storage-configs/' + id, data)
export const deleteStorageConfig = (id: number) => del<void>('/storage-configs/' + id)
export const setDefaultStorageConfig = (id: number) => put<StorageConfigVO>('/storage-configs/' + id + '/default')
export const testStorageConfig = (id: number) => post<{ message: string }>('/storage-configs/' + id + '/test')

/** 立即体检：把启用的配置全部真测一遍（后端也会每 6 小时自动跑） */
export const checkStorageConfigs = () =>
  post<Record<string, { configId: number; name: string; ok: boolean; message: string; checkedAt: string }>>(
    '/storage-configs/check',
    {},
  )
export const testStorageConfigDraft = (data: StorageConfigPayload, existingId?: number) =>
  post<{ message: string }>(
    '/storage-configs/test' + (existingId ? '?existingId=' + existingId : ''),
    data,
  )

// ==================== CDN 线路 ====================

/** 该存储配置的所有 CDN 线路（原始地址 + 内置预设 + 自定义） */
export const fetchCdns = (configId: number) => get<CdnVO[]>('/storage-configs/' + configId + '/cdns')

export const createCdn = (configId: number, data: { name: string; template: string; enabled?: boolean }) =>
  post<CdnVO[]>('/storage-configs/' + configId + '/cdns', data)

export const updateCdn = (
  configId: number,
  key: string,
  data: { name: string; template: string; enabled?: boolean },
) => put<CdnVO[]>('/storage-configs/' + configId + '/cdns/' + key, data)

export const deleteCdn = (configId: number, key: string) =>
  del<CdnVO[]>('/storage-configs/' + configId + '/cdns/' + key)

/** 设置预览 / 下载 / 复制各自的默认线路；值为 key，origin 或空表示原始地址 */
export const setCdnDefaults = (
  configId: number,
  data: { preview?: string | null; download?: string | null; copy?: string | null },
) => put<CdnVO[]>('/storage-configs/' + configId + '/cdns/defaults', data)
