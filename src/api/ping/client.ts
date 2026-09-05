import { request } from '@/api/client'
import { createTraceId, getRuntimeConfig, joinApiUrl } from '@/config/runtime'

export interface PingTarget { address: string; inputIndex: number }
export interface ExpandResponse { targets: PingTarget[]; truncated: boolean; estimatedTotal: number }
export interface PingStartResponse { jobId: string; targetTotal: number; truncated: boolean; estimatedTotal: number }
export interface PingResult { address: string; inputIndex: number; completionIndex: number; status: 'Online' | 'PartialLoss' | 'Offline'; attempts: number; successCount: number; averageDelayMs: number | null; packetLossPercent: number; error?: string }
export interface PingSummary { targetTotal: number; online: number; offline: number; partialLoss: number; averageDelayMs: number | null }

const localBase = () => getRuntimeConfig().localApiBase
export function expandPingTargets(input: string) { return request<ExpandResponse>(joinApiUrl(localBase(), '/api/local/ping/targets/expand'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ input }) }) }
export function startPingJob(input: string, options: { concurrency: number; count: number; timeoutMs: number }) { return request<PingStartResponse>(joinApiUrl(localBase(), '/api/local/ping/jobs'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ input, ...options }) }) }
export function cancelPingJob(jobId: string) { return request<void>(joinApiUrl(localBase(), `/api/local/ping/jobs/${jobId}/cancel`), { method: 'POST' }) }
export async function downloadPing(jobId: string, format: 'csv' | 'xlsx') { const runtime = getRuntimeConfig(); const response = await fetch(joinApiUrl(localBase(), `/api/local/ping/jobs/${jobId}/export?format=${format}`), { headers: { Authorization: `Bearer ${runtime.sessionToken}`, Origin: window.location.origin, 'X-Trace-Id': createTraceId() } }); if (!response.ok) throw new Error(`导出失败：${response.status}`); return response.blob() }
