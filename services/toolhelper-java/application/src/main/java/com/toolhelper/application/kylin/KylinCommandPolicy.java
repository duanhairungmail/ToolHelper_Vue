package com.toolhelper.application.kylin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 集中执行路径和参数校验，防止路径穿越及符号链接逃逸。 */
public final class KylinCommandPolicy {
    private KylinCommandPolicy() {}
    public static String shellQuote(String value) { if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("参数无效"); return "'" + value.replace("'", "'\\''") + "'"; }
    public static Path managedPath(Path root, Path candidate) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize(); Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) throw new SecurityException("路径超出受控目录");
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalized)) { current = current.resolve(part); if (Files.isSymbolicLink(current)) throw new SecurityException("禁止符号链接路径"); }
        return normalized;
    }
}
