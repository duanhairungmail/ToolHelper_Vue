import { ApiError } from './contracts'
import type { ApiResponse, RequestOptions } from './contracts'
import { createTraceId, getRuntimeConfig } from '@/config/runtime'

export async function request<T>(url: string, options: RequestOptions = {}): Promise<T> {
  const { timeoutMs = 15_000, signal, ...init } = options
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })

  try {
    const runtime = getRuntimeConfig()
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    headers.set('X-Trace-Id', createTraceId())
    if (runtime.sessionToken) headers.set('Authorization', `Bearer ${runtime.sessionToken}`)
    const response = await fetch(url, { ...init, headers, signal: controller.signal })
    const payload = await response.json() as ApiResponse<T> | T
    if (!response.ok) {
      throw new ApiError(`请求失败：${response.status}`, { status: response.status })
    }
    if (payload && typeof payload === 'object' && 'success' in payload && payload.success === false) {
      const result = payload as ApiResponse<T>
      throw new ApiError(result.message, { code: result.code, traceId: result.traceId })
    }
    return payload && typeof payload === 'object' && 'data' in payload
      ? (payload as ApiResponse<T>).data
      : payload as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError('请求已取消或超时', { code: 'REQUEST_ABORTED' })
    }
    throw new ApiError(error instanceof Error ? error.message : '网络请求失败', { code: 'NETWORK_ERROR' })
  } finally {
    window.clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}
