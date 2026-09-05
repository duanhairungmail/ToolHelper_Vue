import { ref } from 'vue'

export function useLogDrawer(initialLines: readonly string[] = []) {
  const expanded = ref(true)
  const lines = ref<string[]>([...initialLines])

  function append(message: string) {
    lines.value.push(`[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${message}`)
  }

  function clear() {
    lines.value = []
  }

  return { expanded, lines, append, clear }
}
