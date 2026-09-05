<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useMockTask } from '@/composables/useMockTask'
import { cronService } from '@/mocks/services/tool-services'

const store = useAppStore()
const expression = ref('0 0 * * *')
const preview = ref('尚未生成执行时间')
const { state, execute } = useMockTask(cronService)

async function previewCron() {
  const result = await execute({ expression: expression.value })
  if (!result) return
  preview.value = `${result.message}\n${result.details}`
  store.setStatus(result.message, 'success')
}

async function copyExpression() {
  try {
    await navigator.clipboard.writeText(expression.value)
    store.setStatus('Cron 表达式已复制', 'success')
  } catch {
    store.setStatus('复制失败，请检查浏览器权限', 'error')
  }
}
</script>

<template>
  <ToolPageLayout tool-id="cron">
    <div class="tool-screen" data-od-id="cron-screen">
      <BasePanel title="Cron 表达式">
        <label class="field"><span>表达式</span><input v-model="expression" class="input" aria-label="Cron 表达式"></label>
        <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="state.status === 'loading'" @click="previewCron">预览执行时间</button><button class="btn btn-secondary" type="button" @click="copyExpression">复制表达式</button></div>
      </BasePanel>
      <BasePanel title="未来执行时间" class="top-gap"><pre class="result-box">{{ preview }}</pre></BasePanel>
    </div>
  </ToolPageLayout>
</template>
