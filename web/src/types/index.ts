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
  /** SUPER_ADMIN（超级管理员，只能由数据库赋予）| USER（普通用户，仅能浏览查看下载） */
  role: string
  /** 角色的中文名 */
  roleLabel?: string
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
  /** 最近一次定时体检结果（未体检过为空） */
  lastCheckOk?: boolean
  lastCheckMessage?: string
  lastCheckAt?: string
  remark?: string
  createdAt?: string
  updatedAt?: string
}

/** 取链接的用途：复制 / 复制 Markdown / 下载 */
export type LinkMode = 'copy' | 'markdown' | 'download'

/** 存储配置的一条 CDN 线路 */
export interface CdnVO {
  key: string
  name: string
  /** 前缀模板（可能含 {owner} / {repo} / {branch} 占位符） */
  template: string
  /** 用样例对象键拼出来的真实地址，用来测速 */
  example?: string | null
  /** 是否是原始地址（存储后端直链） */
  origin: boolean
  builtin: boolean
  enabled: boolean
  previewDefault: boolean
  downloadDefault: boolean
  copyDefault: boolean
}

/** 一张图片可用的一种链接 */
export interface ImageLinkVO {
  key: string
  name: string
  url?: string | null
  markdown?: string | null
  origin: boolean
  /** 服务端代理下载（url 为空，走下载接口） */
  proxy: boolean
  builtin: boolean
  previewDefault: boolean
  downloadDefault: boolean
  copyDefault: boolean
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
  /** 预览地址（按「预览默认 CDN」解析，未配置就是 url） */
  previewUrl?: string
  /** 下载地址（按「下载默认 CDN」解析） */
  downloadUrl?: string
  /** 复制链接用的地址（按「复制默认 CDN」解析） */
  copyUrl?: string
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
  /** 移入回收站的时间，正常图片为 null */
  deletedAt?: string | null
  /** 在回收站目录里的对象键；为空说明云端文件还留在原位 */
  trashKey?: string | null
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
  /** 单张文件大小上限（字节）；-1 表示无上限 */
  maxUploadSize: number
  /** 上限的可读文案，例如「30.0 MB」「无上限」 */
  maxUploadSizeText: string
  unlimitedUploadSize: boolean
  allowedExtensions: string[]
  dedupeEnabled: boolean
  pathShardSize?: number
  importMaxFiles?: number
  migrateConcurrency?: number
}

export interface StatsVO {
  totalCount: number
  totalSize: number
  todayCount: number
  weekCount: number
  maxSize: number
  storageConfigCount: number
  trashCount: number
  trashSize: number
  duplicateGroups: number
  duplicateWastedSize: number
  shareCount: number
  shareViews: number
  typeDistribution: { name: string; label: string; value: number }[]
  recent: ImageVO[]
}

export interface AuditLogVO {
  id: number
  userId?: number
  username?: string
  action: string
  targetType?: string
  targetId?: string
  detail?: string
  success: number
  ip?: string
  userAgent?: string
  createdAt: string
}

export interface SettingsVO {
  /** 单张文件大小上限（字节）；-1 表示无上限 */
  maxUploadSize: number
  maxUploadSizeText: string
  unlimitedUploadSize: boolean
  /** 数据库里没设置过时用的出厂默认值（字节） */
  defaultMaxUploadSize: number
  /** 设置值本身的允许上限（字节） */
  maxUploadSizeLimit: number
  /** 登录失败几次后锁定 */
  loginMaxAttempts: number
  /** 锁定时长（分钟） */
  loginLockMinutes: number
  /** 防盗链开关 */
  hotlinkEnabled: boolean
  /** 防盗链白名单（逗号分隔） */
  hotlinkDomains: string
  /** 没有 Referer 时是否放行 */
  hotlinkAllowEmpty: boolean
}
