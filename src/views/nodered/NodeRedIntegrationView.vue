<script setup lang="ts">
import { computed } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { useMockTask } from '@/composables/useMockTask'
import { nodeRedService } from '@/mocks/services/tool-services'

const store = useAppStore()
const { lines, append, clear } = useJobStream(['Node-RED 便携运行时页面已就绪'])
const { state, execute } = useMockTask(nodeRedService)
const running = computed(() => state.value.status === 'loading')

async function run(action: string) {
  append(action)
  const result = await execute({ action })
  if (!result) return
  append(result.message)
  store.setStatus(result.message, 'success')
}

function openBrowser() {
  window.open('http://localhost:1880', '_blank', 'noopener,noreferrer')
  append('已请求系统浏览器打开 Node-RED')
  store.setStatus('已打开系统浏览器', 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="nodered">
    <div class="tool-screen with-log" data-od-id="nodered-screen">
      <BasePanel title="Node-RED 便携运行时">
        <template #actions><span class="badge" :class="running ? 'warn' : ''">{{ running ? '处理中' : '演示模式' }}</span></template>
        <p class="muted">运行时下载、状态和节点治理由 service 管理；画布通过系统浏览器打开。</p>
        <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="running" @click="run('启动 Node-RED')">启动运行时</button><button class="btn btn-secondary" type="button" :disabled="running" @click="run('停止 Node-RED')">停止</button><button class="btn btn-secondary" type="button" :disabled="running" @click="run('更新 Node-RED')">检查更新</button><button class="btn btn-secondary" type="button" :disabled="running" @click="run('下载 Node-RED')">受控下载</button><button class="btn btn-secondary" type="button" @click="openBrowser">在系统浏览器打开</button></div>
      </BasePanel>
      <BasePanel title="节点治理"><div class="metric-grid"><div class="metric"><span>运行状态</span><strong>未启动</strong></div><div class="metric"><span>节点包</span><strong>0</strong></div><div class="metric"><span>待更新</span><strong>0</strong></div><div class="metric"><span>日志</span><strong>正常</strong></div></div></BasePanel>
      <LogDrawer :lines="lines" od-id="nodered-log" @clear="clear" />
    </div>
  </ToolPageLayout>
</template>
