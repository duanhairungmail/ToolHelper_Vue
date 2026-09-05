import type { Component } from 'vue'
import type { RouteRecordRaw } from 'vue-router'
import type { ToolId } from '@/types/tools'
import { tools } from '@/config/tools'

const views: Record<ToolId, () => Promise<{ default: Component }>> = {
  remote: () => import('@/views/remote/RemoteIntegrationView.vue'),
  database: () => import('@/views/database/DatabaseWorkspaceView.vue'),
  api: () => import('@/views/api/ApiValidationView.vue'),
  mac: () => import('@/views/mac/DeviceMacView.vue'),
  nodered: () => import('@/views/nodered/NodeRedIntegrationView.vue'),
  druid: () => import('@/views/druid/DruidScanView.vue'),
  kylin: () => import('@/views/kylin/KylinPolicyView.vue'),
  serial: () => import('@/views/serial/SerialDebuggerView.vue'),
  modbus: () => import('@/views/modbus/ModbusDebuggerView.vue'),
  ping: () => import('@/views/ping/GroupPingView.vue'),
  cron: () => import('@/views/cron/CronExpressionView.vue'),
  sql: () => import('@/views/sql/SqlToolView.vue'),
  aes: () => import('@/views/aes/AesToolView.vue')
}

export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/remote' },
  ...tools.map((tool) => ({
    path: tool.id === 'remote' ? '/remote' : `/tools/${tool.id}`,
    name: tool.id,
    component: views[tool.id],
    meta: { title: tool.name, toolId: tool.id }
  })),
  { path: '/tools/remote', redirect: '/remote' },
  { path: '/:pathMatch(.*)*', redirect: '/remote' }
]
