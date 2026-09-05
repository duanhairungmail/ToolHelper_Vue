export function required(value: unknown, label: string): string | undefined {
  if (String(value ?? '').trim()) return
  return `${label}不能为空`
}

export function isValidHost(value: string): boolean {
  return /^[a-zA-Z0-9][a-zA-Z0-9._:-]*$/.test(value.trim())
}

export function isValidPort(value: string | number): boolean {
  const port = Number(value)
  return Number.isInteger(port) && port > 0 && port <= 65535
}
