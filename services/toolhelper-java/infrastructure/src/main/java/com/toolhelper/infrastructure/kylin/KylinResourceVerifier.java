package com.toolhelper.infrastructure.kylin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class KylinResourceVerifier {
    private KylinResourceVerifier() {}
    public static void verifyX86Resource(Path resource, String expectedSha256) throws IOException {
        if (resource == null || !Files.isRegularFile(resource)) throw new IOException("资源文件不存在");
        String actual; try { actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(resource))); } catch (Exception e) { throw new IOException("无法计算资源哈希", e); }
        if (!actual.equalsIgnoreCase(expectedSha256)) throw new SecurityException("X86_64 资源哈希不匹配");
    }
}
