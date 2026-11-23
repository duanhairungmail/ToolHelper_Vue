package com.toolhelper.infrastructure.nodered;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toolhelper.application.nodered.NodeRedLifecycleService;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
public class NodeRedConfiguration {
    @Bean NodeRedProperties noderedProperties(Environment environment) { return NodeRedProperties.load(environment); }
    @Bean NodeRedPackageService noderedPackages(NodeRedProperties properties, ObjectMapper json) { return new NodeRedPackageService(properties, json); }
    @Bean NodeRedHealthService noderedHealth(ObjectMapper json) { return new NodeRedHealthService(json); }
    @Bean NodeRedSettingsService nodeRedSettings(NodeRedProperties properties) { return new NodeRedSettingsService(properties); }
    @Bean com.toolhelper.application.nodered.SerialPortCoordinator serialPortCoordinator() { return new com.toolhelper.application.nodered.SerialPortCoordinator(); }
    @Bean NodeRedBackupService noderedBackups(NodeRedProperties properties, NodeRedSettingsService settings) { return new NodeRedBackupService(properties, settings); }
    @Bean(destroyMethod = "close") NodeRedProcessRegistry noderedProcesses(NodeRedProperties properties, NodeRedPackageService packages, NodeRedHealthService health, ObjectMapper json, NodeRedSettingsService settings, com.toolhelper.application.nodered.SerialPortCoordinator serialPorts, Environment env) { return new NodeRedProcessRegistry(properties, packages, health, json, settings, serialPorts, env); }
    @Bean NodeRedRuntime noderedRuntime(NodeRedProperties properties, NodeRedPackageService packages, NodeRedProcessRegistry processes, NodeRedBackupService backups) { return new NodeRedRuntime(properties, packages, processes, backups); }
    @Bean NodeRedLifecycleService noderedLifecycle(NodeRedRuntime runtime) { return new NodeRedLifecycleService(runtime); }
    @Bean NodeApprovalService nodeApprovalService(NodeRedProperties properties, NodeRedPackageService packages, NodeRedLifecycleService lifecycle, NodeRedProcessRegistry processes, NodeRedBackupService backups, ObjectMapper json) {
        return new NodeApprovalService(properties, packages, lifecycle, processes, backups, json);
    }
    @Bean BackupSchedule noderedBackupSchedule(NodeRedLifecycleService lifecycle, NodeRedProcessRegistry processes) { return new BackupSchedule(lifecycle, processes); }

    static final class BackupSchedule {
        private final NodeRedLifecycleService lifecycle;
        private final NodeRedProcessRegistry processes;
        BackupSchedule(NodeRedLifecycleService lifecycle, NodeRedProcessRegistry processes) { this.lifecycle = lifecycle; this.processes = processes; }
        @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
        public void backupIfDue() {
            try { lifecycle.automaticBackup(); }
            catch (RuntimeException error) { processes.log("自动备份失败，请检查目录权限和磁盘空间"); System.err.println("NodeRed 自动备份失败：" + error.getClass().getSimpleName()); }
        }
    }
}
