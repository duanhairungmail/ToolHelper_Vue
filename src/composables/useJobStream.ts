import { ref } from 'vue'

export function useJobStream(initialLines: readonly string[] = []) {
  const lines = ref<string[]>([...initialLines])

  function append(line: string) {
    lines.value.push(`[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${line}`)
  }

  function clear() {
    lines.value = []
  }

  return { lines, append, clear }
}
