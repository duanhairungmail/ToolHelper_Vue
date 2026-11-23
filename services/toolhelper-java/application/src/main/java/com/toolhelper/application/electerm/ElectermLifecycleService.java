package com.toolhelper.application.electerm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.toolhelper.application.electerm.ElectermContracts.*;

/** 所有生命周期变更串行执行；更新失败恢复程序指针与更新前的数据快照。 */
public final class ElectermLifecycleService {
    private final Host host;
    private PurgeConfirmation purgeConfirmation;

    public ElectermLifecycleService(Host host) { this.host = host; }
    public synchronized Status status() { return host.status(); }
    public synchronized List<String> logs() { return host.logs(); }
    public synchronized Status start() { return host.start(); }
    public synchronized Status stop() { return host.stop(); }
    public synchronized Status restart() { host.stop(); return host.start(); }

    public synchronized Status install(InstallRequest request) {
        Release candidate = host.prepare(request); // 下载、验签、解压失败不得影响正在运行的版本。
        Release previous = host.current();
        boolean running = "RUNNING".equals(host.status().state());
        host.stop();
        String backup;
        try { backup = host.backup(previous == null ? "migration" : "upgrade"); }
        catch (RuntimeException error) { if (running) host.start(); throw error; }
        try {
            host.activate(candidate);
            Status ready = host.start();
            if (!"RUNNING".equals(ready.state())) throw new ElectermException("START_TIMEOUT", "候选版本健康检查未通过");
            return running ? ready : host.stop();
        } catch (RuntimeException error) {
            try {
                host.stop();
                host.restore(backup);
                host.activate(previous);
                if (running) host.start();
            } catch (RuntimeException rollback) {
                throw new ElectermException("ROLLBACK_FAILED", "更新失败且恢复失败，请保留备份并查看诊断", rollback);
            }
            throw new ElectermException("UPDATE_ROLLED_BACK", "新版本未就绪，已恢复更新前的程序和数据", error);
        }
    }

    public synchronized PurgeConfirmation requestPurge() {
        purgeConfirmation = new PurgeConfirmation(UUID.randomUUID().toString(), Instant.now().plusSeconds(120));
        return purgeConfirmation;
    }

    public synchronized Status uninstall(boolean purge, String token) {
        if (purge) {
            PurgeConfirmation expected = purgeConfirmation;
            purgeConfirmation = null;
            if (expected == null || !expected.token().equals(token) || expected.expiresAt().isBefore(Instant.now()))
                throw new ElectermException("PURGE_CONFIRMATION_REQUIRED", "彻底清除需先获取并提交一次性确认令牌");
            host.stop();
            host.backup("migration");
        }
        host.uninstall(purge);
        return host.status();
    }

    public synchronized void automaticBackup() {
        if (!host.automaticBackupDue()) return;
        boolean running = "RUNNING".equals(host.status().state());
        host.stop();
        try { host.backup("automatic"); }
        finally { if (running) host.start(); }
    }
}
