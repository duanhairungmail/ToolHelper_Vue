<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { useMockTask } from '@/composables/useMockTask'
import { aesService } from '@/mocks/services/tool-services'

const modes = ['CBC', 'ECB', 'CTR', 'GCM', 'CFB', 'OFB', 'XTS']
const paddings = ['PKCS7', 'NoPadding', 'ZeroPadding', 'ISO10126', 'ANSIX923']
const store = useAppStore()
const mode = ref(modes[0])
const padding = ref(paddings[0])
const key = ref('')
const iv = ref('')
const input = ref('')
const output = ref('')
const { state, execute } = useMockTask(aesService)

async function run(action: 'encrypt' | 'decrypt') {
  if (!input.value.trim()) {
    store.setStatus('请输入待处理文本', 'error')
    return
  }
  const result = await execute({ action, mode: mode.value, padding: padding.value, key: key.value, iv: iv.value, input: input.value })
  if (!result) return
  output.value = result.output
  store.setStatus(`${result.message}（演示模式）`, 'success')
}

function clearOutput() {
  output.value = ''
}

onBeforeUnmount(() => {
  key.value = ''
  iv.value = ''
  input.value = ''
  output.value = ''
})
</script>

<template>
  <ToolPageLayout tool-id="aes">
    <div class="tool-screen" data-od-id="aes-screen">
      <BasePanel title="AES 参数">
        <template #actions><span class="badge warn">演示模式</span></template>
        <div class="form-grid">
          <label class="field"><span>运算模式</span><select v-model="mode" class="input" aria-label="AES 运算模式"><option v-for="item in modes" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>填充方式</span><select v-model="padding" class="input" aria-label="AES 填充方式"><option v-for="item in paddings" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>密钥</span><input v-model="key" class="input" type="password" autocomplete="off" placeholder="仅保存在当前页面"></label>
          <label class="field"><span>偏移量 IV</span><input v-model="iv" class="input" type="password" autocomplete="off" placeholder="仅保存在当前页面"></label>
        </div>
        <p class="muted top-gap">重构阶段不使用 Base64 冒充 AES；真实加解密由 Java service 接入。</p>
      </BasePanel>
      <div class="equal-columns equal-height top-gap">
        <BasePanel title="输入"><textarea v-model="input" class="input textarea fill" aria-label="AES 输入"></textarea><div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="state.status === 'loading'" @click="run('encrypt')">加密</button><button class="btn btn-secondary" type="button" :disabled="state.status === 'loading'" @click="run('decrypt')">解密</button></div></BasePanel>
        <BasePanel title="输出结果"><template #actions><button class="btn btn-ghost" type="button" @click="clearOutput">清空</button></template><textarea v-model="output" class="codebox fill" aria-label="AES 输出" readonly></textarea></BasePanel>
      </div>
    </div>
  </ToolPageLayout>
</template>
