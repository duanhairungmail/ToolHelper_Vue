<script setup lang="ts">
import { computed, ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { useMockTask } from '@/composables/useMockTask'
import { remoteService } from '@/mocks/services/tool-services'
import type { JavaLifecycleRequest } from '@/api/java/client'

const store = useAppStore()
const { lines, append, clear } = useJobStream(['远程外挂连接页面已就绪'])
const { state, execute } = useMockTask(remoteService)
const running = computed(() => state.value.status === 'loading')
const expanded = ref(true)

async function run(action: JavaLifecycleRequest['action']) {
  append(`请求远程外挂${action}`)
  const result = await execute({ action })
  if (!result) return
  append(result.message)
  store.setStatus(result.message, 'success')
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
        <template #actions><span class="badge" :class="running ? 'warn' : ''">{{ running ? '处理中' : '演示模式' }}</span></template>
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
