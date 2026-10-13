import { del, get, post } from './http'
import type {
  DirectoryTreeVO,
  ImageVO,
  ImportCandidateVO,
  ImportPreviewVO,
  ImportResultVO,
  MigrationTaskVO,
  PageResult,
  StatsVO,
} from '@/types'

export interface ImageQuery {
  page: number
  size: number
  keyword?: string
  storageConfigId?: number
  /** 目录前缀；__root__ 表示只看根目录下的文件 */
  directory?: string
  /** 搜索模式：fuzzy（默认）| exact */
  matchMode?: string
}

/** 目录树 */
export function fetchDirectories(storageConfigId?: number) {
  const params = { storageConfigId } as unknown as Record<string, unknown>
  return get<DirectoryTreeVO>('/images/directories', params)
}

export const fetchImages = (params: ImageQuery) =>
  get<PageResult<ImageVO>>('/images', params as unknown as Record<string, unknown>)
export const fetchImage = (id: number) => get<ImageVO>('/images/' + id)
export const fetchStats = () => get<StatsVO>('/images/stats')
export const deleteImage = (id: number) => del<void>('/images/' + id)

export function uploadImage(file: File, storageConfigId?: number) {
  const form = new FormData()
  form.append('file', file)
  if (storageConfigId) {
    form.append('storageConfigId', String(storageConfigId))
  }
  return post<ImageVO>('/images/upload', form, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export function uploadImages(files: File[], storageConfigId?: number) {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  if (storageConfigId) {
    form.append('storageConfigId', String(storageConfigId))
  }
  return post<ImageVO[]>('/images/upload/batch', form, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export function uploadBase64(payload: { content: string; fileName?: string; storageConfigId?: number }) {
  return post<ImageVO>('/images/upload/base64', payload)
}

/** 扫描存储后端里已有的文件（返回总览 + 一页候选） */
export function previewImport(storageConfigId: number, prefix?: string, page?: number, size?: number) {
  const params = { storageConfigId, prefix, page, size } as unknown as Record<string, unknown>
  return get<ImportPreviewVO>('/images/import/preview', params)
}

export interface MigratePayload {
  targetStorageConfigId: number
  targetPrefix?: string
  /** 保留源文件的目录结构 */
  preserveDirectory?: boolean
  /** 保留源文件名（false = 用 uuid 重新命名） */
  preserveFileName?: boolean
  deleteSource?: boolean
  imageIds?: number[]
  sourceStorageConfigId?: number
  keyword?: string
  migrateAll?: boolean
}

/** 启动批量迁移（后台任务，返回 taskId） */
export function startMigrate(payload: MigratePayload) {
  return post<MigrationTaskVO>('/images/migrate', payload)
}

export interface RepoMovePayload {
  storageConfigId: number
  sourceDirectory: string
  targetDirectory: string
  includeSubdirectories: boolean
  /** 保留原文件名（默认 true，只换目录不改名） */
  keepFileName: boolean
  /** 再套一层 日期/分片 目录 */
  layered: boolean
}

export interface RepoMoveCheckVO {
  name: string
  ok: boolean
  detail: string
}

export interface RepoMoveResultVO {
  dryRun: boolean
  resolvedSourceDirectory?: string
  resolvedTargetDirectory?: string
  total: number
  moved: number
  commits: number
  commitSha?: string
  commitUrl?: string
  samples: string[]
  shards: string[]
  collisions: number
  collisionSamples: string[]
  message?: string
}

/** 【临时功能】仓库目录整理：自检（只读） */
export function repoMoveDiagnose(params: RepoMovePayload) {
  return get<RepoMoveCheckVO[]>('/images/repo-move/diagnose', params as unknown as Record<string, unknown>)
}

/** 【临时功能】仓库目录整理：预览 */
export function repoMovePreview(payload: RepoMovePayload) {
  return post<RepoMoveResultVO>('/images/repo-move/preview', payload)
}

/** 【临时功能】仓库目录整理：执行 */
export function repoMoveExecute(payload: RepoMovePayload) {
  return post<RepoMoveResultVO>('/images/repo-move/execute', payload)
}

/** 后台迁移任务列表 */
export function fetchMigrateTasks() {
  return get<MigrationTaskVO[]>('/images/migrate')
}

/** 查询单个迁移任务的进度 */
export function fetchMigrateStatus(taskId: string) {
  return get<MigrationTaskVO>('/images/migrate/' + taskId)
}

/** 取消迁移 */
export function cancelMigrate(taskId: string) {
  return post<MigrationTaskVO>('/images/migrate/' + taskId + '/cancel')
}

/** 把选中的远端文件导入图库 */
export function importFromStorage(payload: {
  storageConfigId: number
  prefix?: string
  objectKeys?: string[]
  limit?: number
}) {
  return post<ImportResultVO>('/images/import', payload)
}
