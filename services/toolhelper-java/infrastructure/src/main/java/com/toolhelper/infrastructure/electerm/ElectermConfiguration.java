package com.toolhelper.infrastructure.electerm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toolhelper.application.electerm.ElectermLifecycleService;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
public class ElectermConfiguration {
    @Bean ElectermProperties electermProperties(Environment environment) { return ElectermProperties.load(environment); }
    @Bean ElectermPackageService electermPackages(ElectermProperties properties, ObjectMapper json) { return new ElectermPackageService(properties, json); }
    @Bean ElectermHealthService electermHealth(ObjectMapper json) { return new ElectermHealthService(json); }
    @Bean ElectermBackupService electermBackups(ElectermProperties properties) { return new ElectermBackupService(properties); }
    @Bean(destroyMethod = "close") ElectermProcessRegistry electermProcesses(ElectermProperties properties, ElectermPackageService packages, ElectermHealthService health, ObjectMapper json) { return new ElectermProcessRegistry(properties, packages, health, json); }
    @Bean ElectermRuntime electermRuntime(ElectermProperties properties, ElectermPackageService packages, ElectermProcessRegistry processes, ElectermBackupService backups) { return new ElectermRuntime(properties, packages, processes, backups); }
    @Bean ElectermLifecycleService electermLifecycle(ElectermRuntime runtime) { return new ElectermLifecycleService(runtime); }
    @Bean BackupSchedule electermBackupSchedule(ElectermLifecycleService lifecycle, ElectermProcessRegistry processes) { return new BackupSchedule(lifecycle, processes); }

    static final class BackupSchedule {
        private final ElectermLifecycleService lifecycle;
        private final ElectermProcessRegistry processes;
        BackupSchedule(ElectermLifecycleService lifecycle, ElectermProcessRegistry processes) { this.lifecycle = lifecycle; this.processes = processes; }
        @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
        public void backupIfDue() {
            try { lifecycle.automaticBackup(); }
            catch (RuntimeException error) { processes.log("自动备份失败，请检查目录权限和磁盘空间"); System.err.println("Electerm 自动备份失败：" + error.getClass().getSimpleName()); }
        }
    }
}
