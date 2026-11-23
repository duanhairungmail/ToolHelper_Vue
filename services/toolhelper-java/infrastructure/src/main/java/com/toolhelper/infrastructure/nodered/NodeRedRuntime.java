package com.toolhelper.infrastructure.nodered;

import com.toolhelper.application.nodered.NodeRedContracts.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public final class NodeRedRuntime implements Host {
    private final NodeRedProperties properties;
    private final NodeRedPackageService packages;
    private final NodeRedProcessRegistry processes;
    private final NodeRedBackupService backups;
    public NodeRedRuntime(NodeRedProperties properties, NodeRedPackageService packages, NodeRedProcessRegistry processes, NodeRedBackupService backups) {
        this.properties = properties; this.packages = packages; this.processes = processes; this.backups = backups;
    }
    private void claim() {
        try { processes.claimOwnership(); }
        catch (IOException error) { throw new NodeRedException("INSTANCE_IN_USE", "无法获取 NodeRed 目录管理锁", error); }
    }
    @Override public Release current() { return packages.current(); }
    @Override public Release prepare(InstallRequest request) { claim(); processes.log("开始校验受控发布包"); Release release = packages.prepare(request); processes.log("发布包验签及哈希校验通过，版本 " + release.version()); return release; }
    @Override public void activate(Release release) { claim(); packages.activate(release); }
    @Override public Status status() { return processes.status(); }
    @Override public Status start() { claim(); return processes.start(); }
    @Override public Status stop() { claim(); return processes.stop(); }
    @Override public String backup(String reason) { claim(); String id = backups.backup(reason); processes.log("完成 Node-RED 数据备份，类型 " + reason); return id; }
    @Override public void restore(String backupId) { claim(); backups.restore(backupId); processes.log("已从更新前快照恢复 Node-RED 数据"); }
    @Override public boolean automaticBackupDue() { return backups.automaticBackupDue(); }
    @Override public List<String> logs() { return processes.logs(); }
    @Override public void uninstall() {
        claim(); processes.stop();
        try {
            packages.activate(null);
            NodeRedFiles.removeTree(properties.home(), properties.home().resolve("versions"));
            processes.log("已卸载 Node-RED 程序，数据、认证配置及备份已保留");
        } catch (IOException error) { throw new NodeRedException("UNINSTALL_FAILED", "NodeRed 卸载失败，请检查文件占用和权限", error); }
    }
}
