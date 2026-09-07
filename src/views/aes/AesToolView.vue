<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import { useAppStore } from '@/stores/app'
import { ApiError } from '@/api/contracts'
import { decryptAes, encryptAes, type AesMode, type AesPadding, type AesRequest } from '@/api/aes/client'

const store = useAppStore()
const modes: AesMode[] = ['CBC', 'ECB', 'OFB', 'CFB', 'CTS', 'CTR', 'GCM']
const paddings: AesPadding[] = ['PKCS7', 'NONE', 'ZEROS', 'ANSIX923', 'ISO10126']
const charsets: AesRequest['charset'][] = ['UTF-8', 'UTF-16LE', 'UTF-32LE', 'ASCII', 'GBK', 'ISO-8859-1']
const formats: AesRequest['inputFormat'][] = ['TEXT', 'HEX', 'BASE64']
const outputFormats: AesRequest['outputFormat'][] = ['HEX', 'BASE64']
const mode = ref<AesMode>('CBC')
const padding = ref<AesPadding>('PKCS7')
const keySizeBits = ref<AesRequest['keySizeBits']>(256)
const charset = ref<AesRequest['charset']>('UTF-8')
const keyFormat = ref<AesRequest['keyFormat']>('TEXT')
const ivFormat = ref<AesRequest['ivFormat']>('TEXT')
const inputFormat = ref<AesRequest['inputFormat']>('TEXT')
const outputFormat = ref<AesRequest['outputFormat']>('BASE64')
const key = ref('')
const iv = ref('')
const input = ref('')
const output = ref('')
const loading = ref(false)
const needsIv = computed(() => mode.value !== 'ECB')

function validate(action: 'encrypt' | 'decrypt') {
  if (!key.value) return '请输入密钥'
  if (needsIv.value && !iv.value) return '请输入 IV'
  if (input.value.length === 0 && action === 'decrypt') return '请输入待解密内容'
  if ((mode.value === 'GCM' || mode.value === 'CTR' || mode.value === 'CTS') && padding.value !== 'NONE') return `${mode.value} 仅支持 NONE 填充`
  return ''
}

async function run(action: 'encrypt' | 'decrypt') {
  const error = validate(action)
  if (error) { store.setStatus(error, 'error'); return }
  loading.value = true
  const payload: AesRequest = { compatibilityProfile: 'TOOLHELPER_V3', mode: mode.value, padding: padding.value, keySizeBits: keySizeBits.value, charset: charset.value, keyFormat: keyFormat.value, ivFormat: ivFormat.value, inputFormat: inputFormat.value, outputFormat: outputFormat.value, key: key.value, iv: iv.value, input: input.value }
  try {
    const result = action === 'encrypt' ? await encryptAes(payload) : await decryptAes(payload)
    output.value = result.output
    store.setStatus(`AES ${action === 'encrypt' ? '加密' : '解密'}成功`, 'success')
  } catch (error) {
    store.setStatus(error instanceof ApiError ? error.message : 'AES 操作失败', 'error')
  } finally { loading.value = false }
}

function clearOutput() { output.value = '' }
function clearAll() { key.value = ''; iv.value = ''; input.value = ''; output.value = '' }
onBeforeUnmount(clearAll)
</script>

<template>
  <ToolPageLayout tool-id="aes">
    <div class="tool-screen" data-od-id="aes-screen">
      <BasePanel title="AES 参数">
        <div class="form-grid">
          <label class="field"><span>运算模式</span><select v-model="mode" class="input"><option v-for="item in modes" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>填充方式</span><select v-model="padding" class="input"><option v-for="item in paddings" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>密钥长度</span><select v-model.number="keySizeBits" class="input"><option :value="128">128 位</option><option :value="192">192 位</option><option :value="256">256 位</option></select></label>
          <label class="field"><span>字符编码</span><select v-model="charset" class="input"><option v-for="item in charsets" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>输入格式</span><select v-model="inputFormat" class="input"><option v-for="item in formats" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>输出格式</span><select v-model="outputFormat" class="input"><option v-for="item in outputFormats" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>密钥格式</span><select v-model="keyFormat" class="input"><option v-for="item in formats" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>IV 格式</span><select v-model="ivFormat" class="input" :disabled="!needsIv"><option v-for="item in formats" :key="item">{{ item }}</option></select></label>
          <label class="field"><span>密钥</span><input v-model="key" class="input" type="password" autocomplete="off"></label>
          <label class="field"><span>偏移量 IV</span><input v-model="iv" class="input" type="password" autocomplete="off" :disabled="!needsIv"></label>
        </div>
      </BasePanel>
      <div class="equal-columns equal-height top-gap">
        <BasePanel title="输入"><textarea v-model="input" class="input textarea fill" aria-label="AES 输入"></textarea><div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="loading" @click="run('encrypt')">加密</button><button class="btn btn-secondary" type="button" :disabled="loading" @click="run('decrypt')">解密</button><button class="btn btn-secondary" type="button" @click="clearAll">清空</button></div></BasePanel>
        <BasePanel title="输出结果"><template #actions><button class="btn btn-ghost" type="button" @click="clearOutput">清空</button></template><textarea v-model="output" class="codebox fill" aria-label="AES 输出" readonly></textarea></BasePanel>
      </div>
    </div>
  </ToolPageLayout>
</template>
