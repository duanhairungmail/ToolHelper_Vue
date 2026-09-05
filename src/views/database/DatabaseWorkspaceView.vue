<script setup lang="ts">
import { ref } from 'vue'
import ToolPageLayout from '@/layouts/ToolPageLayout.vue'
import BasePanel from '@/components/common/BasePanel.vue'
import LogDrawer from '@/components/feedback/LogDrawer.vue'
import { useAppStore } from '@/stores/app'
import { useJobStream } from '@/composables/useJobStream'
import { useMockTask } from '@/composables/useMockTask'
import { databaseService } from '@/mocks/services/tool-services'

const store = useAppStore()
const { lines, append, clear } = useJobStream(['SQLite 工作台已就绪'])
const { state, execute } = useMockTask(databaseService)
const databaseFile = ref('data/toolhelper.sqlite')
const activeTab = ref('SQL 查询')
const sql = ref('SELECT name, type\nFROM sqlite_schema\nORDER BY name;')
const tabs = ['SQL 查询', '结果集']
const objects = ['sqlite_schema', 'settings', 'jobs', 'logs']

async function run(action: string) {
  append(action)
  const result = await execute({ action })
  if (!result) return
  append(result.message)
  store.setStatus(result.message, 'success')
}
</script>

<template>
  <ToolPageLayout tool-id="database">
    <div class="tool-screen with-log" data-od-id="database-screen">
      <div class="equal-columns database-layout">
        <BasePanel title="SQLite 文件工作台">
          <label class="field"><span>数据库文件</span><input v-model="databaseFile" class="input" aria-label="SQLite 数据库文件"></label>
          <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="state.status === 'loading'" @click="run('打开 SQLite 文件')">打开文件</button><button class="btn btn-secondary" type="button" @click="run('刷新对象树')">刷新</button></div>
          <p class="muted top-gap">用户库与 ToolHelper 内部库隔离，当前为演示连接。</p>
          <h3 class="section-title">对象树</h3>
          <ul class="object-tree"><li v-for="object in objects" :key="object">表 / {{ object }}</li></ul>
        </BasePanel>
        <BasePanel title="SQL 工作区">
          <div class="tab-row" role="tablist" aria-label="SQL 工作区标签"><button v-for="tab in tabs" :key="tab" class="tab-button" :class="{ active: activeTab === tab }" type="button" role="tab" :aria-selected="activeTab === tab" @click="activeTab = tab">{{ tab }}</button></div>
          <textarea v-model="sql" class="codebox sql-editor" aria-label="SQL 语句"></textarea>
          <div class="button-row top-gap"><button class="btn btn-primary" type="button" :disabled="state.status === 'loading'" @click="run('执行 SQL')">执行 SQL</button><button class="btn btn-secondary" type="button" @click="sql = ''">清空</button></div>
          <div v-if="activeTab === '结果集'" class="result-box top-gap">演示模式：执行结果将在 SQLite service 接入后显示。</div>
          <div v-else class="result-box top-gap">选择“结果集”查看执行结果。</div>
        </BasePanel>
      </div>
      <LogDrawer :lines="lines" od-id="database-log" @clear="clear" />
    </div>
  </ToolPageLayout>
</template>
