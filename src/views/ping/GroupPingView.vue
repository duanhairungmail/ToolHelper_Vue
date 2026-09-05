<script setup lang="ts">
import { computed, ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream, type JobEvent } from '@/composables/useJobStream'
import { cancelPingJob, downloadPing, startPingJob, type PingResult, type PingSummary } from '@/api/ping/client'
import { getRuntimeConfig, joinApiUrl } from '@/config/runtime'

const store = useAppStore()
const target = ref('')
const concurrency = ref(50)
const count = ref(4)
const timeoutMs = ref(1000)
const jobId = ref('')
const results = ref<PingResult[]>([])
const summary = ref<PingSummary>({ targetTotal: 0, online: 0, offline: 0, partialLoss: 0, averageDelayMs: null })
const { lines, clear, connect } = useJobStream(['群 Ping 代理已就绪'])
const running = ref(false)
const hasResults = computed(() => results.value.length > 0)

async function scan() {
  if (!target.value.trim()) return store.setStatus('请输入检测目标', 'error')
  try {
    results.value = []
    summary.value = { targetTotal: 0, online: 0, offline: 0, partialLoss: 0, averageDelayMs: null }
    const started = await startPingJob(target.value, { concurrency: concurrency.value, count: count.value, timeoutMs: timeoutMs.value })
    jobId.value = started.jobId
    running.value = true
    summary.value.targetTotal = started.targetTotal
    store.setStatus(`已展开 ${started.targetTotal} 个目标，正在检测`, 'success')
    void connect(joinApiUrl(getRuntimeConfig().localApiBase, `/api/local/ping/jobs/${started.jobId}/events`), { reconnect: false, onEvent: handleEvent })
  } catch (error) { store.setStatus(error instanceof Error ? error.message : '群 Ping 启动失败', 'error') }
}

function handleEvent(event: JobEvent) {
  const data = event.payload.data as PingResult | PingSummary | undefined
  if (event.type === 'ping.result' && data && 'address' in data) results.value.push(data)
  if (event.type === 'ping.summary' && data && 'targetTotal' in data) { summary.value = data; running.value = false; store.setStatus('群 Ping 已完成', 'success') }
}

async function stop() { if (jobId.value) { await cancelPingJob(jobId.value); running.value = false; store.setStatus('已请求停止，保留已完成结果', 'idle') } }
function clearAll() { target.value = ''; results.value = []; summary.value = { targetTotal: 0, online: 0, offline: 0, partialLoss: 0, averageDelayMs: null }; clear() }
async function exportFile(format: 'csv' | 'xlsx') { if (!jobId.value) return; const blob = await downloadPing(jobId.value, format); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `toolhelper-ping-${jobId.value}.${format}`; link.click(); URL.revokeObjectURL(url) }
</script>

<template>
  <ToolPageLayout tool-id="ping">
    <div class="tool-screen">
      <div class="equal-columns ping-grid">
        <BasePanel title="目标与检测" description="支持 CIDR 网段、末段范围和文本目标列表。">
          <label class="field"><span>网段或范围</span><textarea v-model="target" class="input textarea" aria-label="Ping 目标" placeholder="192.168.1.0/24&#10;192.168.1.10"></textarea></label>
          <div class="form-grid top-gap"><label class="field"><span>并发</span><input v-model.number="concurrency" class="input" type="number" min="1" max="200"></label><label class="field"><span>次数</span><input v-model.number="count" class="input" type="number" min="1" max="10"></label><label class="field"><span>超时(ms)</span><input v-model.number="timeoutMs" class="input" type="number" min="100" max="10000"></label></div>
          <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="running" @click="scan">开始</button><button class="btn btn-secondary" type="button" :disabled="!running" @click="stop">停止</button><button class="btn btn-secondary" type="button" @click="clearAll">清空</button></div>
        </BasePanel>
        <div class="metric-grid"><div class="metric"><span>目标总数</span><strong>{{ summary.targetTotal }}</strong></div><div class="metric"><span>在线</span><strong>{{ summary.online }}</strong></div><div class="metric"><span>离线</span><strong>{{ summary.offline }}</strong></div><div class="metric"><span>平均延迟</span><strong>{{ summary.averageDelayMs == null ? '-' : `${summary.averageDelayMs} ms` }}</strong></div></div>
      </div>
      <BasePanel title="检测结果" class="top-gap" :description="jobId ? `任务 ${jobId}` : undefined">
        <template #actions><button class="btn btn-secondary" :disabled="!hasResults" @click="exportFile('csv')">CSV</button><button class="btn btn-secondary" :disabled="!hasResults" @click="exportFile('xlsx')">Excel</button></template>
        <table><thead><tr><th>目标</th><th>状态</th><th>平均延迟</th><th>丢包率</th></tr></thead><tbody><tr v-for="item in results" :key="`${item.address}-${item.completionIndex}`"><td>{{ item.address }}</td><td>{{ item.status === 'Online' ? '在线' : item.status === 'PartialLoss' ? '部分丢包' : '超时/失败' }}</td><td>{{ item.averageDelayMs == null ? '-' : `${item.averageDelayMs} ms` }}</td><td>{{ item.packetLossPercent }}%</td></tr><tr v-if="!results.length"><td colspan="4" class="muted">请先开始检测</td></tr></tbody></table>
      </BasePanel>
      <BasePanel title="运行日志" class="top-gap"><pre class="terminal-box">{{ lines.join('\n') }}</pre></BasePanel>
    </div>
  </ToolPageLayout>
</template>
