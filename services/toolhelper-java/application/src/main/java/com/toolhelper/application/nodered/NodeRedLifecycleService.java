package com.toolhelper.application.nodered;

import java.util.List;
import static com.toolhelper.application.nodered.NodeRedContracts.*;

/** 所有生命周期变更串行执行；更新失败恢复程序指针与更新前的数据快照。 */
public final class NodeRedLifecycleService {
    private final Host host;

    public NodeRedLifecycleService(Host host) { this.host = host; }
    public synchronized Status status() { return host.status(); }
    public synchronized List<String> logs() { return host.logs(); }
    public synchronized Status start() { return host.start(); }
    public synchronized Status stop() { return host.stop(); }
    public synchronized Status restart() { host.stop(); return host.start(); }

    public synchronized Status install(InstallRequest request) {
        if (request == null || !request.confirmed()) throw new NodeRedException("CONFIRMATION_REQUIRED", "安装或更新前必须明确确认");
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
            if (!"RUNNING".equals(ready.state())) throw new NodeRedException("START_TIMEOUT", "候选版本健康检查未通过");
            return running ? ready : host.stop();
        } catch (RuntimeException error) {
            try {
                host.stop();
                host.restore(backup);
                host.activate(previous);
                if (running) host.start();
            } catch (RuntimeException rollback) {
                throw new NodeRedException("ROLLBACK_FAILED", "更新失败且恢复失败，请保留备份并查看诊断", rollback);
            }
            throw new NodeRedException("UPDATE_ROLLED_BACK", "新版本未就绪，已恢复更新前的程序和数据", error);
        }
    }

    public synchronized String backup() {
        boolean running = "RUNNING".equals(host.status().state());
        host.stop();
        try { return host.backup("migration"); }
        finally { if (running) host.start(); }
    }

    public synchronized Status restore(String id, boolean confirmed) {
        if (!confirmed) throw new NodeRedException("CONFIRMATION_REQUIRED", "恢复前必须明确确认");
        boolean running = "RUNNING".equals(host.status().state());
        host.stop();
        String snapshot;
        try { snapshot = host.backup("restore"); }
        catch (RuntimeException error) { if (running) host.start(); throw error; }
        try { host.restore(id); return running ? host.start() : host.status(); }
        catch (RuntimeException error) {
            try { host.stop(); host.restore(snapshot); if (running) host.start(); }
            catch (RuntimeException rollback) { throw new NodeRedException("ROLLBACK_FAILED", "恢复失败且无法回退，请保留恢复前备份", rollback); }
            throw new NodeRedException("RESTORE_ROLLED_BACK", "恢复未完成，已回退到恢复前数据", error);
        }
    }

    public synchronized Status uninstall() {
        host.uninstall();
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
