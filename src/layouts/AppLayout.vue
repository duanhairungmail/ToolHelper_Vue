<script setup lang="ts">
import AppHeader from '@/components/app/AppHeader.vue'
import AppSidebar from '@/components/app/AppSidebar.vue'
import AppStatusBar from '@/components/app/AppStatusBar.vue'
import type { ToolDefinition } from '@/types/tools'

defineProps<{ tool: ToolDefinition }>()
</script>

<template><div class="app"><AppSidebar /><main class="workspace"><AppHeader :tool="tool" /><div class="screen-content"><slot /></div><AppStatusBar /></main></div></template>
