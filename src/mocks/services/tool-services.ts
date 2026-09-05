import type { JavaLifecycleRequest, JavaLifecycleResult } from '@/api/java/client'
import type { ToolService } from '@/api/contracts'

export interface DemoResult {
  status: 'demo'
  message: string
  details?: string
}

function wait(signal?: AbortSignal, delay = 260) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'))
      return
    }
    const timer = window.setTimeout(resolve, delay)
    signal?.addEventListener('abort', () => {
      window.clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    }, { once: true })
  })
}

export function demoService<TRequest>(label: string): ToolService<TRequest, DemoResult> {
  return {
    async execute(_request, signal) {
      await wait(signal)
      return { status: 'demo', message: `${label}已完成`, details: '演示模式：等待后端服务接入' }
    }
  }
}

export const remoteService: ToolService<JavaLifecycleRequest, JavaLifecycleResult> = {
  async execute(request, signal) {
    await wait(signal)
    return { action: request.action, status: 'demo', message: `远程外挂${request.action}已完成（演示模式）` }
  }
}

export interface AesRequest {
  action: 'encrypt' | 'decrypt'
  mode: string
  padding: string
  key: string
  iv: string
  input: string
}

export interface AesResult {
  status: 'demo'
  output: string
  message: string
}

export const aesService: ToolService<AesRequest, AesResult> = {
  async execute(request, signal) {
    await wait(signal)
    return {
      status: 'demo',
      output: `演示模式：${request.action === 'encrypt' ? '加密' : '解密'}结果待 Java AES 服务接入`,
      message: `AES ${request.action === 'encrypt' ? '加密' : '解密'}请求已提交`
    }
  }
}

export const nodeRedService = demoService('Node-RED 运行时操作')
export const databaseService = demoService('SQLite 工作台操作')
export const serialService = demoService('串口操作')
export const modbusService = demoService('Modbus 操作')
export const pingService = demoService('Ping 扫描')
export const cronService = demoService('Cron 预览')
export const sqlService = demoService('SQL 操作')
export const apiService = demoService('接口验证')
export const macService = demoService('MAC 地址扫描')
export const druidService = demoService('Druid 规则扫描')
export const kylinService = demoService('KylinOS 策略操作')
