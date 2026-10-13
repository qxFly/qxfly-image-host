export interface ApiResult<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

export interface PageResult<T> {
  total: number
  page: number
  size: number
  pages: number
  records: T[]
}

export interface UserVO {
  id: number
  username: string
  nickname?: string
  email?: string
  role: string
  status: number
  createdAt?: string
}

export interface LoginResponse {
  token: string
  tokenType: string
  expiresIn: number
  user: UserVO
}

export interface StorageField {
  key: string
  label: string
  type: string
  required: boolean
  secret: boolean
  placeholder?: string
  defaultValue?: string
  options: string[]
  help?: string
}

export interface StorageTypeVO {
  name: string
  label: string
  shortLabel: string
  description: string
  fields: StorageField[]
}

export interface StorageConfigVO {
  id: number
  name: string
  type: string
  typeLabel: string
  config: Record<string, string>
  isDefault: boolean
  enabled: boolean
  /** 该类型是否支持列举已有文件（决定能否"从存储导入"） */
  supportsList?: boolean
  remark?: string
  createdAt?: string
  updatedAt?: string
}

export interface DirectoryNodeVO {
  path: string
  name: string
  count: number
  totalCount: number
  children: DirectoryNodeVO[]
}

export interface DirectoryTreeVO {
  total: number
  rootFileCount: number
  nodes: DirectoryNodeVO[]
}

export interface ImageVO {
  id: number
  originalName: string
  objectKey: string
  /** 文件所在目录（对象键去掉文件名），根目录下为空串 */
  directory: string
  depth: number
  url: string
  thumbUrl?: string
  mimeType: string
  size: number
  width?: number
  height?: number
  storageType: string
  storageTypeLabel: string
  storageConfigId: number
  storageConfigName?: string
  createdAt: string
  markdown: string
}

export interface NameValue {
  name: string
  label: string
  value: number
}

export interface StatsVO {
  totalCount: number
  totalSize: number
  todayCount: number
  storageConfigCount: number
  typeDistribution: NameValue[]
  recent: ImageVO[]
}

export interface ImportCandidateVO {
  objectKey: string
  fileName: string
  size: number
  url: string
  lastModified?: string
  imported: boolean
}

export interface ImportPreviewVO {
  scanned: number
  imported: number
  importable: number
  truncated: boolean
  page: number
  size: number
  totalPages: number
  candidates: ImportCandidateVO[]
}

export interface ImportResultVO {
  scanned: number
  imported: number
  skipped: number
}

export interface MigrationErrorVO {
  reason: string
  count: number
  samples: string[]
}

export interface MigrationTaskVO {
  taskId: string
  status: string
  sourceName: string
  targetName: string
  concurrency: number
  total: number
  done: number
  succeeded: number
  failed: number
  skipped: number
  percent: number
  currentFile?: string
  message?: string
  startedAt: number
  finishedAt?: number
  errors: MigrationErrorVO[]
}

export interface SystemInfo {
  name: string
  version: string
  storageTypes: string[]
  maxUploadSize: number
  allowedExtensions: string[]
  dedupeEnabled: boolean
}
