<script setup lang="ts">
defineProps<{
  title?: string
  description?: string
}>()
</script>

<template>
  <section class="panel">
    <header v-if="$slots.title || title" class="panel-head">
      <div><h2><slot name="title">{{ title }}</slot></h2><p v-if="description">{{ description }}</p></div>
      <slot name="actions" />
    </header>
    <slot />
  </section>
</template>
