<script setup lang="ts">
interface LogDrawerProps {
  title?: string
  lines: readonly string[]
  odId?: string
}

withDefaults(defineProps<LogDrawerProps>(), {
  title: '操作日志与诊断',
  odId: 'log-drawer'
})

const expanded = defineModel<boolean>('expanded', { default: true })
const emit = defineEmits<{ clear: [] }>()
</script>

<template>
  <section class="detail-drawer" :class="{ collapsed: !expanded }" :data-od-id="odId">
    <header class="drawer-head">
      <strong>{{ title }}</strong>
      <div class="button-row">
        <button class="btn btn-ghost" type="button" @click="emit('clear')">清空</button>
        <button class="btn btn-ghost" type="button" @click="expanded = !expanded">{{ expanded ? '收起日志' : '展开日志' }}</button>
      </div>
    </header>
    <div v-show="expanded" class="drawer-body"><pre class="terminal-box">{{ lines.length ? lines.join('\n') : '暂无日志' }}</pre></div>
  </section>
</template>
