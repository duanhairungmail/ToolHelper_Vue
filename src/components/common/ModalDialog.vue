<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

interface ModalDialogProps {
  title: string
  labelledby?: string
}

withDefaults(defineProps<ModalDialogProps>(), {
  labelledby: undefined
})

const open = defineModel<boolean>({ default: false })
const dialogRef = ref<HTMLElement>()
let returnFocus: HTMLElement | null = null

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    open.value = false
  }
}

watch(open, async (isOpen) => {
  if (isOpen) {
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    document.addEventListener('keydown', onKeydown)
    await nextTick()
    dialogRef.value?.focus()
    return
  }
  document.removeEventListener('keydown', onKeydown)
  returnFocus?.focus()
  returnFocus = null
})

onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div v-if="open" class="modal-backdrop" @click.self="open = false">
    <section
      ref="dialogRef"
      class="modal"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="labelledby"
      tabindex="-1"
    >
      <h2 :id="labelledby">{{ title }}</h2>
      <slot />
    </section>
  </div>
</template>
