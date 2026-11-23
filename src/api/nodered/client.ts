import { request } from '@/api/client'
import { getRuntimeConfig, joinApiUrl } from '@/config/runtime'

export interface NodeRedStatus {
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
export interface NodeInspection {
  id: string
  sha256: string
  allowlisted: boolean
  approved: boolean
  expiresAt: string
  review: {
    packages: { path: string; name: string; version: string; sha256: string; license: string; maintenance: string; vulnerabilities: string; installScripts: Record<string, string> }[]
    nativeDependencies: string[]
    scripts: { path: string; source: string }[]
    permissions: string[]
    networkAccess: string[]
    rollbackImpact: string
    reviewedAt: string
  }
}
export interface SerialLease { id: string; port: string; owner: string; pid: number; releaseHint: string }
export type NodeRedAction = 'install' | 'updates' | 'start' | 'stop' | 'restart'
export const nodeRedUrl = (part: string) => joinApiUrl(getRuntimeConfig().javaApiBase, `/api/integrations/nodered/${part}`)
const post = <T>(part: string, body?: unknown, timeoutMs = 240_000) => request<T>(nodeRedUrl(part), {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), timeoutMs
})
export const getNodeRedStatus = () => request<NodeRedStatus>(nodeRedUrl('status'))
export const controlNodeRed = (action: NodeRedAction) => post<NodeRedStatus>(action, { confirmed: true })
export function importNodeRed(file: File, nodes = false) {
  const body = new FormData(); body.set('package', file); body.set('confirmed', 'true')
  return nodes ? request<NodeInspection>(nodeRedUrl('nodes/review'), { method: 'POST', body, timeoutMs: 240_000 })
    : request<NodeRedStatus>(nodeRedUrl('install'), { method: 'POST', body, timeoutMs: 240_000 })
}
export const approveNode = (id: string, reviewer: string, reason: string) => post<NodeInspection>(`nodes/${encodeURIComponent(id)}/approve`, { reviewer, reason })
export const installNode = (id: string) => post<NodeRedStatus>(`nodes/${encodeURIComponent(id)}/install`, { confirmed: true })
export const cancelNode = (id: string) => request<void>(nodeRedUrl(`nodes/${encodeURIComponent(id)}`), { method: 'DELETE' })
export const removeNodeRedRuntime = () => request<NodeRedStatus>(nodeRedUrl('runtime?confirmed=true'), { method: 'DELETE' })
export const backupNodeRed = () => post<string>('backups')
export const restoreNodeRed = (id: string) => post<NodeRedStatus>('restore', { id, confirmed: true })
export const getSerialLeases = () => request<SerialLease[]>(joinApiUrl(getRuntimeConfig().javaApiBase, '/api/internal/v1/serial/leases'))

/** 地址必须来自 Java，并精确匹配实际回环端口；不接受任何凭据、查询或路径。 */
export function trustedNodeRedUrl(value: string, port: number): string {
  const url = new URL(value)
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || Number(url.port) !== port || url.username || url.password || url.search || url.hash || url.pathname !== '/')
    throw new Error('Java返回的Node-RED地址不受信任')
  return url.href
}
