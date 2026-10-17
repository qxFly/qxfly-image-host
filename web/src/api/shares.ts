import { del, get, getBlob, post } from './http'

export interface ShareVO {
  id: number
  token: string
  /** 后端给的相对地址：/#/s/{token} */
  url: string
  title?: string
  passwordProtected: boolean
  expiresAt?: string
  enabled: boolean
  expired: boolean
  viewCount?: number
  imageCount: number
  createdAt?: string
  images?: import('@/types').ImageVO[]
}

/** 创建分享（仅超级管理员） */
export const createShare = (data: {
  title?: string
  imageIds: number[]
  password?: string
  expiresInDays?: number
}) => post<ShareVO>('/shares', data)

/** 我创建的分享列表 */
export const fetchShares = () => get<ShareVO[]>('/shares')

/** 撤销分享 */
export const deleteShare = (id: number) => del<void>('/shares/' + id)

/** 访问明细（仅超级管理员） */
export const fetchShareViews = (id: number) =>
  get<{ id: number; shareId: number; ip?: string; userAgent?: string; createdAt: string }[]>(
    '/shares/' + id + '/views',
  )

/** 公开打包下载相册（密码相册把密码传进去；返回 zip 流） */
export const downloadShare = (token: string, password?: string) =>
  getBlob('/shares/' + token + '/download', password ? { password } : {})

/** 公开访问相册（不需要登录；有密码时传 password） */
export const viewShare = (token: string, password?: string) =>
  post<ShareVO>('/shares/' + token + '/view', password ? { password } : {})
