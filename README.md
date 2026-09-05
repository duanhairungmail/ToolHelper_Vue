# ToolHelper Vue

调试工具助手 Vue 版。

ToolHelper Vue 是标准 Vue 3 + TypeScript 前端，并配套 Java 21、.NET 8 本机服务骨架和 Java Launcher。前端不通过 iframe 加载旧 HTML；服务只监听回环地址，运行时令牌由 Launcher 注入。

## 环境要求

- Node.js 18 或更高版本
- npm 9 或更高版本

## 开发运行

```bash
npm install
npm run dev
```

浏览器访问终端输出的本地地址。

默认地址为 `http://127.0.0.1:5173/`，根路径和未知路径都会重定向到 `/remote`。除远程页外，工具页面位于 `/tools/<tool-id>`；当前业务动作仍由显式 mock service 返回，并会显示“演示模式”。

## 工程骨架

- `services/toolhelper-java`：Gradle 多模块 Spring Boot 服务，提供 `/actuator/health` 和 SSE。
- `services/toolhelper-agent`：.NET 8 Api/Application/Domain/Infrastructure 四层服务，提供 `/health` 和 SSE。
- `services/toolhelper-launcher`：Java 21 Launcher，分配回环端口、生成短期令牌并写入 `public/runtime-config.js`。
- `contracts/openapi`：统一响应、错误码、健康检查和 SSE 契约。

Launcher 注入的 `runtime-config.js` 已被 Git 忽略，禁止将实际令牌提交到仓库。服务令牌通过 `TOOLHELPER_INTERNAL_TOKEN` 注入。

## 生产构建

```bash
npm run build
```

构建结果位于 `dist` 目录，可运行 `npm run preview` 本地预览。

`public/toolhelper-interface-demo.html` 仅作为只读视觉回归参考，不参与 Vue 构建；`server.mjs` 为历史静态原型服务器，不是当前开发入口。
