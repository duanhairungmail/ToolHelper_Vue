# Node-RED 集成与发布

阶段7实施说明。以下为构建与接入说明，不表示已执行或通过阶段验收。

## 固定版本与构建

组合为 Node.js `24.19.0` Windows X64、Node-RED `5.0.10`、`node-red-node-serialport` `2.0.3`、`node-red-contrib-modbus` `5.62.1`。`package-lock.json` 固定完整依赖树和 npm registry integrity，`source-lock.json` 固定锁文件、官方 Node ZIP 和 node.exe 的 SHA-256。构建工具只使用 ZIP 内的 npm；目标机器只执行运行包内的 node.exe，不要求全局 Node/npm。

构建需要 Windows X64、开发机 Node.js、Windows tar 和 Java 21 的 jar。示例中的路径须替换为发布方自己的路径：

```powershell
node installer/nodered/build-release.mjs `
  --signing-key D:/private/toolhelper-release-ed25519.pem `
  --output installer/nodered/artifacts `
  --main-package-metadata D:/release/ToolHelper/metadata/nodered `
  --jar D:/DevlopEnvironment/java21/bin/jar.exe
```

可用 `--node-archive <本地ZIP>` 复用已缓存的官方 Node ZIP，仍执行固定哈希校验。构建机运行 `npm ci --omit=dev --ignore-scripts --no-audit --no-fund`，不执行依赖安装脚本；检查串口原生模块可加载、固定组件版本一致，再生成完整文件索引。构建不启动 Node-RED 或访问设备。

输出 `payload.zip`、`manifest.json`、`manifest.sig` 和 `nodered-runtime-5.0.10-offline.zip`。Ed25519 签名覆盖清单原始字节，清单覆盖 ZIP、node.exe、锁文件和逐文件索引。Java 在解压前验签，并在每次启动前重新校验文件。ZIP 路径、Windows 保留名称、重复路径、大小/条目数、PE X64 架构均受检查。升级拒绝版本降级。节点包与运行包通过清单 `kind` 区分，不能混用。

主安装包只集成 `--main-package-metadata` 导出的清单、签名和 SPKI 公钥，禁止包含 payload、node.exe 或 node_modules。本仓库当前没有总安装器产物；该选项提供后续总安装器的元数据输入。生产签名私钥、公钥和实际 HTTPS 发布目录须由发布方提供，本实施不生成或自动信任生产密钥。构建产物与临时工作树均被 Git 忽略。

Node-RED 与串口节点为 Apache-2.0；Modbus 节点的实际包元数据为 BSD-3-Clause，已纠正原清单中的 MIT 占位。运行包保留 Node LICENSE、Node-RED LICENSE、依赖包内许可证及 NOTICES。版本来源为官方 Node 分发清单和锁定包的 npm registry/package.json；这不替代发布前的完整许可证核对。

## 生效配置与登录

Java 当前进程的环境变量或 Spring 本机部署配置生效，网页不能修改信任根或传入任意安装命令：

| 环境变量 | 用途/默认值 |
| --- | --- |
| `TOOLHELPER_NODERED_PUBLIC_KEY` | 发布方独立交付的 SPKI PEM 文件路径；未配置则拒绝在线和离线安装 |
| `TOOLHELPER_NODERED_FEED` | 可选 HTTPS 目录，以 `/` 结尾；禁止 URL 凭据、查询、片段、重定向 |
| `TOOLHELPER_NODERED_PORT` | `1880`，只监听 `127.0.0.1`；冲突时不终止未知进程 |
| `TOOLHELPER_NODERED_HOME` | `%LOCALAPPDATA%\ToolHelper\integrations\nodered`，版本、所有权锁、进程记录、私有认证和审计 |
| `TOOLHELPER_NODERED_DATA` | `%LOCALAPPDATA%\ToolHelper\data\nodered`，独立 userDir |
| `TOOLHELPER_NODERED_BACKUPS` | `%LOCALAPPDATA%\ToolHelper\backups\nodered` |
| `TOOLHELPER_NODERED_SECRET` | `%LOCALAPPDATA%\ToolHelper\secrets\nodered-credential-secret.bin`；Java 配置中此值是文件路径 |
| `TOOLHELPER_NODERED_INBOX` | HOME 下 inbox，离线导入暂存 |
| `TOOLHELPER_NODERED_NODE_ALLOWLIST` | 经审核节点 payload SHA-256，逗号分隔；默认无白名单 |

