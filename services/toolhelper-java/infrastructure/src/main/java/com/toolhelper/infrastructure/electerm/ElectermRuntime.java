package com.toolhelper.infrastructure.electerm;

import com.toolhelper.application.electerm.ElectermContracts.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public final class ElectermRuntime implements Host {
    private final ElectermProperties properties;
    private final ElectermPackageService packages;
    private final ElectermProcessRegistry processes;
    private final ElectermBackupService backups;
    public ElectermRuntime(ElectermProperties properties, ElectermPackageService packages, ElectermProcessRegistry processes, ElectermBackupService backups) {
        this.properties = properties; this.packages = packages; this.processes = processes; this.backups = backups;
    }
    private void claim() {
        try { processes.claimOwnership(); }
        catch (IOException error) { throw new ElectermException("INSTANCE_IN_USE", "无法获取 Electerm 目录管理锁", error); }
    }
    @Override public Release current() { return packages.current(); }
    @Override public Release prepare(InstallRequest request) { claim(); processes.log("开始校验受控发布包"); Release release = packages.prepare(request); processes.log("发布包验签及哈希校验通过，版本 " + release.version()); return release; }
    @Override public void activate(Release release) { claim(); packages.activate(release); }
    @Override public Status status() { return processes.status(); }
    @Override public Status start() { claim(); return processes.start(); }
    @Override public Status stop() { claim(); return processes.stop(); }
    @Override public String backup(String reason) { claim(); String id = backups.backup(reason); processes.log("完成 Electerm 数据备份，类型 " + reason); return id; }
    @Override public void restore(String backupId) { claim(); backups.restore(backupId); processes.log("已从更新前快照恢复 Electerm 数据"); }
    @Override public boolean automaticBackupDue() { return backups.automaticBackupDue(); }
    @Override public List<String> logs() { return processes.logs(); }
    @Override public void uninstall(boolean purgeData) {
        claim(); processes.stop();
        try {
            packages.activate(null);
            ElectermFiles.removeTree(properties.home(), properties.home().resolve("versions"));
            if (purgeData) {
                ElectermFiles.removeTree(properties.data().getParent(), properties.data());
                ElectermFiles.removeTree(properties.home(), properties.home().resolve("private"));
                ElectermFiles.removeTree(properties.home(), properties.backups());
            }
            processes.log(purgeData ? "已二次确认并清除 Electerm 程序、数据、认证配置及备份" : "已卸载 Electerm 程序，数据、认证配置及备份已保留");
        } catch (IOException error) { throw new ElectermException("UNINSTALL_FAILED", "Electerm 卸载失败，请检查文件占用和权限", error); }
    }
}
