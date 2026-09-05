# ToolHelper Vue

调试工具助手 Vue 版。

ToolHelper 高保真前端原型的标准 Vue 3 + Vite 工程，采用 Vue Router、Pinia、Composition API 和组件化页面结构，不再通过 iframe 加载旧 HTML。当前重构范围是前端架构与演示交互，尚未接入 Java/C# 后端或本机系统能力。

## 环境要求

- Node.js 18 或更高版本
- npm 9 或更高版本

## 开发运行

```bash
npm install
npm run dev
```

浏览器访问终端输出的本地地址。

默认地址为 `http://localhost:5173/`，根路径和未知路径都会重定向到“远程外挂连接”。13 个工具页面均位于 `/tools/<tool-id>`，页面中的耗时动作通过显式 mock service 返回，并会显示“演示模式”。

## 生产构建

```bash
npm run build
```

构建结果位于 `dist` 目录，可运行 `npm run preview` 本地预览。

`public/toolhelper-interface-demo.html` 仅作为只读视觉回归参考，不参与 Vue 构建；`server.mjs` 为历史静态原型服务器，不是当前开发入口。
