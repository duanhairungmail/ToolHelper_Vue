import type { ToolService } from '../contracts'

export interface JavaLifecycleRequest {
  action: 'install' | 'start' | 'restart' | 'stop'
}

export interface JavaLifecycleResult {
  action: JavaLifecycleRequest['action']
  status: 'demo'
  message: string
}

export type JavaLifecycleService = ToolService<JavaLifecycleRequest, JavaLifecycleResult>
