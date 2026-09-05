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

## Launcher 统一启动

在已安装 Java 21、Node.js/npm 和 .NET SDK 的开发环境中，可由 Launcher 统一启动 Vue、Java 和 C#：

```powershell
.\services\toolhelper-java\gradlew.bat -p .\services\toolhelper-java :launcher:run
```

Launcher 会动态分配回环端口和短期令牌，注入 Java/C# 环境变量，生成前端运行时配置，等待两个健康接口和前端页面就绪后打开浏览器。按 `Ctrl+C` 退出时会回收由本次 Launcher 启动的进程。

## 工程骨架

- `services/toolhelper-java`：Gradle 多模块 Spring Boot 服务，提供 `/actuator/health` 和 SSE。
- `services/toolhelper-agent`：.NET 8 Api/Application/Domain/Infrastructure 四层服务，提供 `/health` 和 SSE。
- `services/toolhelper-launcher`：Java 21 Launcher，分配回环端口、生成短期令牌并写入 `public/runtime-config.js`。
- `contracts/openapi`：统一响应、错误码、健康检查和 SSE 契约。
- `services/toolhelper-java/api`：SQLite 内部库迁移、用户数据库会话、元数据/查询/取消/SSE/CSV 导出 API。

Launcher 注入的 `runtime-config.js` 已被 Git 忽略，禁止将实际令牌提交到仓库。服务令牌通过 `TOOLHELPER_INTERNAL_TOKEN` 注入。

数据库工作台使用 Java 独占的内部库（默认 `%LOCALAPPDATA%/ToolHelper/data/toolhelper.db`）记录任务和审计；用户 `.db`、`.sqlite`、`.sqlite3` 通过独立会话池打开，禁止指向内部目录。查询结果按页返回，导出文件为 Excel 兼容 CSV。

## 生产构建

```bash
npm run build
```

构建结果位于 `dist` 目录，可运行 `npm run preview` 本地预览。

`public/toolhelper-interface-demo.html` 仅作为只读视觉回归参考，不参与 Vue 构建；`server.mjs` 为历史静态原型服务器，不是当前开发入口。