HOME、DATA、BACKUPS、密钥目录须相互独立。首次成功准备启动时，生成 `home/private/admin.properties`，账号 `toolhelper`，随机高熵密码；文件及父目录限制为当前用户访问。用户读取其中 password，在系统浏览器的 Node-RED 登录页输入；不把密码写入 URL、普通日志或 ToolHelper 数据库。adminAuth 使用上游支持的自定义认证函数与 PBKDF2，未配置匿名默认用户。

credentialSecret 为32字节随机值，以 Windows DPAPI CurrentUser 加密保存。Java 通过绝对路径调用 Windows PowerShell，明文只通过辅助进程 stdin/stdout 和 Node 子进程环境传递，不进入命令参数。已有任何用户数据但密钥丢失时禁止生成新密钥，也禁止创建缺少密钥的非空备份；应恢复同机同用户 DPAPI 备份。不能将密钥文件直接迁移给其他 Windows 用户使用。内部传给 Node 的同名 `TOOLHELPER_NODERED_SECRET` 环境变量为解密值；Java 环境中为路径，二者通过独立 ProcessBuilder 环境隔离。

关闭 Projects、Function 外部模块安装、Palette 安装/上传/更新和自动安装；允许已受控安装的节点正常加载。流程代码/已批准脚本仍以当前用户权限运行，不能把审阅审批等同于操作系统沙箱。

## 生命周期、数据与诊断

Java 直接执行包内 `node.exe --require ./coordinator-preload.cjs node_modules/node-red/red.js --settings settings.cjs --userDir <独立目录>`，不经 shell。仅传递必要环境，专用串口令牌不能访问其他 Java 接口。目录锁和串行生命周期操作防止多实例；保存 PID、启动时间、版本、端口、实例及所属 Java PID。健康检查核对实例和版本以及编辑器页面；45秒内不就绪则停止本次进程。健康标识不代替匿名访问或认证的阶段验收。

停服不检查流程是否活动；Java 正常退出回收已登记进程树，preload 监测 Java 父进程异常退出并按自身 PID 终止进程树。下次取得目录锁后，仅在历史 PID、启动时间、程序路径、userDir 及已退出所属进程均可验证时清理孤立进程；无法确认时拒绝接管，PID 已复用时不触碰新进程。实际10秒退出窗口需后续验收确认。

升级先准备/验签，再停服、备份、切换、健康检查；失败恢复旧版本和完整数据。节点变更前也停服和备份，失败恢复；恢复前快照使用独立 `restore` 类别，避免清理掉用户选择的旧备份。每小时检查7天自动备份周期：自动保留7份、升级前2份，手动/首次迁移与节点变更分别7份、恢复前2份。快照包含 userDir 和 DPAPI 密钥密文，恢复禁止覆盖不同密钥。卸载只删除运行时，保留流程、密钥、认证及备份。

Vue 默认展开底部日志，可折叠/清空，通过带服务令牌的 SSE 展示有界生命周期诊断，前端最多500行、Java最多200条。原始上游/脚本 stdout、stderr 不转发，避免输出流程凭据；执行失败显示稳定错误码，脚本结果写入审计。浏览器地址只能来自 Java 返回的实际端口，并检查精确 `http://127.0.0.1:<port>/`。没有目标IP、iframe 或公共演示站点。

## 第三方节点包

