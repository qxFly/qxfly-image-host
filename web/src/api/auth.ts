import { get, post, put } from './http'
import type { LoginResponse, UserVO } from '@/types'

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload extends LoginPayload {
  nickname?: string
  email?: string
}

export const login = (data: LoginPayload) => post<LoginResponse>('/auth/login', data)

/** 是否开放自助注册（默认关闭，账号由超级管理员创建） */
export const fetchRegisterEnabled = () => get<{ enabled: boolean }>('/auth/register-enabled')
export const register = (data: RegisterPayload) => post<UserVO>('/auth/register', data)
export const fetchMe = () => get<UserVO>('/auth/me')
export const updateProfile = (data: { nickname?: string; email?: string }) => put<UserVO>('/auth/profile', data)
export const changePassword = (data: { oldPassword: string; newPassword: string }) => put<void>('/auth/password', data)
