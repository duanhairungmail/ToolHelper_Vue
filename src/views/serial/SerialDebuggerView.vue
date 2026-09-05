<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { useMockTask } from '@/composables/useMockTask'
import { serialService } from '@/mocks/services/tool-services'

const store = useAppStore()
const connected = ref(false)
const serialText = ref('')
const { lines, append, clear } = useJobStream(['串口调试器已就绪'])
const { state, execute } = useMockTask(serialService)

async function toggleConnection() {
  const action = connected.value ? '关闭串口' : '打开串口'
  const result = await execute({ action })
  if (!result) return
  connected.value = !connected.value
  append(result.message)
  store.setStatus(result.message, 'success')
}

function send() {
  if (!connected.value) {
    store.setStatus('请先打开串口', 'error')
    return
  }
  if (!serialText.value.trim()) {
    store.setStatus('请输入要发送的数据', 'error')
    return
  }
  append(`[发送] ${serialText.value}`)
  store.setStatus('发送完成（演示模式）', 'success')
  serialText.value = ''
}
</script>

<template>
  <ToolPageLayout tool-id="serial">
    <div class="tool-screen with-log" data-od-id="serial-screen">
      <div class="equal-columns serial-grid">
        <div class="stack">
          <BasePanel title="连接设置">
            <div class="form-grid two"><label class="field"><span>串口号</span><select class="input" aria-label="串口号"><option>COM3</option><option>COM5</option></select></label><label class="field"><span>波特率</span><select class="input" aria-label="波特率"><option>9600</option><option>115200</option></select></label><label class="field"><span>数据位</span><select class="input" aria-label="数据位"><option>8</option><option>7</option></select></label><label class="field"><span>校验位</span><select class="input" aria-label="校验位"><option>无</option><option>偶校验</option></select></label></div>
            <button class="btn btn-primary top-gap" type="button" :disabled="state.status === 'loading'" @click="toggleConnection">{{ connected ? '关闭串口' : '打开串口' }}</button>
          </BasePanel>
          <BasePanel title="发送数据"><textarea v-model="serialText" class="input textarea" aria-label="串口发送数据" placeholder="输入文本或十六进制数据"></textarea><button class="btn btn-primary top-gap" type="button" @click="send">发送</button></BasePanel>
        </div>
        <BasePanel title="接收日志"><template #actions><button class="btn btn-ghost" type="button" @click="clear">清空日志</button></template><pre class="terminal-box fill">{{ lines.length ? lines.join('\n') : '等待串口数据' }}</pre></BasePanel>
      </div>
    </div>
  </ToolPageLayout>
</template>
