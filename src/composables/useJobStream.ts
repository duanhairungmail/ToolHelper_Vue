import { onScopeDispose, ref } from 'vue'
import { createTraceId, getRuntimeConfig } from '@/config/runtime'

export interface JobEvent {
  eventId: string
  type: string
  jobId: string
  timestamp: string
  traceId: string
  payload: { level?: string; message?: string; [key: string]: unknown }
}

interface ConnectOptions {
  signal?: AbortSignal
  reconnect?: boolean
}

export function useJobStream(initialLines: readonly string[] = []) {
  const lines = ref<string[]>([...initialLines])
  const lastEventId = ref('')
  const seenEventIds = new Set<string>()
  let disposed = false
  let activeController: AbortController | undefined

  function append(line: string) {
    lines.value.push(`[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${line}`)
  }

  function clear() {
    lines.value = []
  }

  function dispose() {
    disposed = true
    activeController?.abort()
  }

  async function connect(url: string, options: ConnectOptions = {}): Promise<void> {
    disposed = false
    activeController?.abort()
    const controller = new AbortController()
    activeController = controller
    const abort = () => controller.abort()
    options.signal?.addEventListener('abort', abort, { once: true })
    let attempt = 0

    try {
      while (!disposed) {
        try {
          const runtime = getRuntimeConfig()
          const headers = new Headers({ Accept: 'text/event-stream', 'X-Trace-Id': createTraceId() })
          if (runtime.sessionToken) headers.set('Authorization', `Bearer ${runtime.sessionToken}`)
          if (lastEventId.value) headers.set('Last-Event-ID', lastEventId.value)
          const response = await fetch(url, { headers, signal: controller.signal })
          if (!response.ok || !response.body) throw new Error(`SSE 请求失败：${response.status}`)
          attempt = 0
          await consume(response.body, (event) => {
            if (seenEventIds.has(event.eventId)) return
            seenEventIds.add(event.eventId)
            lastEventId.value = event.eventId
            append(event.payload.message || event.type)
          }, controller.signal)
          if (!options.reconnect) break
          await delay(1000, controller.signal)
        } catch (error) {
          if (controller.signal.aborted || disposed) break
          attempt += 1
          append(`日志流断开，${Math.min(attempt, 5)} 秒后重连`)
          await delay(Math.min(attempt * 1000, 5000), controller.signal)
        }
      }
    } finally {
      options.signal?.removeEventListener('abort', abort)
      if (activeController === controller) activeController = undefined
    }
  }

  onScopeDispose(dispose)
  return { lines, lastEventId, append, clear, connect, dispose }
}

async function consume(body: ReadableStream<Uint8Array>, onEvent: (event: JobEvent) => void, signal: AbortSignal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (!signal.aborted) {
    const chunk = await reader.read()
    if (chunk.done) break
    buffer += decoder.decode(chunk.value, { stream: true })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() || ''
    for (const frame of frames) {
      const event = parseEvent(frame)
      if (event) onEvent(event)
    }
  }
}

function parseEvent(frame: string): JobEvent | undefined {
  const fields = new Map<string, string>()
  for (const line of frame.split(/\r?\n/)) {
    const separator = line.indexOf(':')
    if (separator > 0) fields.set(line.slice(0, separator), line.slice(separator + 1).trimStart())
  }
  const data = fields.get('data')
  if (!data) return undefined
  try {
    return JSON.parse(data) as JobEvent
  } catch {
    return undefined
  }
}

function delay(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    const timer = window.setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      window.clearTimeout(timer)
      resolve()
    }, { once: true })
  })
}
