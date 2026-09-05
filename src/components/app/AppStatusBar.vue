<script setup lang="ts">
import { useAppStore } from '@/stores/app'

const store = useAppStore()
</script>

<template>
  <footer class="statusbar">
    <div class="status" :class="store.statusType"><span class="status-dot" aria-hidden="true"></span><span>{{ store.status }}</span></div>
    <span class="shortcut">Ctrl + K 搜索</span>
  </footer>
</template>
