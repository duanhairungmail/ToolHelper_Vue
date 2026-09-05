# 阶段 2：实现 SQLite 数据层与数据库工作台

## 阶段目标

由 Java 独占 ToolHelper 内部 SQLite，并交付 Vue 3 + Java/JDBC 的用户 SQLite 工作台。内部库和用户库必须严格隔离。

## 周期与前置条件

- 周期：7–10 个工作日。
- 阶段 1 已验收。
- 锁定 SQLite JDBC、Spring Data JDBC、Flyway、CodeMirror 6、VXE-Table 开源版本。

## 数据源结构

```java
@Bean("internalDataSource")
DataSource internalDataSource(InternalDbProperties properties) { /* toolhelper.db */ }

// 用户连接由会话工厂按文件建立，不注册为内部 Repository 默认数据源。
interface UserDatabaseSessionFactory {
    UserDatabaseSession open(Path databaseFile, char[] password);
}
```

```text
database/
├─ internal/
│  ├─ InternalDataSourceConfig
│  └─ repository/
├─ workspace/
│  ├─ api/
│  ├─ application/
│  ├─ domain/
│  └─ infrastructure/sqlite/
└─ security/
   ├─ DatabasePathPolicy
   └─ SqlRiskClassifier
```

## 实施任务

### 1. 内部数据库

- [x] 创建 `%LOCALAPPDATA%\ToolHelper\data\toolhelper.db`。
- [x] 启用 WAL、外键、`busy_timeout=5000`、`synchronous=NORMAL`。
- [x] 使用 Flyway 创建任务、群 Ping、审计、集成状态表。
- [x] 迁移失败阻止服务就绪，禁止静默重建。

```sql
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
PRAGMA busy_timeout=5000;
```

### 2. 用户 SQLite 会话

- [x] 支持创建、选择、打开、测试和关闭 `.db/.sqlite/.sqlite3`。
- [x] 对路径做规范化、扩展名、大小、权限和符号链接检查。
- [x] 连接池按用户数据库隔离，闲置后关闭。
- [x] 加密口令每次输入，仅在当前连接会话内存中存在。
- [x] 拒绝用户库路径指向 `toolhelper.db`、备份和内部目录。

### 3. 元数据与查询 API

```text
POST   /api/database/sessions
DELETE /api/database/sessions/{sessionId}
GET    /api/database/sessions/{sessionId}/metadata
POST   /api/database/sessions/{sessionId}/queries
POST   /api/database/queries/{taskId}/cancel
GET    /api/database/queries/{taskId}/events
POST   /api/database/queries/{taskId}/export
```

- [x] 元数据树懒加载表、视图、列、索引和触发器。
- [x] 查询返回任务 ID，支持取消、超时、分页和流式导出。
- [x] 用户 SQL 使用 JDBC Statement/PreparedStatement，不经 ORM 改写。
- [x] 拦截访问内部路径的 `ATTACH`；高风险写操作二次确认。

### 4. Vue 工作台

```text
DatabaseWorkspaceView
├─ DatabaseConnectionSidebar
├─ DatabaseObjectExplorer
├─ QueryTabBar
├─ SqlEditor                 # CodeMirror 6
├─ QueryResultGrid           # VXE-Table
├─ ResultDetailPanel
└─ DatabaseLogDrawer
```

- [x] 多标签 SQL、选中执行、格式化、取消和状态恢复。
- [x] VXE-Table 服务端分页，10 万行不一次进入浏览器内存。
- [x] 支持复制、筛选、排序、CSV/Excel 导出。
- [x] 数据编辑仅允许唯一主键结果集，提交前展示变更集。

### 5. 审计与清理

- [x] 内部库只记录连接生命周期、SQL 类型、耗时、行数和结果，不记录口令和完整敏感 SQL。
- [x] 临时导出文件在下载完成、取消或下次启动时清理。
- [x] 用户关闭连接后清空口令字符数组并释放连接池。

## 阶段验收

- [ ] 可在 Vue 中完成创建/打开 SQLite、浏览对象、执行 SQL、查看结果和导出。
- [ ] 用户 SQL 无法通过路径、`ATTACH`、内部表名或连接复用访问 `toolhelper.db`。
- [ ] 内部与用户 DataSource、连接池、Repository 和 API 在代码层可明确区分。
- [ ] 取消查询后 JDBC Statement 实际取消，连接可安全复用或回收。
- [ ] `DELETE/UPDATE/DROP` 等危险操作必须二次确认。
- [ ] 10 万行查询采用分页，浏览器内存不随总行数线性增长。
- [ ] 连接口令不出现在内部库、日志、URL、浏览器存储或进程参数。
- [ ] SQLite 锁冲突、只读文件、损坏文件和磁盘不足均返回稳定错误码。
- [ ] VXE-Table 使用开源功能即可完成首期验收，许可证进入第三方清单。
- [ ] Java 异常退出时未提交事务由驱动回滚，重启后数据库完整性检查通过。

## 退出条件

用户 SQLite 工作台可替代 DBX 的首期学习场景，同时内部 ToolHelper 数据库边界不可绕过。

## 下一步

进入阶段 3；数据库高级能力另行排期，不阻断首期。
