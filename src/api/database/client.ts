import { createTraceId, getRuntimeConfig, joinApiUrl } from '@/config/runtime'
import { request } from '@/api/client'

export interface DatabaseSession {
  sessionId: string
  path: string
  openedAt: string
}

export interface DatabaseObject {
  type: string
  name: string
  tableName: string
}

export interface DatabaseColumn {
  name: string
  type: string
  primaryKey: boolean
  notNull: boolean
}

export interface QueryResult {
  taskId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  columns: string[]
  rows: unknown[][]
  rowCount: number
  hasMore: boolean
  errorCode: string | null
  traceId: string
}

const javaBase = () => getRuntimeConfig().javaApiBase

export function createDatabaseSession(path: string, password: string): Promise<DatabaseSession> {
  return request<DatabaseSession>(joinApiUrl(javaBase(), '/api/database/sessions'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, password: [...password] })
  })
}

export function testDatabaseSession(sessionId: string): Promise<boolean> {
  return request<boolean>(joinApiUrl(javaBase(), `/api/database/sessions/${sessionId}/test`), { method: 'POST' })
}

export function closeDatabaseSession(sessionId: string): Promise<void> {
  return request<void>(joinApiUrl(javaBase(), `/api/database/sessions/${sessionId}`), { method: 'DELETE' })
}

export function loadDatabaseMetadata(sessionId: string): Promise<DatabaseObject[]> {
  return request<DatabaseObject[]>(joinApiUrl(javaBase(), `/api/database/sessions/${sessionId}/metadata`))
}

export function loadDatabaseColumns(sessionId: string, table: string): Promise<DatabaseColumn[]> {
  return request<DatabaseColumn[]>(joinApiUrl(javaBase(), `/api/database/sessions/${sessionId}/metadata/${encodeURIComponent(table)}/columns`))
}

export function submitDatabaseMutation(sessionId: string, table: string, primaryKeyColumn: string, primaryKeyValue: unknown, changes: Record<string, unknown>) {
  return request<number>(joinApiUrl(javaBase(), `/api/database/sessions/${sessionId}/mutations`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ table, primaryKeyColumn, primaryKeyValue, changes, confirmHighRisk: true })
  })
}

export function submitDatabaseQuery(sessionId: string, sql: string, page: number, pageSize: number, confirmHighRisk: boolean) {
  return request<{ taskId: string; traceId: string }>(joinApiUrl(javaBase(), `/api/database/sessions/${sessionId}/queries`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sql, page, pageSize, confirmHighRisk })
  })
}

export function getDatabaseQueryResult(taskId: string): Promise<QueryResult> {
  return request<QueryResult>(joinApiUrl(javaBase(), `/api/database/queries/${taskId}`))
}

export function cancelDatabaseQuery(taskId: string): Promise<void> {
  return request<void>(joinApiUrl(javaBase(), `/api/database/queries/${taskId}/cancel`), { method: 'POST' })
}

export async function downloadDatabaseQuery(taskId: string): Promise<Blob> {
  const runtime = getRuntimeConfig()
  const response = await fetch(joinApiUrl(javaBase(), `/api/database/queries/${taskId}/export?format=csv`), {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${runtime.sessionToken}`,
      Origin: window.location.origin,
      'X-Trace-Id': createTraceId()
    }
  })
  if (!response.ok) throw new Error(`导出失败：${response.status}`)
  return response.blob()
}
