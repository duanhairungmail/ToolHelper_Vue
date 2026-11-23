package com.toolhelper.infrastructure.nodered;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static com.toolhelper.application.nodered.NodeRedContracts.*;

/** 调用者须先停服，确保流程、凭据和节点依赖处于同一个静止快照中。 */
public final class NodeRedBackupService {
    private final NodeRedProperties properties;
    private final NodeRedSettingsService settings;
    public NodeRedBackupService(NodeRedProperties properties, NodeRedSettingsService settings) { this.properties = properties; this.settings = settings; }

    public String backup(String reason) {
        if (!Set.of("automatic", "upgrade", "migration", "nodes", "restore").contains(reason)) throw new IllegalArgumentException("未知备份类型");
        try {
            NodeRedFiles.noLinks(properties.secret().getParent(), properties.secret());
            if (!Files.exists(properties.secret()) && Files.isDirectory(properties.data())) try (var files = Files.walk(properties.data())) {
                if (files.anyMatch(Files::isRegularFile)) throw new NodeRedException("CREDENTIAL_SECRET_MISSING", "已有用户数据但密钥缺失，禁止创建无法恢复的备份");
            }
            NodeRedFiles.privateDirectory(properties.backups());
            String id = reason + "-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".zip";
            Path archive = properties.backups().resolve(id), temporary = properties.backups().resolve(id + ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                if (Files.exists(properties.secret())) {
                    byte[] sealed = Files.readAllBytes(properties.secret()); settings.verifySealedSecret(sealed);
                    zip.putNextEntry(new ZipEntry(".toolhelper-credential-secret.bin")); zip.write(sealed); zip.closeEntry();
                }
                if (Files.exists(properties.data())) {
                    NodeRedFiles.noLinks(properties.data(), properties.data());
                    try (var files = Files.walk(properties.data())) {
                        for (Path path : files.sorted().toList()) {
                            NodeRedFiles.noLinks(properties.data(), path);
                            if (Files.isDirectory(path)) continue;
                            if (path.getFileName().toString().equals(".toolhelper-credential-secret.bin")) throw new IOException("用户数据使用了备份保留文件名");
                            zip.putNextEntry(new ZipEntry(properties.data().relativize(path).toString().replace('\\', '/')));
                            Files.copy(path, zip); zip.closeEntry();
                        }
                    }
                }
            } catch (IOException | RuntimeException error) {
                try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
            Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE);
            prune(reason, Set.of("upgrade", "restore").contains(reason) ? 2 : 7);
            return id;
        } catch (IOException error) { throw new NodeRedException("BACKUP_FAILED", "Node-RED 数据备份失败，已阻止后续变更", error); }
    }

    public boolean automaticBackupDue() {
        if (!Files.isDirectory(properties.data())) return false;
        try {
            if (!Files.isDirectory(properties.backups())) return true;
            try (var files = Files.list(properties.backups())) {
                Optional<Path> latest = files.filter(path -> path.getFileName().toString().startsWith("automatic-") && path.toString().endsWith(".zip")).max(Comparator.comparing(path -> path.getFileName().toString()));
                return latest.isEmpty() || Files.getLastModifiedTime(latest.get()).toInstant().isBefore(Instant.now().minus(Duration.ofDays(7)));
            }
        } catch (IOException error) { throw new NodeRedException("BACKUP_FAILED", "无法读取自动备份状态", error); }
    }

    public void restore(String id) {
        if (id == null || !id.matches("(automatic|upgrade|migration|nodes|restore)-[0-9]+-[0-9a-f-]{36}\\.zip")) throw new NodeRedException("RESTORE_FAILED", "备份标识无效");
        Path parent = properties.data().getParent(), temporary = null, old = parent.resolve("nodered-rollback-" + UUID.randomUUID());
        boolean movedOld = false;
        try {
            NodeRedFiles.noLinks(properties.backups(), properties.backups().resolve(id));
            NodeRedFiles.noLinks(parent, properties.data());
            Files.createDirectories(parent);
            temporary = Files.createTempDirectory(parent, "nodered-restore-");
            NodeRedFiles.privateAccess(temporary);
            NodeRedPackageService.extract(properties.backups().resolve(id), temporary, 3L * 1024 * 1024 * 1024);
            Path sealed = temporary.resolve(".toolhelper-credential-secret.bin");
            if (Files.exists(sealed)) {
                byte[] protectedBytes = Files.readAllBytes(sealed); settings.verifySealedSecret(protectedBytes);
                if (Files.exists(properties.secret()) && !java.security.MessageDigest.isEqual(Files.readAllBytes(properties.secret()), protectedBytes))
                    throw new IOException("备份密钥与当前凭据密钥不一致，禁止覆盖");
                if (!Files.exists(properties.secret())) {
                    NodeRedFiles.privateDirectory(properties.secret().getParent());
                    NodeRedFiles.atomicWrite(properties.secret(), protectedBytes); NodeRedFiles.privateAccess(properties.secret());
                }
                Files.delete(sealed);
            } else {
                try (var restoredFiles = Files.walk(temporary)) {
                    if (restoredFiles.anyMatch(Files::isRegularFile)) throw new IOException("非空备份缺少DPAPI凭据密钥");
                }
            }
            if (Files.exists(properties.data())) { Files.move(properties.data(), old, StandardCopyOption.ATOMIC_MOVE); movedOld = true; }
            Files.move(temporary, properties.data(), StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            if (movedOld) NodeRedFiles.removeTree(parent, old);
        } catch (IOException error) {
            if (movedOld && !Files.exists(properties.data())) try { Files.move(old, properties.data(), StandardCopyOption.ATOMIC_MOVE); } catch (IOException restoreError) { error.addSuppressed(restoreError); }
            throw new NodeRedException("RESTORE_FAILED", "无法恢复 Node-RED 数据，请保留原备份", error);
        } finally { if (temporary != null) try { NodeRedFiles.removeTree(parent, temporary); } catch (IOException error) { System.err.println("NodeRed 恢复暂存目录清理失败"); } }
    }

    private void prune(String reason, int retain) throws IOException {
        try (var files = Files.list(properties.backups())) {
            List<Path> backups = files.filter(path -> path.getFileName().toString().startsWith(reason + "-") && path.toString().endsWith(".zip")).sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed()).toList();
            for (int i = retain; i < backups.size(); i++) Files.delete(backups.get(i));
        }
    }
}
