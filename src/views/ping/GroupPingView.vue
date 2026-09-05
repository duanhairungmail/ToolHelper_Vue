<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useMockTask } from '@/composables/useMockTask'
import { pingService } from '@/mocks/services/tool-services'

const store = useAppStore()
const target = ref('')
const total = ref(0)
const { state, execute } = useMockTask(pingService)

async function scan() {
  if (!target.value.trim()) {
    store.setStatus('请输入检测目标', 'error')
    return
  }
  const result = await execute({ target: target.value })
  if (!result) return
  total.value = target.value.endsWith('/24') ? 254 : 1
  store.setStatus(`已载入 ${total.value} 个目标（演示模式）`, 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="ping">
    <div class="tool-screen" data-od-id="ping-screen">
      <div class="equal-columns ping-grid"><BasePanel title="目标与检测" description="支持 CIDR 网段、末段范围和文本目标列表。"><label class="field"><span>网段或范围</span><input v-model="target" class="input" aria-label="Ping 目标" placeholder="192.168.1.0/24"></label><div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="state.status === 'loading'" @click="scan">扫描网段</button><button class="btn btn-secondary" type="button" @click="target = ''">清空</button><button class="btn btn-secondary" type="button" @click="store.setStatus('导入目标功能将在本地 service 接入后启用', 'idle')">导入文件</button></div></BasePanel><div class="metric-grid"><div class="metric"><span>目标总数</span><strong>{{ total }}</strong></div><div class="metric"><span>在线</span><strong>0</strong></div><div class="metric"><span>离线</span><strong>0</strong></div><div class="metric"><span>平均延迟</span><strong>0 ms</strong></div></div></div>
      <BasePanel title="检测结果" class="top-gap"><table><thead><tr><th>目标</th><th>状态</th><th>平均延迟</th><th>丢包率</th></tr></thead><tbody><tr><td colspan="4" class="muted">请先扫描网段或导入文件</td></tr></tbody></table></BasePanel>
    </div>
  </ToolPageLayout>
</template>
