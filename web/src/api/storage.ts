import { del, get, post, put } from './http'
import type { StorageConfigVO, StorageTypeVO } from '@/types'

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
export const testStorageConfigDraft = (data: StorageConfigPayload, existingId?: number) =>
  post<{ message: string }>(
    '/storage-configs/test' + (existingId ? '?existingId=' + existingId : ''),
    data,
  )
