import { del, get, getBlob, post, postBlob } from './http'
import type {
  DirectoryTreeVO,
  ImageLinkVO,
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
  /** 搜索模式：fuzzy（默认）| exact | prefix */
  matchMode?: string
  /** 体积区间（字节） */
  minSize?: number
  maxSize?: number
  /** 上传日期区间（yyyy-MM-dd，左闭右开含当天） */
  dateFrom?: string
  dateTo?: string
  /** 尺寸区间（像素） */
  minWidth?: number
  maxWidth?: number
  minHeight?: number
  maxHeight?: number
}

/** 目录树 */
/** 导出图片元数据清单（CSV 带 BOM 可直接用 Excel 打开 / JSON） */
export function exportManifest(format: 'csv' | 'json' = 'csv') {
  return getBlob('/images/export', { format })
}

/** 按清单回灌元数据：只补库里没有的记录（按对象键去重、幂等），不重新下载或上传文件 */
export function importManifest(items: Record<string, unknown>[]) {
  return post<{ inserted: number; skipped: number; total: number }>('/images/import-manifest', { items })
}

export function fetchDirectories(storageConfigId?: number) {
  const params = { storageConfigId } as unknown as Record<string, unknown>
  return get<DirectoryTreeVO>('/images/directories', params)
}

export const fetchImages = (params: ImageQuery) =>
  get<PageResult<ImageVO>>('/images', params as unknown as Record<string, unknown>)
export const fetchImage = (id: number) => get<ImageVO>('/images/' + id)
export const fetchStats = () => get<StatsVO>('/images/stats')
export const deleteImage = (id: number) => del<void>('/images/' + id)

/** 批量删除：把选中的图片移入回收站（可在回收站恢复或彻底删除），返回移入回收站的张数 */
export const deleteImages = (imageIds: number[]) => post<number>('/images/delete/batch', { imageIds })

/** 这张图可用的全部链接（原始地址 + 各 CDN + 代理下载），供「选择复制哪种链接」用 */
export const fetchImageLinks = (id: number) => get<ImageLinkVO[]>('/images/' + id + '/links')

/** 回收站列表（仅超级管理员） */
export function fetchTrash(params: { page: number; size: number; keyword?: string }) {
  return get<PageResult<ImageVO>>('/images/trash', params as unknown as Record<string, unknown>)
}

/** 从回收站恢复 */
export const restoreImages = (imageIds: number[]) => post<number>('/images/trash/restore', { imageIds })

/** 彻底删除：同时删掉云端对象与本地记录，不可恢复 */
export const purgeImages = (imageIds: number[]) => post<number>('/images/trash/delete', { imageIds })

/** 清空回收站：把回收站里的图片全部彻底删除 */
export const clearTrash = () => post<number>('/images/trash/clear')

/** 下载单张原图（由后端代理取回，私有仓库 / 私有桶也能下） */
export const downloadImage = (id: number) => getBlob('/images/' + id + '/download')

/** 批量下载：把选中的图片打包成一个 zip，一次最多 500 张 */
export const downloadImagesZip = (imageIds: number[]) =>
  postBlob('/images/download/batch', { imageIds })

export function uploadImage(file: File, storageConfigId?: number) {
  const form = new FormData()
  form.append('file', file)
  if (storageConfigId) {
    form.append('storageConfigId', String(storageConfigId))
  }
  return post<ImageVO>('/images/upload', form, { headers: { 'Content-Type': 'multipart/form-data' } })
}

/**
 * 带进度的单张上传。
 * 上传会话（stores/upload.ts）用它逐张上传，才能拿到每张的进度 / 速度 / ETA。
 */
export function uploadImageWithProgress(
  file: File,
  storageConfigId: number | undefined,
  onProgress: (loaded: number, total: number) => void,
  signal?: AbortSignal,
) {
  const form = new FormData()
  form.append('file', file)
  if (storageConfigId) {
    form.append('storageConfigId', String(storageConfigId))
  }
  return post<ImageVO>('/images/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    signal,
    onUploadProgress: (event: { loaded: number; total?: number }) => {
      onProgress(event.loaded, event.total && event.total > 0 ? event.total : file.size)
    },
  })
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

/** 已持久化的迁移失败项（重启不丢） */
export function fetchMigrationFailures() {
  return get<
    { imageId: number; originalName: string; toConfigId: number; reason?: string; createdAt: string }[]
  >('/images/migrate/failures')
}

/** 一键重试失败项：重新提交成一个迁移任务；返回新任务 */
export function retryMigrationFailures(toConfigId?: number) {
  return post<MigrationTaskVO>(
    '/images/migrate/retry' + (toConfigId ? '?toConfigId=' + toConfigId : ''),
  )
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
