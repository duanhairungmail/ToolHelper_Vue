<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { getRuntimeConfig, joinApiUrl, runtimeConfigured } from '@/config/runtime'
import {
  cancelDatabaseQuery,
  closeDatabaseSession,
  createDatabaseSession,
  downloadDatabaseQuery,
  getDatabaseQueryResult,
  loadDatabaseMetadata,
  loadDatabaseColumns,
  submitDatabaseQuery,
  submitDatabaseMutation,
  testDatabaseSession,
  type DatabaseObject,
  type DatabaseSession,
  type QueryResult
} from '@/api/database/client'

interface QueryTab {
  id: number
  title: string
  sql: string
}

const store = useAppStore()
const { lines, append, clear, connect } = useJobStream(['SQLite 工作台已就绪'])
const databaseFile = ref('')
const password = ref('')
const session = ref<DatabaseSession | null>(null)
const objects = ref<DatabaseObject[]>([])
const connectionError = ref('')
const loading = ref(false)
const queryTaskId = ref('')
const queryPage = ref(0)
const queryPageSize = ref(100)
const queryResult = ref<QueryResult | null>(null)
const resultFilter = ref('')
const sortKey = ref('')
const sortDescending = ref(false)
const resultTabActive = ref(true)
const editTable = ref('')
const editPrimaryKey = ref('')
const editPrimaryKeyValue = ref('')
const editChanges = ref('{\n  "name": "新值"\n}')
const editColumns = ref<{ name: string; type: string; primaryKey: boolean; notNull: boolean }[]>([])
const nextTabId = ref(2)
const activeTabId = ref(1)
const tabs = ref<QueryTab[]>([
  { id: 1, title: '查询 1', sql: 'SELECT name, type\nFROM sqlite_schema\nORDER BY name;' }
])

const activeTab = computed<QueryTab>(() => tabs.value.find((tab) => tab.id === activeTabId.value) ?? tabs.value[0]!)
const gridColumns = computed(() => (queryResult.value?.columns ?? []).map((field) => ({
  field,
  title: field,
  minWidth: 140,
  showOverflow: true
})))
const highRisk = computed(() => /^(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|REPLACE|VACUUM|ATTACH|DETACH)\b/i.test(activeTab.value.sql.trim()))
const visibleRows = computed(() => {
  const result = queryResult.value
  if (!result) return []
  const keyword = resultFilter.value.trim().toLowerCase()
  const filtered = result.rows.filter((row) => !keyword || row.some((cell) => String(cell ?? '').toLowerCase().includes(keyword)))
  if (!sortKey.value) return filtered
  const index = result.columns.indexOf(sortKey.value)
  if (index < 0) return filtered
  return [...filtered].sort((left, right) => {
    const a = String(left[index] ?? '')
    const b = String(right[index] ?? '')
    return (a.localeCompare(b, 'zh-CN', { numeric: true }) || 0) * (sortDescending.value ? -1 : 1)
  })
})

async function openDatabase() {
  if (!runtimeConfigured()) return fail('运行时未配置，无法连接 Java 数据服务')
  if (!databaseFile.value.trim()) return fail('请输入 SQLite 文件路径')
  loading.value = true
  connectionError.value = ''
  try {
    await closeCurrentSession()
    session.value = await createDatabaseSession(databaseFile.value.trim(), password.value)
    password.value = ''
    await testDatabaseSession(session.value.sessionId)
    objects.value = await loadDatabaseMetadata(session.value.sessionId)
    append(`已打开 SQLite：${session.value.path}`)
    store.setStatus('SQLite 用户库已连接', 'success')
  } catch (error) {
    fail(error instanceof Error ? error.message : 'SQLite 连接失败')
  } finally {
    loading.value = false
  }
}

async function closeCurrentSession() {
  if (!session.value) return
  await closeDatabaseSession(session.value.sessionId).catch(() => undefined)
  session.value = null
  objects.value = []
  queryResult.value = null
}

async function closeDatabase() {
  await closeCurrentSession()
  append('已关闭 SQLite 会话')
  store.setStatus('SQLite 用户库已关闭')
}

async function refreshObjects() {
  if (!session.value) return fail('请先打开 SQLite 文件')
  objects.value = await loadDatabaseMetadata(session.value.sessionId)
  append(`对象树已刷新，共 ${objects.value.length} 项`)
}

async function loadEditColumns() {
  if (!session.value || !editTable.value.trim()) return fail('请输入要编辑的表名')
  editColumns.value = await loadDatabaseColumns(session.value.sessionId, editTable.value.trim())
  const primaryKey = editColumns.value.find((column) => column.primaryKey)
  editPrimaryKey.value = primaryKey?.name || ''
  if (editColumns.value.filter((column) => column.primaryKey).length !== 1) fail('该表不是唯一主键表，禁止编辑')
}

