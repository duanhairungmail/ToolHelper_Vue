import type { ToolDefinition } from '@/types/tools'

export const tools = [
  { id: 'remote', group: '连接与接口', name: '远程外挂连接', keywords: ['Electerm Web', '外挂生命周期'], lead: '管理 Electerm Web 外挂安装、启动、重启、停止与日志。', action: '' },
  { id: 'database', group: '连接与接口', name: 'SQLite 文件工作台', keywords: ['SQLite', '数据库文件'], lead: '打开本地 SQLite 文件并浏览对象、编辑 SQL 与查看结果。', action: '' },
  { id: 'api', group: '连接与接口', name: '极早期接口验证', keywords: ['API', 'MQTT'], lead: '在开发早期构造请求、检查响应并保存常用接口。', action: '保存请求' },
  { id: 'mac', group: '连接与接口', name: '获取设备 MAC 地址', keywords: ['ARP'], lead: '获取同子网设备 MAC 地址，并保存或导出设备台账。', action: '验证驱动' },
  { id: 'nodered', group: 'MQTT 测试工具', name: 'Node-RED 可视化编排', keywords: ['MQTT', 'Modbus', 'HTTP'], lead: '管理便携运行时并编排串口、Modbus 与 HTTP 流程。', action: '刷新状态' },
  { id: 'druid', group: '安全与运维', name: 'Druid 漏洞检测', keywords: ['安全扫描'], lead: '扫描常见暴露路径，汇总风险结果与修复方向。', action: '更新规则' },
  { id: 'kylin', group: '安全与运维', name: 'KylinOS 运维策略', keywords: ['系统优化'], lead: '管理系统激活、服务部署、漏洞扫描与优化策略。', action: '刷新状态' },
  { id: 'serial', group: '调试工具', name: '基本串口调试', keywords: ['COM'], lead: '配置串口并以文本或十六进制收发数据。', action: '刷新串口' },
  { id: 'modbus', group: '调试工具', name: '极早期 Modbus 调试', keywords: ['RTU'], lead: '连接 Modbus 设备，读取解析结果并核对原始报文。', action: '扫描设备' },
  { id: 'ping', group: '调试工具', name: '群 Ping', keywords: ['网络检测'], lead: '批量检测网段可达性、延迟与丢包。', action: '导入目标' },
  { id: 'cron', group: '调试工具', name: 'Cron 表达式', keywords: ['日期'], lead: '生成表达式并预览未来执行时间。', action: '复制表达式' },
  { id: 'sql', group: '调试工具', name: 'SQL 语句生成与格式化', keywords: ['SELECT', 'INSERT', 'UPDATE'], lead: '同屏完成 SQL 生成、格式化、复制与下载。', action: '加载 SQL' },
  { id: 'aes', group: '调试工具', name: 'AES 加密/解密', keywords: ['CBC', 'ECB', 'GCM'], lead: '配置 AES 参数并在本机完成加密和解密。', action: '重置参数' }
] as const satisfies readonly ToolDefinition[]

export const toolGroups = [...new Set(tools.map((tool) => tool.group))]

export function getTool(id: string): ToolDefinition {
  return tools.find((tool) => tool.id === id) || tools[0]
}
