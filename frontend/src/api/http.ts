import axios, {
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig
} from 'axios'
import { ref } from 'vue'
import { clearToken, getToken } from './auth'
import type { R } from './types'

/** UUID（写接口 Idempotency-Key / X-Trace-Id 共用） */
export function uuid(): string {
  const c = globalThis.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0
    const v = ch === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/* ---------- 全局轻提示：错误统一提示 / 操作反馈 ---------- */
export interface Toast {
  id: number
  text: string
  type: 'error' | 'success' | 'info'
}
export const toasts = ref<Toast[]>([])
let toastSeq = 0
export function notify(text: string, type: Toast['type'] = 'error'): void {
  const id = ++toastSeq
  toasts.value.push({ id, text, type })
  window.setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }, 4000)
}

export class ApiError extends Error {
  constructor(message: string, readonly code: number, readonly traceId?: string) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 响应头 X-Trace-Id，便于排障展示 */
export const lastTraceId = ref<string>('')

let unauthorizedHandler: () => void = () => window.location.assign('/login')
export function setUnauthorizedHandler(fn: () => void): void {
  unauthorizedHandler = fn
}
function handleUnauthorized(): void {
  clearToken()
  notify('登录已失效，请重新登录')
  unauthorizedHandler()
}

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json; charset=utf-8' }
})

const WRITE_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE']

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  config.headers.set('X-Trace-Id', uuid())
  const method = (config.method || 'get').toUpperCase()
  // 契约：所有写接口强制 Idempotency-Key
  if (WRITE_METHODS.includes(method)) config.headers.set('Idempotency-Key', uuid())
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse) => {
    const trace = response.headers?.['x-trace-id'] as string | undefined
    if (trace) lastTraceId.value = trace

    // 文件流（CSV 导出由后端生成）直接放行，不解包
    if (response.config.responseType === 'blob') return response

    const body = response.data as R<unknown> | undefined
    if (body && typeof body === 'object' && 'code' in body) {
      const r = body
      if (r.code === 0) {
        response.data = r.data // 统一解包：调用方直接拿到 data
        return response
      }
      // 40101 / 40102：未登录 / token 过期
      if (r.code === 40101 || r.code === 40102) handleUnauthorized()
      const message = r.message || `请求失败 (code=${r.code})`
      notify(message)
      return Promise.reject(new ApiError(message, r.code, r.traceId))
    }
    return response
  },
  (error) => {
    const status: number | undefined = error?.response?.status
    const body = error?.response?.data as R<unknown> | undefined
    const trace = error?.response?.headers?.['x-trace-id'] as string | undefined
    if (trace) lastTraceId.value = trace
    if (status === 401) {
      handleUnauthorized()
      return Promise.reject(error)
    }
    const message = body?.message || error?.message || '网络异常，请稍后重试'
    notify(message)
    return Promise.reject(error)
  }
)

function unwrap<T>(p: Promise<AxiosResponse<T>>): Promise<T> {
  return p.then((r) => r.data)
}

/** 业务层统一使用：返回值即 R.data */
export const api = {
  get: <T>(url: string, config?: AxiosRequestConfig) => unwrap<T>(http.get<T>(url, config)),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    unwrap<T>(http.post<T>(url, data, config)),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    unwrap<T>(http.put<T>(url, data, config)),
  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    unwrap<T>(http.patch<T>(url, data, config)),
  delete: <T>(url: string, config?: AxiosRequestConfig) =>
    unwrap<T>(http.delete<T>(url, config))
}

/** 触发后端生成的 CSV 文件下载，前端不拼数据、不聚合 */
export async function downloadFile(
  url: string,
  params?: Record<string, unknown>,
  fallbackName = 'change-events.csv'
): Promise<void> {
  const resp = await http.get<Blob>(url, { params, responseType: 'blob' })
  let filename = fallbackName
  const disposition = resp.headers?.['content-disposition'] as string | undefined
  if (disposition) {
    const m = /filename\*?=(?:UTF-8'')?["']?([^;"']+)/i.exec(disposition)
    if (m) {
      try {
        filename = decodeURIComponent(m[1])
      } catch {
        filename = m[1]
      }
    }
  }
  const href = URL.createObjectURL(resp.data)
  const a = document.createElement('a')
  a.href = href
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(href)
}

export default http
