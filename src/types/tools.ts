export type ToolId =
  | 'remote'
  | 'database'
  | 'api'
  | 'mac'
  | 'nodered'
  | 'druid'
  | 'kylin'
  | 'serial'
  | 'modbus'
  | 'ping'
  | 'cron'
  | 'sql'
  | 'aes'

export type StatusType = 'idle' | 'loading' | 'success' | 'error'

export interface ToolDefinition {
  id: ToolId
  group: string
  name: string
  keywords: readonly string[]
  lead: string
  action: string
}
