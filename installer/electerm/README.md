# Electerm Web 集成与发布

阶段6实施说明。此文档说明构建和配置，不代表已执行或通过阶段验收。

## 固定基线

- Electerm Web `5.3.26`，Git commit `54cae0be962f2c948c84067a2eee279632f49371`。
- 本机源码实际位置为 `D:\DevlopProject\electerm-web`；方案原 `D:\Desktop\ToolHelpe_Design\electerm-web` 路径已不存在。
- Node.js `24.19.0`，Windows X64，npm 使用官方运行时压缩包中自带版本。
- `source-lock.json` 固定上游提交、依赖锁 SHA-256、官方 Node 压缩包与 node.exe SHA-256。
- `package-lock.json` 保存完整上游依赖锁的内容。构建使用固定 Git 对象内的原始锁文件校验字节 SHA-256，避免 Git 换行转换影响比较。
- 生产程序只使用运行包内 `runtime/node.exe`，不查找全局 Node/npm。

## 构建签名运行包

构建需要开发机 Node.js、Git、Windows tar 和 Java 21 的 `jar` 命令。所有中间产物位于本仓库忽略的 `test-runtime/electerm-build`；不写入上游源码，也不包含其 `.env`、数据或未提交修改。

```powershell
node installer/electerm/build-release.mjs `
  --source D:/DevlopProject/electerm-web `
  --signing-key D:/private/toolhelper-release-ed25519.pem `
  --output installer/electerm/artifacts `
  --jar D:/DevlopEnvironment/java21/bin/jar.exe
```

签名密钥必须是发布方保管的 Ed25519 PKCS8 PEM；私钥不进入运行包或仓库。发布公钥为 SPKI PEM，通过可信安装器或本机配置独立提供，不能从待安装的离线包自动导入信任。

仅供本机开发的密钥可用下列命令生成到忽略的临时目录；不能作为生产信任根：

```powershell
node installer/electerm/build-release.mjs --generate-development-key true
```

构建先 `npm ci --legacy-peer-deps --ignore-scripts`，显式执行上游前端资源准备与 Vite 构建，再 `npm ci --legacy-peer-deps --omit=dev --ignore-scripts` 安装相同锁文件中的运行依赖。两次都不解析新版本，构建后再次检查锁 SHA-256。构建检查 node-pty、SSH 及 Node SQLite 模块可加载，不启动 Electerm 或远程连接。

输出：

- `payload.zip`：`app/`、`runtime/`、`NOTICES.md`。
- `manifest.json`：版本、架构、上游提交、依赖锁及二进制哈希。
- `manifest.sig`：对 manifest 原始字节的 Ed25519 签名，Base64 编码。
- `electerm-5.3.26-offline.zip`：manifest、签名、payload 的离线封装。

运行包和开发密钥不提交 Git。源码与依赖锁、打包工具、许可证和修改记录提交 Git。

## 受控源与本机配置

Java 从当前进程环境读取下列参数（修改后重启 Java 服务生效）：

| 参数 | 默认/用途 |
| --- | --- |
| `TOOLHELPER_ELECTERM_PUBLIC_KEY` | 必填的独立发布公钥路径；未配置时拒绝在线、离线安装 |
| `TOOLHELPER_ELECTERM_FEED` | 可选受控 HTTPS 目录，以 `/` 结尾；不允许查询参数、URL 凭据或重定向 |
| `TOOLHELPER_ELECTERM_PORT` | `5577`，冲突时报告 `PORT_IN_USE`，不结束未知进程 |
| `TOOLHELPER_ELECTERM_HOME` | `%LOCALAPPDATA%\ToolHelper\integrations\electerm`，程序、私有管理文件、备份 |
| `TOOLHELPER_ELECTERM_DATA` | `%LOCALAPPDATA%\ToolHelper\data\electerm`，独立持久数据目录 |
| `TOOLHELPER_ELECTERM_INBOX` | 程序管理目录下 `inbox`，受控离线包导入暂存 |

本轮未提供生产受控源地址或生产发布公钥，代码不内置虚构地址，也不自动信任开发签名。发布方需配置上述信任根。开发环境可显式将 `PUBLIC_KEY` 指向本机生成的 development-public.pem，再在页面选择本机开发签名离线包；本轮未执行安装或启动。

受控源放置 `manifest.json`、`manifest.sig`、`payload.zip`，三者通过 HTTPS 下载。离线导入也必须通过同一发布公钥验证。校验签名、版本（拒绝降级）、架构、压缩包 SHA-256、node.exe SHA-256/PE X64 标识与依赖锁 SHA-256 后才暂存为新版本目录。解压限制路径、重复文件、Windows 保留名称、条目数和膨胀大小。

## 生命周期与首次登录

