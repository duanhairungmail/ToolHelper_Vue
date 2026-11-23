# 阶段 7：集成 Node-RED

## 阶段目标

由 Java 管理独立 Node.js + Node-RED 服务，系统浏览器打开原生编辑器；首次使用受控下载，ToolHelper 退出时无条件停止全部流程。

## 周期与前置条件

- 周期：6–8 个工作日。
- 阶段 1 生命周期基础和阶段 0 许可证清单已验收。
- 锁定 Node.js、Node-RED、`node-red-node-serialport`、`node-red-contrib-modbus` 版本组合。

## 代码结构

```text
integration/nodered/
├─ api/NodeRedController.java
├─ application/NodeRedLifecycleService.java
├─ infrastructure/
│  ├─ NodeRedPackageService.java
│  ├─ NodeRedProcessRegistry.java
│  ├─ NodeRedHealthService.java
│  ├─ NodeRedSettingsService.java
│  ├─ NodeApprovalService.java
│  └─ NodeRedBackupService.java
└─ NodeRedProperties.java
```

## 实施任务

### 1. 首次受控安装

- [x] 主安装包不预置 Node-RED 运行时，只包含签名清单和校验公钥。
- [x] 用户确认后下载固定版本组合，校验签名、SHA-256、版本和架构。
- [x] 无网络时允许导入 ToolHelper 官方构建的离线包。
- [x] 不在目标机器执行任意在线 `npm install`。

### 2. 安全配置

```js
module.exports = {
  uiHost: '127.0.0.1',
  uiPort: Number(process.env.TOOLHELPER_NODERED_PORT),
  editorTheme: { projects: { enabled: false } }
}
```

- [x] 开启 `adminAuth`，禁止匿名编辑器访问。
- [x] 生成稳定 `credentialSecret`，不得放入进程参数或普通日志。
- [x] 禁止 Node-RED 自更新和 Palette Manager 任意联网安装。
- [x] 仅监听回环地址，移除原“目标 IP”输入。

### 3. 生命周期

```text
GET    /api/integrations/nodered/status
POST   /api/integrations/nodered/install
POST   /api/integrations/nodered/start
POST   /api/integrations/nodered/stop
POST   /api/integrations/nodered/restart
DELETE /api/integrations/nodered/runtime
GET    /api/integrations/nodered/events
POST   /api/integrations/nodered/updates
```

- [x] 使用固定 `node.exe` 直接启动 `red.js`，不经过 shell。
- [x] 记录 PID、版本、端口和实例标识。
- [x] ToolHelper 退出时，不论流程状态均停止 Node-RED 进程树。
- [x] 异常退出后，下次启动只清理本产品登记的孤立进程。

### 4. 第三方节点治理

- [x] 默认包含核心、串口、Modbus 节点。
- [x] 允许导入含原生二进制或安装脚本的节点。
- [x] 节点必须命中白名单或完成人工审批。
- [x] 安装前展示脚本、原生依赖、权限、网络访问和回滚影响并确认。
- [x] 校验包完整性、许可证、维护状态和已知漏洞。
- [x] 来源不明、无法审阅、校验失败的脚本默认拒绝。
- [x] 审计包名、版本、SHA-256、审批、确认和执行结果。

### 5. 串口协调

- [x] Java 维护串口占用登记，C# 与 Node-RED 使用同一协调接口。
- [ ] 同一串口不得被 ToolHelper C# 与 Node-RED 同时打开。
- [x] 冲突时显示占用方、端口和释放方式，不静默抢占。

### 6. 数据、密钥与备份

```text
userDir: %LOCALAPPDATA%\ToolHelper\data\nodered\
backup:  %LOCALAPPDATA%\ToolHelper\backups\nodered\
secret:  %LOCALAPPDATA%\ToolHelper\secrets\nodered-credential-secret.bin
```

- [x] `credentialSecret` 为 32 字节随机值，使用 DPAPI CurrentUser 加密保存。
- [x] 每 7 天、升级前、节点变更前备份。
- [x] 保留 7 份自动备份和 2 份升级前备份。
- [x] 密钥缺失时禁止生成新密钥覆盖旧凭据。

