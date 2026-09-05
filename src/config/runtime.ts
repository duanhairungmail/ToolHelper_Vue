export interface RuntimeConfig {
  javaApiBase: string
  localApiBase: string
  sessionToken: string
}

const emptyRuntime: RuntimeConfig = { javaApiBase: '', localApiBase: '', sessionToken: '' }

export function getRuntimeConfig(): RuntimeConfig {
  const candidate = window.__TOOLHELPER_RUNTIME_CONFIG__
  if (!candidate) return emptyRuntime
  return {
    javaApiBase: candidate.javaApiBase.trim().replace(/\/$/, ''),
    localApiBase: candidate.localApiBase.trim().replace(/\/$/, ''),
    sessionToken: candidate.sessionToken.trim()
  }
}

export function runtimeConfigured(): boolean {
  const config = getRuntimeConfig()
  return Boolean(config.javaApiBase && config.localApiBase && config.sessionToken)
}

export function createTraceId(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function joinApiUrl(base: string, path: string): string {
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}
