<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { getRuntimeConfig } from '@/config/runtime'
import { approveNode, backupNodeRed, cancelNode, controlNodeRed, getNodeRedStatus, getSerialLeases, importNodeRed, installNode, nodeRedUrl, removeNodeRedRuntime, restoreNodeRed, trustedNodeRedUrl, type NodeInspection, type NodeRedAction, type NodeRedStatus, type SerialLease } from '@/api/nodered/client'

const store = useAppStore()
const { lines, append, clear, connect } = useJobStream(['Node-RED 管理页面已就绪'])
const busy = ref(false)
const expanded = ref(true)
const status = ref<NodeRedStatus | null>(null)
const inspection = ref<NodeInspection | null>(null)
const leases = ref<SerialLease[]>([])
const reviewer = ref('')
const reason = ref('')
const backupId = ref('')
const labels = { NOT_INSTALLED: '未安装', STOPPED: '已停止', STARTING: '启动中', RUNNING: '运行中' }
const stateLabel = computed(() => busy.value ? '处理中' : status.value ? labels[status.value.state] : '未读取状态')
const configured = () => Boolean(getRuntimeConfig().javaApiBase && getRuntimeConfig().sessionToken)
watch(() => lines.value.length, length => { if (length > 500) lines.value.splice(0, length - 500) })

async function execute(action: () => Promise<void>) {
  if (busy.value) return
  if (!configured()) { append('Java 服务未配置，请通过 ToolHelper 启动器打开'); return }
  busy.value = true
  try { await action(); store.setStatus('Node-RED 操作完成', 'success') }
  catch (error) {
    const message = error instanceof Error ? error.message : 'Node-RED 操作失败'
    append(message); store.setStatus(message, 'error')
  } finally { busy.value = false }
}
async function refresh() {
  status.value = await getNodeRedStatus()
  leases.value = await getSerialLeases()
}
function run(action: NodeRedAction) {
  if ((action === 'install' || action === 'updates') && !window.confirm('确认下载并安装 ToolHelper 签名的固定版本运行包？变更前将停止流程并备份数据。')) return
  void execute(async () => { status.value = await controlNodeRed(action); append(`当前状态：${labels[status.value.state]}`) })
}
function openBrowser() {
  if (status.value?.state !== 'RUNNING' || !status.value.url) return
  void execute(async () => {
    window.open(trustedNodeRedUrl(status.value!.url!, status.value!.port), '_blank', 'noopener,noreferrer')
  })
}
function startAndOpen() {
  if (busy.value || !configured()) return
  // 在点击事件内打开窗口，避免异步启动后被浏览器拦截。
  const page = window.open('about:blank', '_blank')
  if (page) page.opener = null
  void execute(async () => {
    try {
      status.value = await controlNodeRed('start')
      if (!status.value.url) throw new Error('运行时未返回就绪地址')
      const url = trustedNodeRedUrl(status.value.url, status.value.port)
      if (page) page.location.replace(url)
      else append('浏览器阻止了新窗口，请点击“打开编辑器”')
    } catch (error) { page?.close(); throw error }
  })
}
function chooseFile(event: Event, nodes: boolean) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]; input.value = ''
  if (!file || !window.confirm(nodes ? '确认导入签名节点包进行审阅？此步骤不执行安装脚本。' : '确认导入签名离线运行包？变更前将停止流程并备份。')) return
  void execute(async () => {
    if (nodes) {
      if (inspection.value) await cancelNode(inspection.value.id)
      inspection.value = await importNodeRed(file, true) as NodeInspection
      reviewer.value = ''; reason.value = ''
    } else status.value = await importNodeRed(file) as NodeRedStatus
  })
}
function approve() {
  void execute(async () => { if (inspection.value) inspection.value = await approveNode(inspection.value.id, reviewer.value, reason.value) })
}
function installReviewed() {
  if (!inspection.value?.approved || !window.confirm('已阅读脚本、原生依赖、权限、网络及回滚影响。确认停止流程、备份并执行节点安装？脚本在当前用户权限下运行，外部副作用需人工回退。')) return
  void execute(async () => { status.value = await installNode(inspection.value!.id); inspection.value = null })
}
function cancelReview() {
  void execute(async () => { if (inspection.value) await cancelNode(inspection.value.id); inspection.value = null })
}
function remove() {
  if (!window.confirm('确认停止所有流程并卸载运行时？流程数据、密钥和备份会保留。')) return
  void execute(async () => { status.value = await removeNodeRedRuntime() })
}
function backup() {
  void execute(async () => { backupId.value = await backupNodeRed(); append(`备份完成：${backupId.value}`); await refresh() })
}
function restore() {
  if (!backupId.value.trim() || !window.confirm('确认停止流程并恢复指定备份？仅支持同一 Windows 用户的 DPAPI 密钥。')) return
  void execute(async () => { status.value = await restoreNodeRed(backupId.value.trim()) })
}
onMounted(() => {
  if (!configured()) return
  void execute(refresh)
  void connect(nodeRedUrl('events'), { reconnect: true }).catch(error => append(error instanceof Error ? error.message : '日志连接失败'))
})
</script>

