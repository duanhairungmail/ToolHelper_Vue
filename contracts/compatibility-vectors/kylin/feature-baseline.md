# KylinOS 功能与命令基线（源码记录）

状态：implementation-in-progress。以下内容来自原始 `Views/Security/KylinOsDeployView.cs`，尚未在目标 KylinOS X86_64 镜像执行，也不代表阶段验收通过。

## 连接前置

- 基类：`SshToolBaseView`，同时建立 SSH 与 SFTP 会话。
- 默认 SSH 端口：22；连接参数和密码由用户输入。
- 提权路径：root 或密码 sudo；源码通过 SSH stdin 传递 sudo 密码。
- 目标架构检查：后续资源部署根据 `uname -m` 选择 x11vnc 二进制。

## 七项功能

| Tab | 功能 | 源码记录的扫描/变更对象 |
| --- | --- | --- |
| 0 | 系统激活 | `kylin_activation_check 2>&1`，只读检查 |
| 1 | 定时重启 | `/usr/local/bin/scheduled-reboot.sh`、`/usr/local/bin/clear-autologin.sh`、`/etc/xdg/autostart/clear-autologin.desktop`、`/etc/cron.d/auto-reboot`、`/etc/sudoers.d/auto-reboot` |
| 2 | 日志优化 | `/usr/local/bin/clean-logs.sh`、`/etc/cron.d/clean-logs`、`/var/log/clean-logs.log` |
| 3 | VNC Server | `/usr/local/bin/x11vnc`、`/etc/x11vnc.passwd`、`/etc/systemd/system/x11vnc.service`，默认端口 5901 |
| 4 | PostgreSQL/openGauss 连接 | `/data/usershare/firestation/db/opengauss/data/single_node/postgresql.conf`、`pg_hba.conf`，备份目录 `backup_conf` |
| 5 | 漏洞扫描 | `dpkg -l kylin-offline-upgrade`，补丁目录 `plugins/Security patch/` |
| 6 | 系统优化 | `systemctl` 服务、XDG autostart、D-Bus 激活文件、残留进程及 14 项优化定义 |

## 变更流程记录

源码中已有扫描、SFTP 上传、sudo 命令、验证和部分卸载流程；阶段 0 只固化命令/路径清单。后续 Java 实现必须将其重构为：`SCAN_CURRENT -> PREFLIGHT -> REQUEST_CONFIRMATION -> BACKUP -> APPLY_WHITELISTED_STEPS -> VERIFY -> ROLLBACK_IF_FAILED -> VERIFY_ROLLBACK -> AUDIT`。

## 待补测试环境

- Kylin-Desktop-V10-SP1-2503-HWE-Release-20250430-X86_64
- root、密码 sudo、无 sudo、错误凭据账号
- 可恢复虚拟机快照
- 连接、扫描、部署、卸载、恢复的执行前后证据
