<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useMockTask } from '@/composables/useMockTask'
import { apiService } from '@/mocks/services/tool-services'
import { required } from '@/utils/validation'

const store = useAppStore()
const method = ref('GET')
const url = ref('http://localhost:8080/api/health')
const body = ref('')
const result = ref('尚未验证请求')
const { state, execute } = useMockTask(apiService)

async function validate() {
  const error = required(url.value, '请求地址')
  if (error) {
    store.setStatus(error, 'error')
    return
  }
  const response = await execute({ method: method.value, url: url.value, body: body.value })
  if (!response) return
  result.value = `${response.message}\n${response.details}`
  store.setStatus(response.message, 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="api">
    <div class="tool-screen" data-od-id="api-screen">
      <BasePanel title="极早期接口验证">
        <div class="form-grid">
          <label class="field"><span>方法</span><select v-model="method" class="input" aria-label="请求方法"><option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option></select></label>
          <label class="field" style="grid-column: span 3"><span>请求地址</span><input v-model="url" class="input" aria-label="请求地址"></label>
        </div>
        <label class="field top-gap"><span>请求体</span><textarea v-model="body" class="input textarea" aria-label="请求体" placeholder="JSON（可选）"></textarea></label>
        <button class="btn btn-primary top-gap" type="button" :disabled="state.status === 'loading'" @click="validate">{{ state.status === 'loading' ? '验证中…' : '验证请求' }}</button>
      </BasePanel>
      <BasePanel title="响应详情" class="top-gap"><pre class="result-box">{{ result }}</pre></BasePanel>
    </div>
  </ToolPageLayout>
</template>
