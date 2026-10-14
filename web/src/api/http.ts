import axios from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types'

export const TOKEN_KEY = 'qxfly_image_host_token'

/**
 * 浏览器端默认走同源 /api（由 Vite dev server / Nginx 反向代理到后端）；
 * Tauri 桌面端页面由 tauri://localhost 提供，必须使用绝对地址访问后端服务。
 */
function resolveApiBase(): string {
  const configured = import.meta.env.VITE_API_BASE as string | undefined
  if (configured) {
    return configured
  }
  const inTauri = isTauri()
  // 桌面端必须用绝对地址；端口可用 VITE_BACKEND_PORT 覆盖（默认 8080）
  const backendPort = (import.meta.env.VITE_BACKEND_PORT as string | undefined) || '8080'
  return inTauri ? 'http://127.0.0.1:' + backendPort + '/api' : '/api'
}

/** 是否跑在 Tauri 桌面端里 */
export function isTauri(): boolean {
  return (
    typeof window !== 'undefined' &&
    ('__TAURI_INTERNALS__' in window || '__TAURI__' in window)
  )
}

const instance = axios.create({
  baseURL: resolveApiBase(),
  timeout: 180000,
})

/**
 * 桌面端：用 Rust 侧 `app_info` 给的地址覆盖接口地址。
 * <p>
 * 这样**同一个安装包能指向任意后端**，不用重新打包：启动前设置环境变量
 * `QXFLY_IMAGE_HOST_API_BASE`（例如 https://img.example.com）即可，
 * 没设置时用打包时写死的地址（默认 http://127.0.0.1:8080）。
 * 不依赖 @tauri-apps/api，直接用 Tauri 注入到 window 上的 invoke。
 */
export async function applyDesktopApiBase(): Promise<string | null> {
  if (!isTauri()) {
    return null
  }
  try {
    const internals = (window as unknown as {
      __TAURI_INTERNALS__?: { invoke?: (cmd: string) => Promise<{ api_base?: string }> }
    }).__TAURI_INTERNALS__
    if (!internals || typeof internals.invoke !== 'function') {
      return null
    }
    const info = await internals.invoke('app_info')
    const base = info && info.api_base ? info.api_base.replace(/\/+$/, '') : ''
    if (!base) {
      return null
    }
    // 允许写 "http://host:8080" 或 "http://host:8080/api"，两种都认
    instance.defaults.baseURL = base.endsWith('/api') ? base : base + '/api'
    return instance.defaults.baseURL
  } catch {
    // 拿不到就用打包时的默认地址
    return null
  }
}

instance.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

function isBlob(value: unknown): value is Blob {
  return typeof Blob !== 'undefined' && value instanceof Blob
}

/** 下载接口报错时回的仍是 JSON，只是被包成了 Blob，这里把它翻成人话 */
async function readBlobMessage(blob: Blob): Promise<string | null> {
  try {
    const body = JSON.parse(await blob.text())
    if (body && typeof body.message === 'string' && body.message) {
      return body.message
    }
  } catch {
    // 不是 JSON（例如网关返回的 HTML 错误页）就沿用默认提示
  }
  return null
}

instance.interceptors.response.use(
  async (response) => {
    const body = response.data
    // 下载类请求：接口失败时 HTTP 仍是 200，但响应体是 JSON，需要单独认出来
    if (response.config.responseType === 'blob' && isBlob(body)) {
      const message = body.type.includes('json') ? await readBlobMessage(body) : null
      if (message) {
        ElMessage.error(message)
        return Promise.reject(new Error(message))
      }
      return body
    }
    if (body && typeof body.code === 'number' && body.code !== 200) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  async (error) => {
    const status = error.response ? error.response.status : 0
    const data = error.response ? error.response.data : null
    let message = (data && data.message) || error.message || '网络异常'
    if (isBlob(data)) {
      message = (await readBlobMessage(data)) || message
    }
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

/** 二进制 GET（单张下载）：响应是文件流，拿到的就是 Blob */
export function getBlob(url: string, params?: Record<string, unknown>): Promise<Blob> {
  return instance.get(url, { params, responseType: 'blob' }) as unknown as Promise<Blob>
}

/** 二进制 POST（批量下载打包）：请求体是 JSON，响应是文件流 */
export function postBlob(url: string, data?: unknown): Promise<Blob> {
  return instance.post(url, data, { responseType: 'blob' }) as unknown as Promise<Blob>
}

export default instance