async function submitChanges() {
  if (!session.value || !editTable.value || !editPrimaryKey.value || !editPrimaryKeyValue.value) return fail('请先加载唯一主键表并填写主键值')
  try {
    const changes = JSON.parse(editChanges.value) as Record<string, unknown>
    const count = await submitDatabaseMutation(session.value.sessionId, editTable.value, editPrimaryKey.value, editPrimaryKeyValue.value, changes)
    append(`变更集已提交，影响 ${count} 行`)
    await refreshObjects()
    store.setStatus('数据库变更集已提交', 'success')
  } catch (error) {
    fail(error instanceof Error ? error.message : '变更集提交失败')
  }
}

async function executeSql() {
  if (!session.value) return fail('请先打开 SQLite 文件')
  const sql = activeTab.value.sql.trim()
  if (!sql) return fail('SQL 不能为空')
  if (highRisk.value && !window.confirm('当前 SQL 会修改数据库或数据库结构，是否继续？')) return
  loading.value = true
  queryPage.value = 0
  queryResult.value = null
  try {
    const accepted = await submitDatabaseQuery(session.value.sessionId, sql, queryPage.value, queryPageSize.value, highRisk.value)
    queryTaskId.value = accepted.taskId
    append(`查询任务已提交：${accepted.taskId}`)
    await connect(joinApiUrl(getRuntimeConfig().javaApiBase, `/api/database/queries/${accepted.taskId}/events`), { reconnect: false })
    queryResult.value = await getDatabaseQueryResult(accepted.taskId)
    append(`查询完成：${queryResult.value.rowCount} 行`)
    resultTabActive.value = true
    store.setStatus('SQLite 查询完成', 'success')
  } catch (error) {
    fail(error instanceof Error ? error.message : 'SQL 执行失败')
  } finally {
    loading.value = false
  }
}

async function cancelQuery() {
  if (!queryTaskId.value) return
  await cancelDatabaseQuery(queryTaskId.value)
  append('已请求取消查询')
  loading.value = false
}

async function nextPage() {
  if (!queryResult.value?.hasMore || !session.value) return
  queryPage.value += 1
  await executeSql()
}

async function previousPage() {
  if (queryPage.value === 0) return
  queryPage.value -= 1
  await executeSql()
}

function addTab() {
  const id = nextTabId.value++
  tabs.value.push({ id, title: `查询 ${id}`, sql: 'SELECT 1;' })
  activeTabId.value = id
  resultTabActive.value = false
}

function formatSql() {
  activeTab.value.sql = activeTab.value.sql.replace(/\s+/g, ' ').replace(/\s*,\s*/g, ',\n').replace(/\s+(FROM|WHERE|GROUP BY|ORDER BY|LIMIT)\s+/gi, '\n$1 ')
}

function insertObject(name: string) {
  activeTab.value.sql += `\nSELECT * FROM "${name.replaceAll('"', '""')}";`
  resultTabActive.value = false
}

async function copyResult() {
  if (!queryResult.value) return
  const text = [queryResult.value.columns, ...visibleRows.value].map((row) => row.join('\t')).join('\n')
  await navigator.clipboard.writeText(text)
  append('结果已复制到剪贴板')
}

async function exportResult() {
  if (!queryTaskId.value) return fail('没有可导出的查询结果')
  const blob = await downloadDatabaseQuery(queryTaskId.value)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `toolhelper-query-${queryTaskId.value}.csv`
  link.click()
  URL.revokeObjectURL(url)
  append('已导出 Excel 兼容 CSV')
}

function fail(message: string) {
  connectionError.value = message
  append(message)
  store.setStatus(message, 'error')
}

onBeforeUnmount(() => {
  void closeCurrentSession()
})
</script>

