<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { toolGroups, tools } from '@/config/tools'
import { useAppStore } from '@/stores/app'
import logo from '@/assets/logo.png'

const query = ref('')
const route = useRoute()
const store = useAppStore()
const visibleTools = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return tools.filter((tool) => `${tool.name} ${tool.keywords.join(' ')}`.toLowerCase().includes(keyword))
})
</script>

<template>
  <aside class="sidebar" :class="{ 'mobile-open': store.sidebarOpen }" data-od-id="tool-sidebar">
    <header class="brand">
      <img :src="logo" alt="ToolHelper 标志">
      <div><strong>ToolHelper</strong><span>极早期调试辅助工具</span></div>
    </header>
    <div class="search-wrap">
      <input v-model="query" class="input" placeholder="搜索工具" aria-label="搜索工具">
    </div>
    <nav class="nav-scroll" aria-label="工具导航">
      <template v-for="group in toolGroups" :key="group">
        <section v-if="visibleTools.some((tool) => tool.group === group)" class="nav-group">
          <div class="nav-label">{{ group }}</div>
          <RouterLink
            v-for="tool in visibleTools.filter((item) => item.group === group)"
            :key="tool.id"
            class="nav-item"
            :class="{ active: route.name === tool.id }"
            :to="tool.id === 'remote' ? '/remote' : `/tools/${tool.id}`"
            @click="store.sidebarOpen = false"
          >{{ tool.name }}</RouterLink>
        </section>
      </template>
      <p v-if="!visibleTools.length" class="nav-empty">没有匹配的工具</p>
    </nav>
    <footer class="sidebar-foot"><span>13 个工具</span><span>Vue 3 + TypeScript</span></footer>
  </aside>
</template>
