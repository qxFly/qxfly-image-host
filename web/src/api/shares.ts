import { del, get, post } from './http'

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

/** 公开访问相册（不需要登录；有密码时传 password） */
export const viewShare = (token: string, password?: string) =>
  post<ShareVO>('/shares/' + token + '/view', password ? { password } : {})
