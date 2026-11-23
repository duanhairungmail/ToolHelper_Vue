<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { getRuntimeConfig } from '@/config/runtime'
import { controlElecterm, getElectermLogs, getElectermStatus, importElecterm, trustedElectermUrl, type ElectermAction, type ElectermStatus } from '@/api/electerm/client'

const store = useAppStore()
const { lines, append, clear } = useJobStream(['Electerm Web 管理页面已就绪'])
const running = ref(false)
const status = ref<ElectermStatus | null>(null)
const expanded = ref(true)
const offlineInput = ref<HTMLInputElement | null>(null)
const labels = { NOT_INSTALLED: '未安装', STOPPED: '已停止', STARTING: '启动中', RUNNING: '运行中' }
const stateLabel = computed(() => running.value ? '处理中' : status.value ? labels[status.value.state] : '未读取状态')
const configured = () => Boolean(getRuntimeConfig().javaApiBase && getRuntimeConfig().sessionToken)

async function run(action: ElectermAction | 'status' | 'logs', file?: File) {
  if (running.value) return
  if (!configured()) {
    append('运行时未配置，无法连接 Java 服务')
    store.setStatus('Java 服务未配置', 'error')
    return
  }

  running.value = true
  append(`请求 Electerm ${file ? '离线安装' : action}`)
  try {
    if (action !== 'logs') status.value = action === 'status' ? await getElectermStatus() : file ? await importElecterm(file) : await controlElecterm(action)
    const logs = await getElectermLogs()
    clear()
    logs.forEach(line => append(line))
    if (status.value) append(`当前状态：${labels[status.value.state]}${status.value.version ? `，版本 ${status.value.version}` : ''}`)
    store.setStatus('Electerm 操作完成', 'success')
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Java API 请求失败'
    append(message)
    store.setStatus(message, 'error')
  } finally {
    running.value = false
  }
}

function openWeb() {
  if (!status.value?.url || status.value.state !== 'RUNNING') return
  try {
    const url = trustedElectermUrl(status.value.url, status.value.port)
    window.open(url, '_blank', 'noopener,noreferrer')
    append('已请求系统浏览器打开本机 Electerm Web')
  } catch (error) { append(error instanceof Error ? error.message : '地址校验失败') }
}
function chooseOffline(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ''
  if (file) void run('install', file)
}
onMounted(() => { if (configured()) void run('status') })
</script>

<template>
  <ToolPageLayout tool-id="remote">
    <div class="tool-screen with-log" data-od-id="remote-screen">
      <BasePanel title="Electerm Web 运行状态">
        <template #actions><span class="badge" :class="status?.state === 'RUNNING' && !running ? '' : 'warn'">{{ stateLabel }}</span></template>
        <p class="muted">仅管理 Electerm Web 外挂生命周期，不在此页面收集 SSH、RDP、VNC 凭据。</p>
        <p v-if="status?.version" class="muted">版本 {{ status.version }} · 内置 Node.js {{ status.nodeVersion }} · 回环端口 {{ status.port }}<span v-if="status.pid"> · PID {{ status.pid }}</span></p>
        <p v-if="status?.diagnostic" role="status">{{ status.diagnostic }}</p>
        <div class="button-row top-gap">
          <button class="btn btn-primary" type="button" :disabled="running" @click="run('install')">{{ status?.version ? '检查并安装更新' : '安装外挂' }}</button>
          <button class="btn btn-secondary" type="button" :disabled="running" @click="offlineInput?.click()">导入离线安装包</button>
          <button class="btn btn-secondary" type="button" :disabled="running || !status?.version || status.state === 'RUNNING'" @click="run('start')">启动外挂</button>
          <button class="btn btn-secondary" type="button" :disabled="running || !status?.version" @click="run('restart')">重启外挂</button>
          <button class="btn btn-secondary" type="button" :disabled="running || !status?.pid" @click="run('stop')">停止外挂</button>
          <button class="btn btn-secondary" type="button" :disabled="running || status?.state !== 'RUNNING'" @click="openWeb">打开 Electerm Web</button>
          <button class="btn btn-ghost" type="button" :disabled="running" @click="run('status')">刷新状态</button>
          <button class="btn btn-ghost" type="button" :disabled="running" @click="run('logs')">读取日志</button>
        </div>
        <input ref="offlineInput" type="file" accept=".zip" hidden aria-label="导入签名的 Electerm 离线包" @change="chooseOffline" />
      </BasePanel>
      <LogDrawer v-model:expanded="expanded" :lines="lines" od-id="remote-log" @clear="clear" />
    </div>
  </ToolPageLayout>
</template>
