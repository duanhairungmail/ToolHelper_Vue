<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useMockTask } from '@/composables/useMockTask'
import { macService } from '@/mocks/services/tool-services'

const store = useAppStore()
const subnet = ref('192.168.1.0/24')
const result = ref('暂无设备记录')
const { state, execute } = useMockTask(macService)

async function scan() {
  const response = await execute({ subnet: subnet.value })
  if (!response) return
  result.value = `${response.message}\n${response.details}`
  store.setStatus(response.message, 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="mac">
    <div class="tool-screen" data-od-id="mac-screen">
      <BasePanel title="获取设备 MAC 地址">
        <label class="field"><span>网段</span><input v-model="subnet" class="input" aria-label="扫描网段"></label>
        <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="state.status === 'loading'" @click="scan">扫描设备</button><button class="btn btn-secondary" type="button" @click="result = '已清空设备记录'">清空</button></div>
      </BasePanel>
      <BasePanel title="设备台账" class="top-gap"><table><thead><tr><th>IP 地址</th><th>MAC 地址</th><th>状态</th></tr></thead><tbody><tr><td colspan="3" class="muted"><pre class="result-box">{{ result }}</pre></td></tr></tbody></table></BasePanel>
    </div>
  </ToolPageLayout>
</template>