### 7. Vue 页面

- [x] 展示安装、版本、端口、状态、启动并打开、停止、重启、更新。
- [x] 系统默认浏览器打开实际回环地址。
- [x] 日志默认展开、可收起、可清空并填满底部空间。

## 本轮实施记录（2026-11-23，非阶段验收）

- 已完成下方实施清单中的28项代码/构建接入，1项保留未勾；所有阶段验收项均未执行、未勾选。
- 按现有 api/application/infrastructure 分层增加 Node-RED 模块，没有另起平行架构。Vue 已从 mock 切换到真实 Java API。
- 固定组合：Node.js 24.19.0 / Node-RED 5.0.10 / node-red-node-serialport 2.0.3 / node-red-contrib-modbus 5.62.1；完整依赖锁及构建工具在 installer/nodered。
- 主包仅元数据的导出功能已实现；本地开发签名打包成功，元数据目录只有 manifest.json、manifest.sig 和 release-public.pem。总安装器组装属于阶段8，本轮未声称已有正式安装器。
- 未提供生产发布源、公钥和签名私钥。默认拒绝安装，不生成虚构生产信任根；开发签名包不等同于官方生产发布。
- 第三方节点采用发布方签名的预构建依赖树和30天内审阅材料。校验所有传递包、原生文件、生命周期脚本披露及完整文件哈希；白名单/人工审批与最终确认分开，审计记录审批理由及执行结果。维护状态/已知漏洞是发布方审核结论，不是运行端实时联网扫描结果。
- C#串口边界：总体方案第7节将串口/Modbus实际工具列为后续接入，本轮未扩展迁移。Java独占租约、C#真实预留/释放API与客户端、Node-RED物理打开前申请/关闭后释放均已实现；但现有C#串口页仍是mock，没有真实物理打开路径，因此“同一串口不得同时打开”的端到端闭环保留未勾，不能用租约单测替代。
- 凭据使用稳定32字节随机密钥，Windows DPAPI CurrentUser保护；快照含密钥密文。已有数据但密钥缺失时禁止重建。自动保留7份、升级前2份；手动/迁移和节点变更分别7份，恢复前安全快照另保留2份。
- 开发检查：Java离线单测60项通过（含Node-RED/串口相关23项）、bootJar与Launcher编译通过；前端单测7项、契约检查3项、类型检查与Vite构建通过；C#构建0错误/0警告；Node脚本语法与本地开发签名打包通过。Vite保留主chunk大于500kB的提示，不在本轮扩展优化。
- 未启动真实Node-RED，未执行干净机器、45秒首次启动、编辑器流程、设备/模拟器、10秒退出、匿名访问、真实节点脚本安装、真实凭据升级恢复或24小时运行验收。
- 详细构建、信任配置、API、节点包审阅格式及限制见项目 installer/nodered/README.md。仓库根目录保存本方案同步副本，以便随代码提交追踪。

## 阶段验收

- [ ] 无全局 Node.js/npm 的机器可完成受控安装和启动。
- [ ] 首次启动 45 秒内进入 `RUNNING`，重复点击不产生第二实例。
- [ ] 可创建、部署、停止和重新部署基础流程。
- [ ] 串口、Modbus 各通过真实设备或受控模拟器流程。
- [ ] ToolHelper 退出后 10 秒内 Node-RED 进程树退出、端口释放，即使存在运行或定时流程。
- [ ] Node-RED 只监听 `127.0.0.1`，未认证用户无法访问编辑器。
- [ ] 未审批的原生节点或安装脚本不能执行。
- [ ] 取消安装确认后没有脚本、子进程或文件变更。
- [ ] 节点审计包含包名、版本、哈希、审批和执行结果。
- [ ] C# 占用串口时 Node-RED 明确阻断，反向亦然。
- [ ] 升级、备份、恢复后既有流程凭据仍可解密。
- [ ] 连续运行 24 小时无阻断性内存、日志或流程异常。

## 退出条件

Node-RED 生命周期、节点治理、串口协调、升级恢复和退出停止全部闭环。

## 下一步

进入《阶段8-启动器安全发布与验收.md》。
