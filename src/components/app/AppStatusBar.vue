<script setup lang="ts">
import { onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { checkHealth } from '@/api/health'
import { getRuntimeConfig, runtimeConfigured } from '@/config/runtime'

const store = useAppStore()

onMounted(async () => {
  if (!runtimeConfigured()) return
  const runtime = getRuntimeConfig()
  const checks = [
    checkHealth(runtime.javaApiBase, '/actuator/health').then(() => { store.javaHealth = 'up' }).catch(() => { store.javaHealth = 'down' }),
    checkHealth(runtime.localApiBase, '/health').then(() => { store.localHealth = 'up' }).catch(() => { store.localHealth = 'down' })
  ]
  await Promise.all(checks)
})
</script>

<template>
  <footer class="statusbar">
    <div class="status" :class="store.statusType"><span class="status-dot" aria-hidden="true"></span><span>{{ store.status }}</span></div>
    <div class="service-health" aria-label="服务状态">
      <span :class="`health-${store.javaHealth}`">Java {{ store.javaHealth === 'up' ? '正常' : store.javaHealth === 'down' ? '离线' : '未配置' }}</span>
      <span :class="`health-${store.localHealth}`">C# {{ store.localHealth === 'up' ? '正常' : store.localHealth === 'down' ? '离线' : '未配置' }}</span>
    </div>
    <span class="shortcut">Ctrl + K 搜索</span>
  </footer>
</template>