节点包必须由可信发布方预构建，目标机器不运行 npm。包内 app 包含 package.json、package-lock.json、完整 node_modules 和 review.json，可选 install/*.cjs。审阅材料须覆盖所有传递依赖、原生二进制和包生命周期脚本，审核时间不得超过30天；维护状态必须 active、已知漏洞必须 none。以上状态来自发布方签名的审核结论，运行端不以联网扫描替代发布方审核，也不会自行断言任何包当前无漏洞。

每个包使用相对 app 路径区分重复嵌套依赖；示例结构如下，哈希必须由实际文件计算，审核结论必须由审阅人确认，不能直接使用占位值：

```json
{
  "packages": [{
    "path": "node_modules/example", "name": "example", "version": "1.0.0",
    "sha256": "<package.json 的64位小写SHA-256>", "license": "MIT",
    "maintenance": "active", "vulnerabilities": "none", "installScripts": {}
  }],
  "nativeDependencies": ["node_modules/example/prebuilds/win32-x64/native.node"],
  "scripts": [{"path": "install/setup.cjs", "source": "<完整脚本文本>"}],
  "permissions": ["当前用户文件读写"], "networkAccess": ["具体目标与用途，或空数组"],
  "rollbackImpact": "恢复 userDir；脚本造成的外部文件/网络副作用须人工回退",
  "reviewedAt": "<真实审核时间，ISO-8601>"
}
```

installScripts 须与每个 package.json 的 preinstall/install/postinstall/prepare 精确一致；这些 npm 脚本只披露，**不自动执行**。确需本机安装动作时，将入口显式改为可审阅的 install/*.cjs；只有 review.scripts 中完整内容匹配的入口，才会在最终确认后直接交给受控 node.exe。原生文件声明为 node_modules 下全部 .node/.dll/.exe/.so/.dylib 的相对路径。构建方式：

```powershell
node installer/nodered/build-release.mjs `
  --signing-key D:/private/toolhelper-release-ed25519.pem `
  --node-bundle D:/reviewed/example-app `
  --bundle-version 1.0.0 `
  --output installer/nodered/artifacts/example-nodes
```

导入仅审阅 → 命中部署白名单或填写审批人/理由 → 用户明确确认才安装。审阅令牌10分钟、单次使用，最多20个待审包。取消最终确认不触发停服、备份、复制或脚本；审阅导入本身会生成临时文件与审计记录。来源不明、验签失败、审阅缺项、过期、未审批或未确认都拒绝执行。脚本最多120秒，共享退出清理和父进程看护；安装后再次拒绝链接/越界文件树，脚本外部副作用不能自动回滚。审核不代表脚本被隔离执行。

`home/audit/nodes.jsonl` 记录时间、审阅ID、包名/版本/package.json哈希、payload哈希、审批人/理由、确认、每个脚本开始/成功/失败及安装/回退结果。审计不可写时阻断后续操作。已签名但未激活的版本保留在受管 versions 中供诊断，卸载运行时清理；不会自动删除用户其他目录。

## 串口协调边界

Java 统一协调 COM 号，规范化 `com3` 与 `\\.\COM3`；租约绑定 PID+启动时间，进程活着不因超时被抢占。Node-RED preload 包装每份 @serialport/stream：先取租约再打开，物理句柄关闭后才释放；冲突显示占用方/端口/释放方式，打开失败释放、协调期间取消不再打开。

C# 已接同一Java接口并提供真实预留/释放API；Launcher 注入实际Java动态端口。**现有 SerialDebuggerView 仍是演示页，本阶段没有把串口调试器改造成真实串口收发工具。** 后续实际C#物理串口迁移必须复用 SerialPortLeaseClient，遵守“申请→打开；关闭→释放”，禁止直接绕过协调器打开。故“C#与Node-RED真实同时打开设备”的端到端闭环目前不能宣称完成，对应方案项保留未勾。此限制不是设备测试通过与否的问题。

## API

统一受回环、Origin白名单和Bearer保护，返回 ApiResponse；专用Node令牌仅可用于串口租约路径。

| 方法 | 路径（Node-RED 前缀为 `/api/integrations/nodered`） | 参数/用途 |
| --- | --- | --- |
| GET | `/status` | 安装、版本、PID、端口、可信URL |
| POST | `/install`、`/updates` | JSON `{confirmed:true,offlinePackage?:"inbox文件名"}` 或 multipart package+confirmed |
| POST | `/start`、`/stop`、`/restart` | 受控生命周期 |
| DELETE | `/runtime?confirmed=true` | 保留数据卸载 |
| GET | `/events` | SSE，Last-Event-ID，最近200条内重放 |
| POST | `/backups` | 停服生成备份并恢复原运行状态，返回备份名 |
| POST | `/restore` | `{id,confirmed:true}`，恢复失败尝试回退 |
| POST | `/nodes/review` | multipart package+confirmed，仅验签审阅 |
| POST | `/nodes/{id}/approve` | `{reviewer,reason}`，人工审批 |
| POST | `/nodes/{id}/install` | `{confirmed:true}`，一次性执行 |
| DELETE | `/nodes/{id}` | 取消待审申请 |
| GET/POST/DELETE | `/api/internal/v1/serial/leases`、`/{id}` | 列表/`{port,pid}`申请/释放；网页只读，Node专用令牌与C#内部令牌可变更 |
| POST/DELETE | C# `/api/local/serial/reservations`、`/{id}` | `{port}`预留/释放，不打开物理设备 |

## 实施检查边界

开发检查包括 Vue 类型与构建、Node脚本语法、Java/Launcher和C#编译、隔离单测、契约检查及构建机打包。Java单测使用合成ZIP、临时目录、进程身份自查及替身，不启动真实Node-RED。Node单测验证实际settings和preload，边界IO用最小替身。

未执行方案的阶段验收：干净Windows、45秒首次启动、编辑器流程、设备/模拟器、10秒退出、匿名访问、真实节点脚本安装、真实凭据迁移、24小时运行均留待用户后续指示。生产受控源/公钥未提供，当前默认拒绝安装；不能把开发签名当作官方生产发布。
