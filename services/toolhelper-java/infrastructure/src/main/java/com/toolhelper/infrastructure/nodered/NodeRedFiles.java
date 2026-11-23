package com.toolhelper.infrastructure.nodered;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

final class NodeRedFiles {
    private NodeRedFiles() {}

    static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".toolhelper-", ".tmp");
        try { Files.write(temporary, bytes); Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }

    static void privateDirectory(Path path) throws IOException {
        noLinks(path, path);
        Files.createDirectories(path);
        privateAccess(path);
    }

    static void privateAccess(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("禁止使用符号链接作为数据目录");
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
            acl.setAcl(List.of(entry.build()));
        } else Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
    }

    /** 递归删除只接受已验证的受管子目录，绝不跟随符号链接。 */
    static void removeTree(Path allowedRoot, Path target) throws IOException {
        Path root = allowedRoot.toAbsolutePath().normalize(), resolved = target.toAbsolutePath().normalize();
        if (!resolved.startsWith(root) || resolved.equals(root) || Files.isSymbolicLink(root)) throw new IOException("拒绝删除受控根目录以外的路径");
        noLinks(root, resolved);
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(resolved)) { for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }

    static void noLinks(Path root, Path target) throws IOException {
        Path base = root.toAbsolutePath().normalize(), normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) throw new IOException("路径越界");
        for (Path path = normalized; path != null; path = path.getParent()) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("受管路径禁止符号链接或重解析点");
            }
        }
    }
}
