<script setup lang="ts">
import { computed } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { useMockTask } from '@/composables/useMockTask'
import { kylinService } from '@/mocks/services/tool-services'

const store = useAppStore()
const { lines, append, clear } = useJobStream(['KylinOS 运维策略页面已就绪'])
const { state, execute } = useMockTask(kylinService)
const running = computed(() => state.value.status === 'loading')

async function run(action: string) {
  append(action)
  const result = await execute({ action })
  if (!result) return
  append(result.message)
  store.setStatus(result.message, 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="kylin">
    <div class="tool-screen with-log" data-od-id="kylin-screen">
      <BasePanel title="KylinOS 运维策略">
        <template #actions><span class="badge" :class="running ? 'warn' : ''">{{ running ? '处理中' : '演示模式' }}</span></template>
        <p class="muted">激活、服务部署、漏洞扫描和优化策略均通过受控 service 执行。</p>
        <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="running" @click="run('刷新激活状态')">刷新激活状态</button><button class="btn btn-secondary" type="button" :disabled="running" @click="run('部署服务')">部署服务</button><button class="btn btn-secondary" type="button" :disabled="running" @click="run('扫描漏洞')">扫描漏洞</button><button class="btn btn-secondary" type="button" :disabled="running" @click="run('应用优化')">应用优化</button></div>
      </BasePanel>
      <LogDrawer :lines="lines" od-id="kylin-log" @clear="clear" />
    </div>
  </ToolPageLayout>
</template>
