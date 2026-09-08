<script setup lang="ts">
import { computed, ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { runtimeConfigured } from '@/config/runtime'
import { closeKylinSession, getKylinJob, issueKylinConfirmation, openKylinSession, submitKylinJob, type KylinJob, type KylinSession } from '@/api/kylin/client'
import type { KylinOperation } from '@/api/kylin/types'

const store = useAppStore()
const { lines, append, clear } = useJobStream(['KylinOS 受控运维页面已就绪'])
const form = ref({ host: '', port: 22, username: 'root', password: '', privateKey: '', privateKeyPassphrase: '', sudoPassword: '' })
const session = ref<KylinSession | null>(null); const job = ref<KylinJob | null>(null); const running = ref(false); const expanded = ref(true)
const capabilities: Array<{ label: string; scan: KylinOperation; change?: KylinOperation; impact: string }> = [
  { label: '系统激活', scan: 'ACTIVATION_SCAN', impact: '仅读取激活状态' }, { label: '定时重启 / 自动登录', scan: 'REBOOT_SCAN', change: 'REBOOT_DEPLOY', impact: '修改计划任务和启动项' }, { label: '日志清理', scan: 'LOG_CLEAN_SCAN', change: 'LOG_CLEAN_DEPLOY', impact: '部署白名单清理策略' }, { label: 'x11vnc', scan: 'VNC_SCAN', change: 'VNC_DEPLOY', impact: '上传并管理 X86_64 服务资源' }, { label: 'openGauss / PostgreSQL', scan: 'OPENGAUSS_SCAN', change: 'OPENGAUSS_DEPLOY', impact: '备份并受控重载配置' }, { label: '离线升级漏洞', scan: 'VULNERABILITY_SCAN', change: 'VULNERABILITY_REPAIR', impact: '核验包版本、架构和哈希' }, { label: '14 项系统优化', scan: 'OPTIMIZATION_SCAN', change: 'OPTIMIZATION_APPLY', impact: '保存原值后逐项应用' }
]
const statusText = computed(() => running.value ? '处理中' : session.value ? '已连接' : '未连接')
const canConnect = computed(() => Boolean(form.value.host && form.value.username && runtimeConfigured()))
async function connect() { if (!canConnect.value || running.value) { append('请填写主机、用户名并配置 Java 服务令牌'); return } running.value=true; append(`正在连接 ${form.value.host}:${form.value.port}（SSH + SFTP）`); try { session.value=await openKylinSession({ ...form.value }); append(`会话已建立，架构 ${session.value.architecture}，SFTP 就绪`); store.setStatus('KylinOS 会话已连接','success') } catch(error) { append(error instanceof Error ? error.message : '连接失败'); store.setStatus('KylinOS 连接失败','error') } finally { running.value=false } }
async function disconnect() { if (!session.value) return; await closeKylinSession(session.value.id); session.value=null; job.value=null; append('会话已断开，凭据已从会话中释放') }
async function run(operation: KylinOperation) { if (!session.value || running.value) { append('请先建立 KylinOS 会话'); return } running.value=true; append(`提交 ${operation}`); try { await monitor(await submitKylinJob(session.value.id, operation)) } catch(error) { append(error instanceof Error ? error.message : '任务提交失败'); store.setStatus('任务失败','error') } finally { running.value=false } }
async function change(operation: KylinOperation) { if (!session.value || running.value) { append('请先建立 KylinOS 会话'); return } running.value=true; try { const confirmation=await issueKylinConfirmation(session.value.id, operation); append(`确认令牌有效期至 ${new Date(confirmation.expiresAt).toLocaleTimeString()}`); await monitor(await submitKylinJob(session.value.id, operation, confirmation.token, true)) } catch(error) { append(error instanceof Error ? error.message : '变更任务失败'); store.setStatus('变更失败','error') } finally { running.value=false } }
async function monitor(accepted: KylinJob) { job.value=accepted; for (let i=0; i<20 && (job.value.state==='ACCEPTED'||job.value.state==='RUNNING'); i++) { await new Promise(resolve=>window.setTimeout(resolve,300)); job.value=await getKylinJob(accepted.id) } job.value.steps.forEach(step=>append(`${step.name}: ${step.message}`)); store.setStatus(job.value.message,job.value.state==='SUCCEEDED'?'success':'error') }
</script>
<template>
  <ToolPageLayout tool-id="kylin"><div class="tool-screen with-log" data-od-id="kylin-screen">
    <BasePanel title="KylinOS 连接"><template #actions><span class="badge" :class="running ? 'warn' : ''">{{ statusText }}</span></template>
      <div class="form-grid top-gap"><label>主机<input v-model="form.host" placeholder="192.168.1.10" /></label><label>端口<input v-model.number="form.port" type="number" min="1" max="65535" /></label><label>用户<input v-model="form.username" /></label><label>密码<input v-model="form.password" type="password" autocomplete="off" /></label><label class="wide">私钥路径<input v-model="form.privateKey" placeholder="可选：RSA / ED25519 文件路径" /></label><label>私钥口令<input v-model="form.privateKeyPassphrase" type="password" autocomplete="off" /></label><label>sudo 密码<input v-model="form.sudoPassword" type="password" autocomplete="off" /></label></div>
      <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="running" @click="connect">建立 SSH + SFTP 会话</button><button class="btn btn-secondary" type="button" :disabled="running || !session" @click="disconnect">断开并释放凭据</button></div>
    </BasePanel>
    <BasePanel title="扫描与变更" description="所有变更均需预检、短期确认、备份、复验和恢复计划。"><div class="capability-grid"><div v-for="item in capabilities" :key="item.scan" class="capability"><div><strong>{{ item.label }}</strong><p class="muted">{{ item.impact }}</p></div><div class="button-row"><button class="btn btn-secondary" type="button" :disabled="running || !session" @click="run(item.scan)">扫描</button><button v-if="item.change" class="btn btn-primary" type="button" :disabled="running || !session" @click="change(item.change)">变更</button></div></div></div></BasePanel>
    <LogDrawer v-model:expanded="expanded" :lines="lines" od-id="kylin-log" @clear="clear" />
  </div></ToolPageLayout>
</template>
