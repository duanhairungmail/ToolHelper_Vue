package com.toolhelper.api.database.security;

import com.toolhelper.infrastructure.InternalDbProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Component
public class DatabasePathPolicy {
    private static final Set<String> EXTENSIONS = Set.of(".db", ".sqlite", ".sqlite3");
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private final Path internalDatabase;
    private final Path internalRoot;

    public DatabasePathPolicy(InternalDbProperties properties) {
        this.internalDatabase = properties.databasePath().toAbsolutePath().normalize();
        this.internalRoot = internalDatabase.getParent();
    }

    public Path validate(Path requested) {
        if (requested == null) throw new IllegalArgumentException("数据库路径不能为空");
        Path path = requested.toAbsolutePath().normalize();
        String extension = extension(path.getFileName().toString());
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("仅支持 .db、.sqlite、.sqlite3 文件");
        if (path.equals(internalDatabase) || path.startsWith(internalRoot)) {
            throw new IllegalArgumentException("用户数据库不得位于 ToolHelper 内部目录");
        }
        try {
            if (Files.exists(path)) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IllegalArgumentException("数据库必须是普通文件，禁止符号链接");
                if (Files.size(path) > MAX_BYTES) throw new IllegalArgumentException("数据库文件超过 2 GiB 限制");
            } else {
                Path parent = path.getParent();
                if (parent == null || !Files.isDirectory(parent) || Files.isSymbolicLink(parent)) {
                    throw new IllegalArgumentException("数据库父目录不存在或不安全");
                }
            }
            if (Files.isWritable(path) || (Files.exists(path.getParent()) && Files.isWritable(path.getParent()))) return path;
        } catch (IOException error) {
            throw new IllegalArgumentException("无法检查数据库路径", error);
        }
        throw new IllegalArgumentException("数据库文件或父目录不可写");
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }
}
