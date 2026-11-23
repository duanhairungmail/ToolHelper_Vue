import { request } from '@/api/client'
import { getRuntimeConfig, joinApiUrl } from '@/config/runtime'

export interface ElectermStatus {
  state: 'NOT_INSTALLED' | 'STOPPED' | 'STARTING' | 'RUNNING'
  pid: number | null
  startedAt: string | null
  version: string | null
  nodeVersion: string | null
  port: number
  instanceId: string
  url: string | null
  diagnostic: string | null
}
export type ElectermAction = 'install' | 'start' | 'stop' | 'restart'
const url = (path: string) => joinApiUrl(getRuntimeConfig().javaApiBase, `/api/integrations/electerm/${path}`)

export function getElectermStatus() { return request<ElectermStatus>(url('status')) }
export function getElectermLogs() { return request<string[]>(url('logs')) }
export function controlElecterm(action: ElectermAction) {
  return request<ElectermStatus>(url(action), { method: 'POST', timeoutMs: action === 'install' ? 240_000 : 40_000 })
}
export function importElecterm(file: File) {
  const body = new FormData()
  body.set('package', file)
  return request<ElectermStatus>(url('install'), { method: 'POST', body, timeoutMs: 240_000 })
}

/** 只接受 Java 返回的无凭据回环地址，阻止外部 URL、查询参数和脚本跳转。 */
export function trustedElectermUrl(value: string, expectedPort: number): string {
  const parsed = new URL(value)
  if (parsed.protocol !== 'http:' || parsed.hostname !== '127.0.0.1' || Number(parsed.port) !== expectedPort || parsed.username || parsed.password || parsed.search || parsed.hash || parsed.pathname !== '/') {
    throw new Error('Java 返回的 Electerm 地址不受信任')
  }
  return parsed.href
}
