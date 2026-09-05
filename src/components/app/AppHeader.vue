<script setup lang="ts">
import { useAppStore } from '@/stores/app'
import type { ToolDefinition } from '@/types/tools'

defineProps<{ tool: ToolDefinition }>()
const store = useAppStore()
</script>

<template>
  <header class="page-head" data-od-id="page-header">
    <div class="title-row">
      <button class="btn btn-secondary mobile-menu" type="button" @click="store.sidebarOpen = true">菜单</button>
      <div><h1>{{ tool.name }}</h1><p>{{ tool.lead }}</p></div>
    </div>
    <button v-if="tool.action" class="btn btn-secondary" type="button" @click="store.setStatus(`${tool.action}操作已完成`, 'success')">{{ tool.action }}</button>
  </header>
</template>
