import type { ToolService } from '../contracts'

export interface LocalTaskResult {
  status: 'demo'
  message: string
}

export type LocalTaskService<TRequest = Record<string, unknown>> = ToolService<TRequest, LocalTaskResult>
