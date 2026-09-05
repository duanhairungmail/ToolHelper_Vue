<script setup lang="ts">
import { computed } from 'vue'
import AppLayout from './AppLayout.vue'
import { getTool } from '@/config/tools'
import type { ToolId } from '@/types/tools'

const props = defineProps<{ toolId: ToolId }>()
const tool = computed(() => getTool(props.toolId))
</script>

<template>
  <AppLayout :tool="tool"><slot /></AppLayout>
</template>
