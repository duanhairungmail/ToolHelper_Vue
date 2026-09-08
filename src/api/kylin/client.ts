import { request } from '@/api/client'
import { getRuntimeConfig, joinApiUrl } from '@/config/runtime'
import type { KylinOperation } from './types'
export type { KylinOperation } from './types'
export interface KylinSessionRequest { host: string; port?: number; username: string; password?: string; privateKey?: string; privateKeyPassphrase?: string; sudoPassword?: string }
export interface KylinSession { id: string; host: string; port: number; username: string; state: string; architecture: string; sftpReady: boolean; createdAt: string; lastUsedAt: string }
export interface KylinJob { id: string; sessionId: string; operation: KylinOperation; state: string; steps: Array<{ name: string; status: string; exitCode: number; changed: boolean; rollbackAvailable: boolean; message: string }>; message: string; createdAt: string; finishedAt?: string }
const base = () => getRuntimeConfig().javaApiBase
export function openKylinSession(body: KylinSessionRequest, signal?: AbortSignal) { return request<KylinSession>(joinApiUrl(base(), '/api/java/kylin/sessions'), { method: 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' }, signal, timeoutMs: 35_000 }) }
export function closeKylinSession(id: string) { return request<void>(joinApiUrl(base(), `/api/java/kylin/sessions/${id}`), { method: 'DELETE' }) }
export function issueKylinConfirmation(id: string, operation: KylinOperation) { return request<{ token: string; expiresAt: string }>(joinApiUrl(base(), `/api/java/kylin/sessions/${id}/confirmations?operation=${encodeURIComponent(operation)}`), { method: 'POST' }) }
export function submitKylinJob(sessionId: string, operation: KylinOperation, confirmationToken?: string, confirm = false) { return request<KylinJob>(joinApiUrl(base(), `/api/java/kylin/sessions/${sessionId}/jobs`), { method: 'POST', body: JSON.stringify({ operation, confirmationToken, confirm }), headers: { 'Content-Type': 'application/json' } }) }
export function getKylinJob(id: string) { return request<KylinJob>(joinApiUrl(base(), `/api/java/jobs/${id}`)) }
