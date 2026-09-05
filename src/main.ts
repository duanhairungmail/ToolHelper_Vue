import { createApp } from 'vue'
import { createPinia } from 'pinia'
import VxeUITable from 'vxe-table'
import App from './App.vue'
import router from './router'
import './assets/styles/main.css'
import 'vxe-table/lib/style.css'

async function loadRuntimeConfig() {
  await new Promise<void>((resolve) => {
    const script = document.createElement('script')
    script.src = '/runtime-config.js'
    script.onload = () => resolve()
    script.onerror = () => resolve()
    document.head.appendChild(script)
  })
}

await loadRuntimeConfig()
createApp(App).use(createPinia()).use(router).use(VxeUITable).mount('#app')
