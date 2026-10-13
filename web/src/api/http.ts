import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types'

export const TOKEN_KEY = 'pichost_token'

/**
 * 浏览器端默认走同源 /api（由 Vite dev server / Nginx 反向代理到后端）；
 * Tauri 桌面端页面由 tauri://localhost 提供，必须使用绝对地址访问后端服务。
 */
function resolveApiBase(): string {
  const configured = import.meta.env.VITE_API_BASE as string | undefined
  if (configured) {
    return configured
  }
  const inTauri =
    typeof window !== 'undefined' &&
    ('__TAURI_INTERNALS__' in window || '__TAURI__' in window)
  return inTauri ? 'http://127.0.0.1:8080/api' : '/api'
}

const instance = axios.create({
  baseURL: resolveApiBase(),
  timeout: 180000,
})

instance.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

instance.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number' && body.code !== 200) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const status = error.response ? error.response.status : 0
    const data = error.response ? error.response.data : null
    const message = (data && data.message) || error.message || '网络异常'
    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (window.location.hash.indexOf('/login') < 0) {
        window.location.hash = '#/login'
      }
    }
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

export function get<T>(url: string, params?: Record<string, unknown>): Promise<ApiResult<T>> {
  return instance.get(url, { params }) as unknown as Promise<ApiResult<T>>
}

export function post<T>(url: string, data?: unknown, config?: Record<string, unknown>): Promise<ApiResult<T>> {
  return instance.post(url, data, config) as unknown as Promise<ApiResult<T>>
}

export function put<T>(url: string, data?: unknown): Promise<ApiResult<T>> {
  return instance.put(url, data) as unknown as Promise<ApiResult<T>>
}

export function del<T>(url: string): Promise<ApiResult<T>> {
  return instance.delete(url) as unknown as Promise<ApiResult<T>>
}

export default instance