Vue 页面通过 Java API 安装、启动、停止、重启、查看状态/诊断并打开浏览器，无 iframe、WebView2 或凭据表单。Java 同时确认自有进程存活、回环 HTTP 健康标识匹配和主页面可读，才返回 `RUNNING` 和浏览器 URL。

Electerm 自身保持独立密码认证。首次启动生成高熵密码与签名密钥，保存在 `%LOCALAPPDATA%\ToolHelper\integrations\electerm\private\auth.properties`；文件及父目录限制为当前用户访问。首次登录由本机用户读取其中的 `password` 值，在 Electerm 自身页面输入。不要将该文件内容复制到日志、URL、工单或 Git。JWT 只由 Electerm 自身认证链路处理，不由 ToolHelper URL/SQLite/诊断保存。ToolHelper 服务令牌不会传给 Node 子进程。

固定启动参数：`HOST=127.0.0.1`、`ENABLE_AUTH=1`、`DISABLE_LOCAL_TERMINAL=1`、`TOKEN_EXPIRED_TIME=8h`、`NODE_ENV=production` 和独立 `DB_PATH`。认证值通过进程环境传入，不进入命令参数。

目录管理锁和服务串行锁防止重复实例。PID、进程启动时间、版本、端口、实例标识另存管理目录 `process.json`；不会凭历史 PID 接管未知进程。清理仅针对本次创建的 Process 和记录启动时间匹配的子进程。Java 退出 hook 绕过启动健康等待锁；Node 同时监测 Java 父进程，Java 异常退出时按自身 PID 回收进程树。实现按 10 秒退出窗口设计，实际退出耗时留待阶段验收。

## 备份、升级与卸载

备份前停服，以保证 SQLite 主文件、WAL、配置和其他数据处于同一个静止快照。每小时检查是否满7天，首次有数据且无自动备份时创建自动快照；备份后恢复原运行状态。自动备份保留5份，升级前备份保留2份，首次安装/迁移快照另保留5份。备份在管理目录 `backups/`，不混入数据目录。

升级先下载和验签，不影响当前服务；准备完成后停服、备份、切换版本并检查健康。失败则停止候选进程，恢复完整数据快照和旧版本指针，按原运行状态重启。若恢复本身失败返回 `ROLLBACK_FAILED`，保留备份供人工处置。

卸载默认保留数据、认证配置、备份。彻底清除须先请求120秒、一次性的确认令牌，再显式提交 `purgeData=true` 和 `confirmationToken`；将删除程序、当前数据、认证配置及备份。页面不提供自动清除入口。

## API

所有接口继续受 Java 现有回环地址、Origin 白名单和 Bearer 服务令牌校验保护。

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| GET | `/api/integrations/electerm/status` | 程序/进程/页面状态与可信 URL |
| POST | `/api/integrations/electerm/install` | 空请求从受控源安装；JSON `offlinePackage` 引用 inbox 包；multipart `package` 导入本机 ZIP |
| POST | `/api/integrations/electerm/start` | 幂等启动 |
| POST | `/api/integrations/electerm/stop` | 停止自有进程树 |
| POST | `/api/integrations/electerm/restart` | 重启 |
| GET | `/api/integrations/electerm/logs` | 最近200条生命周期诊断 |
| POST | `/api/integrations/electerm/purge-confirmation` | 获取彻底清除的一次性确认令牌 |
| POST | `/api/integrations/electerm/uninstall` | 默认保留数据的卸载，或二次确认后清除 |

## 相对上游的修改

1. 添加 ToolHelper 生产启动适配器、实例健康标识和父进程监测。
2. 通过上游实际支持的环境变量禁用本地终端、开启自身认证、限制回环监听。
3. 客户端应用升级检查改为禁用，运行时动态下载模块入口禁用；应用更新统一由 Java 管理。
4. 禁用上游 URL 访问日志和 electron-log 文件/控制台输出，避免 WebSocket JWT 或错误参数落入 ToolHelper 日志。ToolHelper 只记录固定生命周期诊断；用户主动启用的 Electerm 会话录制仍由 Electerm 管理。
5. 保留数据库 schema 迁移；必须在完成升级/迁移前快照后才启动候选版本。
6. 保留 `app/LICENSE`、`runtime/LICENSE`、依赖目录内许可证和 `NOTICES.md`。

## 开发验证范围

Java 单元/组件测试使用临时目录、合成签名包和内存生命周期替身，覆盖验签、ZIP 防护、哈希与架构、备份与回滚、幂等/串行控制、清除确认及接口映射。前端测试覆盖回环 URL 与错误码传递，另外执行类型检查、生产构建和契约检查。

没有启动真实 Electerm，没有执行首次启动耗时、干净机器、浏览器版本、登录、SSH/SFTP/RDP/VNC、端口可达性、真实退出计时或真实版本升级验收。阶段方案中的验收清单保持未勾选。
