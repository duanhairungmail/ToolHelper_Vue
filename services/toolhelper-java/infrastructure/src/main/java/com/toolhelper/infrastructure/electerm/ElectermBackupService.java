package com.toolhelper.infrastructure.electerm;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static com.toolhelper.application.electerm.ElectermContracts.*;

/** 调用者须先停服，确保 SQLite 主文件/WAL 及配置在同一个静止快照中。 */
public final class ElectermBackupService {
    private final ElectermProperties properties;
    public ElectermBackupService(ElectermProperties properties) { this.properties = properties; }

    public String backup(String reason) {
        if (!Set.of("automatic", "upgrade", "migration").contains(reason)) throw new IllegalArgumentException("未知备份类型");
        try {
            ElectermFiles.privateDirectory(properties.backups());
            String id = reason + "-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".zip";
            Path archive = properties.backups().resolve(id), temporary = properties.backups().resolve(id + ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                if (Files.exists(properties.data())) {
                    ElectermFiles.noLinks(properties.data(), properties.data());
                    try (var files = Files.walk(properties.data())) {
                        for (Path path : files.sorted().toList()) {
                            if (Files.isSymbolicLink(path)) throw new IOException("备份拒绝包含符号链接的数据");
                            if (Files.isDirectory(path)) continue;
                            zip.putNextEntry(new ZipEntry(properties.data().relativize(path).toString().replace('\\', '/')));
                            Files.copy(path, zip); zip.closeEntry();
                        }
                    }
                }
            } catch (IOException error) { Files.deleteIfExists(temporary); throw error; }
            Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE);
            prune(reason, "upgrade".equals(reason) ? 2 : 5);
            return id;
        } catch (IOException error) { throw new ElectermException("BACKUP_FAILED", "Electerm 数据备份失败，已阻止后续变更", error); }
    }

    public boolean automaticBackupDue() {
        if (!Files.isDirectory(properties.data())) return false;
        try {
            if (!Files.isDirectory(properties.backups())) return true;
            try (var files = Files.list(properties.backups())) {
                Optional<Path> latest = files.filter(path -> path.getFileName().toString().startsWith("automatic-") && path.toString().endsWith(".zip")).max(Comparator.comparing(path -> path.getFileName().toString()));
                return latest.isEmpty() || Files.getLastModifiedTime(latest.get()).toInstant().isBefore(Instant.now().minus(Duration.ofDays(7)));
            }
        } catch (IOException error) { throw new ElectermException("BACKUP_FAILED", "无法读取自动备份状态", error); }
    }

    public void restore(String id) {
        if (id == null || !id.matches("(automatic|upgrade|migration)-[0-9]+-[0-9a-f-]{36}\\.zip")) throw new ElectermException("RESTORE_FAILED", "备份标识无效");
        Path parent = properties.data().getParent(), temporary = null, old = parent.resolve("electerm-rollback-" + UUID.randomUUID());
        boolean movedOld = false;
        try {
            ElectermFiles.noLinks(properties.backups(), properties.backups().resolve(id));
            ElectermFiles.noLinks(parent, properties.data());
            Files.createDirectories(parent);
            temporary = Files.createTempDirectory(parent, "electerm-restore-");
            ElectermFiles.privateAccess(temporary);
            ElectermPackageService.extract(properties.backups().resolve(id), temporary, 3L * 1024 * 1024 * 1024);
            if (Files.exists(properties.data())) { Files.move(properties.data(), old, StandardCopyOption.ATOMIC_MOVE); movedOld = true; }
            Files.move(temporary, properties.data(), StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            if (movedOld) ElectermFiles.removeTree(parent, old);
        } catch (IOException error) {
            if (movedOld && !Files.exists(properties.data())) try { Files.move(old, properties.data(), StandardCopyOption.ATOMIC_MOVE); } catch (IOException restoreError) { error.addSuppressed(restoreError); }
            throw new ElectermException("RESTORE_FAILED", "无法恢复 Electerm 数据，请保留原备份", error);
        } finally { if (temporary != null) try { ElectermFiles.removeTree(parent, temporary); } catch (IOException error) { System.err.println("Electerm 恢复暂存目录清理失败"); } }
    }

    private void prune(String reason, int retain) throws IOException {
        try (var files = Files.list(properties.backups())) {
            List<Path> backups = files.filter(path -> path.getFileName().toString().startsWith(reason + "-") && path.toString().endsWith(".zip")).sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed()).toList();
            for (int i = retain; i < backups.size(); i++) Files.delete(backups.get(i));
        }
    }
}
