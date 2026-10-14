import { del, get, post, put } from './http'
import type { PageResult, UserVO } from '@/types'

export interface UserQuery {
  page: number
  size: number
  keyword?: string
}

/** 账号列表（仅超级管理员） */
export const fetchUsers = (params: UserQuery) =>
  get<PageResult<UserVO>>('/users', params as unknown as Record<string, unknown>)

/** 新建账号：角色固定为普通用户，超级管理员只能由数据库赋予 */
export const createUser = (data: { username: string; password: string; nickname?: string; email?: string }) =>
  post<UserVO>('/users', data)

/** 修改账号资料（昵称 / 邮箱），仅超级管理员 */
export const updateUser = (id: number, data: { nickname?: string; email?: string }) =>
  put<UserVO>('/users/' + id, data)

/** 启用（1）/ 禁用（0）账号 */
export const updateUserStatus = (id: number, status: number) => put<UserVO>('/users/' + id + '/status', { status })

export const resetUserPassword = (id: number, newPassword: string) =>
  put<void>('/users/' + id + '/password', { newPassword })

export const deleteUser = (id: number) => del<void>('/users/' + id)
