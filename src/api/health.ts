import { request } from './client'
import { joinApiUrl } from '@/config/runtime'

export interface HealthData {
  service: string
  version: string
  status: 'UP'
  traceId: string
}

export function checkHealth(base: string, path: string): Promise<HealthData> {
  return request<HealthData>(joinApiUrl(base, path))
}
