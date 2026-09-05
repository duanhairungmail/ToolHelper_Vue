import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { StatusType } from '@/types/tools'

export const useAppStore = defineStore('app', () => {
  const sidebarOpen = ref(false)
  const status = ref('远程外挂连接已就绪')
  const statusType = ref<StatusType>('idle')
  const javaHealth = ref<'unknown' | 'up' | 'down'>('unknown')
  const localHealth = ref<'unknown' | 'up' | 'down'>('unknown')

  function setStatus(message: string, type: StatusType = 'idle') {
    status.value = message
    statusType.value = type
  }

  return { sidebarOpen, status, statusType, javaHealth, localHealth, setStatus }
})

export type AppStore = ReturnType<typeof useAppStore>
