export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T | null
  traceId: string
}

export interface RequestOptions extends RequestInit {
  timeoutMs?: number
}

export class ApiError extends Error {
  readonly code: string
  readonly traceId?: string
  readonly status?: number

  constructor(message: string, options: { code?: string; traceId?: string; status?: number } = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = options.code || 'UNKNOWN_ERROR'
    this.traceId = options.traceId
    this.status = options.status
  }
}

export interface ToolService<TRequest, TResult> {
  execute(request: TRequest, signal?: AbortSignal): Promise<TResult>
}