<template>
  <ToolPageLayout tool-id="nodered">
    <div class="tool-screen with-log" data-od-id="nodered-screen">
      <BasePanel title="Node-RED 运行状态">
        <template #actions><span class="badge" :class="status?.state === 'RUNNING' && !busy ? '' : 'warn'">{{ stateLabel }}</span></template>
        <p class="muted">Java 管理独立运行时，系统浏览器打开原生编辑器。ToolHelper 退出会停止全部流程。首次登录账号为 toolhelper，密码见本机受保护的 private/admin.properties。</p>
        <p v-if="status?.version">Node-RED {{ status.version }} · Node.js {{ status.nodeVersion }} · 127.0.0.1:{{ status.port }} <span v-if="status.pid">· PID {{ status.pid }}</span></p>
        <p v-if="status?.diagnostic" role="status">{{ status.diagnostic }}</p>
        <div class="button-row top-gap">
          <button class="btn btn-primary" :disabled="busy" @click="run('install')">受控安装</button>
          <label class="btn btn-secondary">导入离线包<input type="file" accept=".zip" :disabled="busy" hidden @change="chooseFile($event, false)" /></label>
          <button class="btn btn-secondary" :disabled="busy || !status?.version" @click="startAndOpen">启动并打开</button>
          <button class="btn btn-secondary" :disabled="busy || !status?.pid" @click="run('stop')">停止</button>
          <button class="btn btn-secondary" :disabled="busy || !status?.version" @click="run('restart')">重启</button>
          <button class="btn btn-secondary" :disabled="busy || !status?.version" @click="run('updates')">检查并安装更新</button>
          <button class="btn btn-secondary" :disabled="busy || status?.state !== 'RUNNING'" @click="openBrowser">打开编辑器</button>
          <button class="btn btn-ghost" :disabled="busy" @click="execute(refresh)">刷新状态与串口</button>
          <button class="btn btn-ghost" :disabled="busy || !status?.version" @click="remove">卸载运行时</button>
        </div>
      </BasePanel>
      <BasePanel title="节点审阅与审批">
        <p class="muted">仅接收已签名、包含完整预构建依赖树和审阅材料的节点包；编辑器内禁止联网安装。</p>
        <label class="btn btn-secondary">导入待审节点包<input type="file" accept=".zip" :disabled="busy" hidden @change="chooseFile($event, true)" /></label>
        <div v-if="inspection" class="top-gap">
          <p>SHA-256：<code>{{ inspection.sha256 }}</code> · {{ inspection.allowlisted ? '已命中配置白名单' : inspection.approved ? '已人工审批' : '待人工审批' }} · 有效期 {{ inspection.expiresAt }}</p>
          <ul><li v-for="pkg in inspection.review.packages" :key="pkg.path">{{ pkg.name }}@{{ pkg.version }} · {{ pkg.path }} · {{ pkg.license }} · 维护 {{ pkg.maintenance }} · 已知漏洞 {{ pkg.vulnerabilities }} · package.json SHA-256 {{ pkg.sha256 }}<pre v-if="Object.keys(pkg.installScripts).length" class="review-source">已披露但不自动执行的 npm 生命周期脚本：{{ JSON.stringify(pkg.installScripts, null, 2) }}</pre></li></ul>
          <p>原生依赖：{{ inspection.review.nativeDependencies.join('、') || '无' }}</p>
          <p>权限：{{ inspection.review.permissions.join('、') || '无额外权限声明' }}</p>
          <p>网络访问：{{ inspection.review.networkAccess.join('、') || '无' }}</p>
          <p>回滚影响：{{ inspection.review.rollbackImpact }}</p>
          <details v-for="script in inspection.review.scripts" :key="script.path" open><summary>{{ script.path }}</summary><pre class="review-source">{{ script.source }}</pre></details>
          <div v-if="!inspection.approved" class="button-row top-gap">
            <label>审批人 <input v-model="reviewer" maxlength="80" :disabled="busy" /></label>
            <label>审批理由 <input v-model="reason" maxlength="1000" :disabled="busy" /></label>
            <button class="btn btn-secondary" :disabled="busy || !reviewer.trim() || !reason.trim()" @click="approve">人工审批</button>
          </div>
          <div class="button-row top-gap"><button class="btn btn-primary" :disabled="busy || !inspection.approved" @click="installReviewed">确认执行安装</button><button class="btn btn-ghost" :disabled="busy" @click="cancelReview">取消审阅</button></div>
        </div>
      </BasePanel>
      <BasePanel title="数据备份与串口协调">
        <div class="button-row"><button class="btn btn-secondary" :disabled="busy || !status?.version" @click="backup">立即备份</button><label>备份文件名 <input v-model="backupId" :disabled="busy" placeholder="migration-…zip" /></label><button class="btn btn-secondary" :disabled="busy || !backupId.trim()" @click="restore">确认恢复</button></div>
        <p class="muted">自动备份保留 7 份，升级前备份保留 2 份。凭据密钥绑定当前 Windows 用户。</p>
        <p v-if="!leases.length" class="muted">当前无串口占用登记。</p>
        <ul v-else><li v-for="lease in leases" :key="lease.id">{{ lease.port }} · {{ lease.owner }} · PID {{ lease.pid }} · {{ lease.releaseHint }}</li></ul>
      </BasePanel>
      <LogDrawer v-model:expanded="expanded" :lines="lines" od-id="nodered-log" @clear="clear" />
    </div>
  </ToolPageLayout>
</template>

<style scoped>
.review-source { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 24rem; overflow: auto; }
code { overflow-wrap: anywhere; }
</style>
