<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useMockTask } from '@/composables/useMockTask'
import { modbusService } from '@/mocks/services/tool-services'

const store = useAppStore()
const rawExpanded = ref(false)
const { state, execute } = useMockTask(modbusService)

async function run(action: string) {
  const result = await execute({ action })
  if (result) store.setStatus(result.message, 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="modbus">
    <div class="tool-screen" data-od-id="modbus-screen">
      <div class="equal-columns"><BasePanel title="串口与协议" description="支持申弘版与南瑞怡和版 Modbus RTU。"><div class="form-grid two"><label class="field"><span>协议</span><select class="input" aria-label="Modbus 协议"><option>申弘版</option><option>南瑞怡和版</option></select></label><label class="field"><span>串口号</span><select class="input" aria-label="Modbus 串口号"><option>COM3</option><option>COM5</option></select></label><label class="field"><span>波特率</span><select class="input" aria-label="Modbus 波特率"><option>9600</option><option>115200</option></select></label><label class="field"><span>设备地址</span><input class="input" value="1" aria-label="设备地址"></label></div><div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="state.status === 'loading'" @click="run('打开串口')">打开串口</button><button class="btn btn-secondary" type="button" :disabled="state.status === 'loading'" @click="run('扫描设备')">扫描设备</button><button class="btn btn-secondary" type="button" :disabled="state.status === 'loading'" @click="run('读取当前设备')">读取当前设备</button></div></BasePanel><BasePanel title="设备列表"><table><thead><tr><th>地址</th><th>序列号</th><th>在线</th></tr></thead><tbody><tr><td colspan="3" class="muted">打开串口后扫描站端下辖设备</td></tr></tbody></table></BasePanel></div>
      <BasePanel title="解析结果" class="top-gap"><pre class="result-box">（未选中设备）</pre></BasePanel>
      <section class="detail-drawer" data-od-id="modbus-raw-log"><header class="drawer-head"><strong>原始报文</strong><div class="button-row"><button class="btn btn-ghost" type="button" @click="rawExpanded = false">清空</button><button class="btn btn-ghost" type="button" @click="rawExpanded = !rawExpanded">{{ rawExpanded ? '收起报文' : '展开报文' }}</button></div></header><div v-show="rawExpanded" class="drawer-body"><pre class="terminal-box">等待 Modbus RTU 通信...</pre></div></section>
    </div>
  </ToolPageLayout>
</template>
