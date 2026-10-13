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
export const register = (data: RegisterPayload) => post<UserVO>('/auth/register', data)
export const fetchMe = () => get<UserVO>('/auth/me')
export const updateProfile = (data: { nickname?: string; email?: string }) => put<UserVO>('/auth/profile', data)
export const changePassword = (data: { oldPassword: string; newPassword: string }) => put<void>('/auth/password', data)
