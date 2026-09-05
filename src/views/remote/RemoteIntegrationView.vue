<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { checkHealth } from '@/api/health'
import { createTraceId, getRuntimeConfig, joinApiUrl, runtimeConfigured } from '@/config/runtime'
import type { JavaLifecycleRequest } from '@/api/java/client'

const store = useAppStore()
const { lines, append, clear, connect } = useJobStream(['远程外挂连接页面已就绪'])
const running = ref(false)
const connected = ref(false)
const expanded = ref(true)

async function run(action: JavaLifecycleRequest['action']) {
  if (running.value) return
  if (!runtimeConfigured()) {
    append('运行时未配置，无法连接 Java 服务')
    store.setStatus('Java 服务未配置', 'error')
    return
  }

  running.value = true
  connected.value = false
  append(`请求远程外挂${action}`)
  const runtime = getRuntimeConfig()
  const jobId = createTraceId()
  try {
    await checkHealth(runtime.javaApiBase, '/actuator/health')
    append('Java API 健康检查通过')
    await connect(joinApiUrl(runtime.javaApiBase, `/api/jobs/${jobId}/events`), { reconnect: false })
    connected.value = true
    store.setStatus(`远程外挂${action}完成`, 'success')
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Java API 请求失败'
    append(message)
    store.setStatus(message, 'error')
  } finally {
    running.value = false
  }
}

function openWeb() {
  window.open('https://cloud.electerm.org', '_blank', 'noopener,noreferrer')
  append('已请求系统浏览器打开 Electerm Web')
  store.setStatus('已打开 Electerm Web', 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="remote">
    <div class="tool-screen with-log" data-od-id="remote-screen">
      <BasePanel title="Electerm Web 运行状态">
        <template #actions><span class="badge" :class="running ? 'warn' : connected ? '' : 'warn'">{{ running ? '处理中' : connected ? '已连接' : '待连接' }}</span></template>
        <p class="muted">仅管理 Electerm Web 外挂生命周期，不在此页面收集 SSH、RDP、VNC 凭据。</p>
        <div class="button-row top-gap">
          <button class="btn btn-primary" type="button" :disabled="running" @click="run('install')">安装外挂</button>
          <button class="btn btn-secondary" type="button" :disabled="running" @click="run('start')">启动外挂</button>
          <button class="btn btn-secondary" type="button" :disabled="running" @click="run('restart')">重启外挂</button>
          <button class="btn btn-secondary" type="button" :disabled="running" @click="run('stop')">停止外挂</button>
          <button class="btn btn-secondary" type="button" @click="openWeb">打开 Electerm Web</button>
        </div>
      </BasePanel>
      <LogDrawer v-model:expanded="expanded" :lines="lines" od-id="remote-log" @clear="clear" />
    </div>
  </ToolPageLayout>
</template>
