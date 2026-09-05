<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import ModalDialog from '@/components/common/ModalDialog.vue'
import { useAppStore } from '@/stores/app'
import { useMockTask } from '@/composables/useMockTask'
import { sqlService } from '@/mocks/services/tool-services'
import { escapeSql } from '@/utils/format'

interface Condition {
  field: string
  op: '=' | '!=' | 'LIKE'
  value: string
}

const store = useAppStore()
const sql = ref('SELECT id, name, department\nFROM employees\nORDER BY id ASC\nLIMIT 100;')
const formatted = ref('SELECT id, name\nFROM employees;')
const conditions = ref<Condition[]>([])
const condition = ref<Condition>({ field: '', op: '=', value: '' })
const showConditionDialog = ref(false)
const showConditionView = ref(false)
const { state, execute } = useMockTask(sqlService)

function addCondition() {
  if (!condition.value.field.trim() || !condition.value.value.trim()) {
    store.setStatus('字段和值不能为空', 'error')
    return
  }
  conditions.value.push({ ...condition.value })
  condition.value = { field: '', op: '=', value: '' }
  showConditionDialog.value = false
  store.setStatus('查询条件已添加', 'success')
}

function generateSql() {
  const where = conditions.value.length
    ? `\nWHERE ${conditions.value.map((item) => `${item.field} ${item.op} '${escapeSql(item.value)}'`).join(' AND ')}`
    : ''
  sql.value = `SELECT id, name, department\nFROM employees${where}\nORDER BY id ASC\nLIMIT 100;`
  store.setStatus('SQL 已生成', 'success')
}

async function runSql() {
  const result = await execute({ sql: sql.value })
  if (!result) return
  store.setStatus(result.message, 'success')
}

async function copyFormatted() {
  try {
    await navigator.clipboard.writeText(formatted.value)
    store.setStatus('SQL 已复制', 'success')
  } catch {
    store.setStatus('复制失败，请检查浏览器权限', 'error')
  }
}

function formatSql() {
  formatted.value = sql.value.replace(/\s+/g, ' ').replace(/\s+(FROM|WHERE|ORDER BY|LIMIT)\s+/gi, '\n$1 ')
}
</script>

<template>
  <ToolPageLayout tool-id="sql">
    <div class="tool-screen" data-od-id="sql-screen">
      <div class="equal-columns equal-height">
        <BasePanel title="SQL 生成器"><div class="form-grid two"><label class="field"><span>表名</span><input class="input" value="employees" aria-label="表名"></label><label class="field"><span>LIMIT</span><input class="input" value="100" aria-label="LIMIT"></label></div><div class="button-row top-gap"><button class="btn btn-primary" type="button" @click="generateSql">生成 SQL</button><button class="btn btn-secondary" type="button" @click="showConditionDialog = true">添加条件</button><button class="btn btn-secondary" type="button" @click="showConditionView = true">查看条件</button></div><textarea v-model="sql" class="codebox fill" aria-label="生成的 SQL"></textarea><button class="btn btn-primary top-gap" type="button" :disabled="state.status === 'loading'" @click="runSql">执行 SQL</button></BasePanel>
        <BasePanel title="SQL 格式化"><textarea v-model="formatted" class="input textarea" aria-label="待格式化 SQL"></textarea><div class="button-row top-gap"><button class="btn btn-primary" type="button" @click="formatSql">格式化</button><button class="btn btn-secondary" type="button" @click="copyFormatted">复制输出</button></div><pre class="terminal-box fill">{{ formatted }}</pre></BasePanel>
      </div>
      <ModalDialog v-model="showConditionDialog" title="添加查询条件" labelledby="condition-title"><div class="form-grid"><label class="field"><span>字段名</span><input v-model="condition.field" class="input" aria-label="条件字段"></label><label class="field"><span>运算符</span><select v-model="condition.op" class="input" aria-label="条件运算符"><option>=</option><option>!=</option><option>LIKE</option></select></label><label class="field"><span>值</span><input v-model="condition.value" class="input" aria-label="条件值"></label></div><div class="button-row top-gap"><button class="btn btn-primary" type="button" @click="addCondition">添加</button><button class="btn btn-secondary" type="button" @click="showConditionDialog = false">取消</button></div></ModalDialog>
      <ModalDialog v-model="showConditionView" title="已添加条件" labelledby="condition-list-title"><pre class="result-box">{{ conditions.length ? conditions.map((item, index) => `${index + 1}. ${item.field} ${item.op} ${item.value}`).join('\n') : '尚未添加条件' }}</pre><button class="btn btn-secondary top-gap" type="button" @click="showConditionView = false">关闭</button></ModalDialog>
    </div>
  </ToolPageLayout>
</template>
