<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useMockTask } from '@/composables/useMockTask'
import { druidService } from '@/mocks/services/tool-services'

const store = useAppStore()
const target = ref('http://localhost:8080')
const result = ref('尚未执行规则扫描')
const { state, execute } = useMockTask(druidService)

async function scan() {
  const response = await execute({ target: target.value })
  if (!response) return
  result.value = `${response.message}\n${response.details}`
  store.setStatus(response.message, 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="druid">
    <div class="tool-screen" data-od-id="druid-screen">
      <BasePanel title="Druid 漏洞检测">
        <label class="field"><span>目标地址</span><input v-model="target" class="input" aria-label="Druid 目标地址"></label>
        <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="state.status === 'loading'" @click="scan">开始扫描</button><button class="btn btn-secondary" type="button" @click="result = '规则版本：演示规则集 1.0'">更新规则</button></div>
      </BasePanel>
      <BasePanel title="风险结果" class="top-gap"><pre class="result-box">{{ result }}</pre></BasePanel>
    </div>
  </ToolPageLayout>
</template>