<template>
  <ToolPageLayout tool-id="database">
    <div class="tool-screen with-log database-workbench" data-od-id="database-screen">
      <div class="database-layout">
        <aside class="database-sidebar">
          <BasePanel title="SQLite 连接">
            <label class="field"><span>数据库文件</span><input v-model="databaseFile" class="input" placeholder="D:/data/example.sqlite" aria-label="SQLite 数据库文件"></label>
            <label class="field top-gap"><span>加密口令（仅本次连接）</span><input v-model="password" class="input" type="password" autocomplete="off" aria-label="SQLite 加密口令"></label>
            <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="loading" @click="openDatabase">打开 / 测试</button><button class="btn btn-secondary" type="button" :disabled="loading || !session" @click="closeDatabase">关闭</button></div>
            <p v-if="connectionError" class="error-text top-gap">{{ connectionError }}</p>
            <p class="muted top-gap">用户库与 ToolHelper 内部库隔离，路径仅允许 .db/.sqlite/.sqlite3。</p>
          </BasePanel>
          <BasePanel title="对象浏览器">
            <div class="button-row object-toolbar"><button class="btn btn-ghost" type="button" :disabled="!session" @click="refreshObjects">刷新</button><span class="muted">{{ objects.length }} 项</span></div>
            <ul class="object-tree"><li v-for="object in objects" :key="`${object.type}:${object.name}`"><button type="button" class="object-button" @click="insertObject(object.name)">{{ object.type }} / {{ object.name }}</button></li><li v-if="!objects.length" class="muted">打开数据库后加载对象</li></ul>
          </BasePanel>
          <BasePanel title="唯一主键编辑">
            <label class="field"><span>表名</span><input v-model="editTable" class="input" placeholder="users" aria-label="编辑表名"></label>
            <div class="button-row top-gap"><button class="btn btn-secondary" type="button" :disabled="!session" @click="loadEditColumns">读取主键</button><span class="muted">{{ editColumns.filter((column) => column.primaryKey).length === 1 ? '唯一主键' : '未确认' }}</span></div>
            <label class="field top-gap"><span>主键列 / 值</span><div class="inline-fields"><input v-model="editPrimaryKey" class="input" placeholder="id" aria-label="主键列"><input v-model="editPrimaryKeyValue" class="input" placeholder="1" aria-label="主键值"></div></label>
            <label class="field top-gap"><span>变更集 JSON</span><textarea v-model="editChanges" class="textarea" aria-label="变更集 JSON" spellcheck="false"></textarea></label>
            <button class="btn btn-primary top-gap" type="button" :disabled="!session" @click="submitChanges">提交变更集</button>
          </BasePanel>
        </aside>
        <section class="database-main">
          <BasePanel title="SQL 工作区">
            <div class="tab-row" role="tablist" aria-label="SQL 查询标签"><button v-for="tab in tabs" :key="tab.id" class="tab-button" :class="{ active: !resultTabActive && activeTabId === tab.id }" type="button" role="tab" :aria-selected="!resultTabActive && activeTabId === tab.id" @click="activeTabId = tab.id; resultTabActive = false">{{ tab.title }}</button><button class="tab-button" type="button" @click="addTab">+ 新标签</button><button class="tab-button" :class="{ active: resultTabActive }" type="button" @click="resultTabActive = true">结果集</button></div>
            <template v-if="!resultTabActive">
              <textarea v-model="activeTab.sql" class="codebox sql-editor" aria-label="SQL 语句" spellcheck="false"></textarea>
              <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="loading || !session" @click="executeSql">{{ loading ? '执行中…' : '执行 SQL' }}</button><button class="btn btn-secondary" type="button" :disabled="!loading" @click="cancelQuery">取消</button><button class="btn btn-secondary" type="button" @click="formatSql">格式化</button><button class="btn btn-secondary" type="button" @click="activeTab.sql = ''">清空</button><span v-if="highRisk" class="badge warn">需二次确认</span></div>
            </template>
            <template v-else>
              <div v-if="queryResult" class="result-toolbar"><input v-model="resultFilter" class="input" placeholder="筛选当前页" aria-label="筛选结果"><select v-model="sortKey" class="input" aria-label="排序列"><option value="">不排序</option><option v-for="column in queryResult.columns" :key="column" :value="column">按 {{ column }} 排序</option></select><button class="btn btn-secondary" type="button" @click="sortDescending = !sortDescending">{{ sortDescending ? '降序' : '升序' }}</button><button class="btn btn-secondary" type="button" @click="copyResult">复制</button><button class="btn btn-secondary" type="button" @click="exportResult">导出 Excel(CSV)</button></div>
              <div v-if="queryResult" class="result-table-wrap"><vxe-grid border stripe height="360" :columns="gridColumns" :data="visibleRows" :scroll-y="{ enabled: true, gt: 100 }" :row-config="{ isHover: true }"><template #empty>没有匹配结果</template></vxe-grid></div>
              <div v-if="queryResult" class="pagination"><span class="muted">第 {{ queryPage + 1 }} 页 · 当前 {{ visibleRows.length }} 行{{ queryResult.hasMore ? '以上' : '' }}</span><button class="btn btn-ghost" type="button" :disabled="queryPage === 0 || loading" @click="previousPage">上一页</button><button class="btn btn-ghost" type="button" :disabled="!queryResult.hasMore || loading" @click="nextPage">下一页</button></div>
              <div v-else class="empty-state">执行查询后显示分页结果</div>
            </template>
          </BasePanel>
        </section>
      </div>
      <LogDrawer :lines="lines" od-id="database-log" @clear="clear" />
    </div>
  </ToolPageLayout>
</template>
