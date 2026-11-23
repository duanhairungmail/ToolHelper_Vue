package com.toolhelper.infrastructure.nodered;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import static com.toolhelper.application.nodered.NodeRedContracts.*;

/** 只把明文密钥送入受管进程环境；DPAPI 辅助进程通过 stdin/stdout 交换，不进入参数或日志。 */
public final class NodeRedSettingsService {
    private final NodeRedProperties properties;
    public NodeRedSettingsService(NodeRedProperties properties) { this.properties = properties; }

    public synchronized Map<String, String> environment() {
        try {
            byte[] secret = credentialSecret();
            Properties admin = administrator();
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(
                admin.getProperty("password").toCharArray(), Base64.getDecoder().decode(admin.getProperty("salt")), 210_000, 256)).getEncoded();
            Map<String, String> result = Map.of("TOOLHELPER_NODERED_SECRET", Base64.getEncoder().encodeToString(secret),
                "TOOLHELPER_NODERED_ADMIN_SALT", admin.getProperty("salt"), "TOOLHELPER_NODERED_ADMIN_HASH", Base64.getEncoder().encodeToString(hash));
            Arrays.fill(secret, (byte) 0); Arrays.fill(hash, (byte) 0);
            return result;
        } catch (NodeRedException error) { throw error; }
        catch (Exception error) { throw new NodeRedException("SETTINGS_FAILED", "无法读取Node-RED密钥或本机认证设置", error); }
    }

    private byte[] credentialSecret() throws Exception {
        Path path = properties.secret();
        NodeRedFiles.noLinks(path.getParent(), path);
        if (Files.exists(path)) {
            NodeRedFiles.privateAccess(path);
            byte[] secret = unprotect(Files.readAllBytes(path));
            if (secret.length != 32) throw new IOException("凭据密钥长度无效");
            return secret;
        }
        // 已有任何用户数据都不能被新密钥覆盖，即使 flows_cred.json 被移动或命名改变。
        if (Files.isDirectory(properties.data())) try (var files = Files.walk(properties.data())) {
            if (files.anyMatch(Files::isRegularFile)) throw new NodeRedException("CREDENTIAL_SECRET_MISSING", "已有Node-RED数据但凭据密钥缺失，请恢复同用户DPAPI备份；禁止生成新密钥");
        }
        NodeRedFiles.privateDirectory(path.getParent());
        byte[] secret = random();
        NodeRedFiles.atomicWrite(path, protect(secret)); NodeRedFiles.privateAccess(path);
        return secret;
    }

    private Properties administrator() throws IOException {
        Path path = properties.credentials(); NodeRedFiles.privateDirectory(path.getParent());
        Properties result = new Properties();
        if (Files.exists(path)) {
            NodeRedFiles.privateAccess(path); try (Reader reader = Files.newBufferedReader(path)) { result.load(reader); }
        } else {
            result.setProperty("username", "toolhelper"); result.setProperty("password", Base64.getUrlEncoder().withoutPadding().encodeToString(random()));
            result.setProperty("salt", Base64.getEncoder().encodeToString(random()));
            StringWriter writer = new StringWriter(); result.store(writer, "Node-RED local administrator; keep private");
            NodeRedFiles.atomicWrite(path, writer.toString().getBytes(StandardCharsets.UTF_8)); NodeRedFiles.privateAccess(path);
        }
        if (result.getProperty("password", "").length() < 32 || Base64.getDecoder().decode(result.getProperty("salt", "")).length != 32)
            throw new IOException("本机认证文件无效");
        return result;
    }

    public void verifySealedSecret(byte[] sealed) {
        byte[] clear = unprotect(sealed);
        try { if (clear.length != 32) throw new NodeRedException("CREDENTIAL_SECRET_INVALID", "备份中的凭据密钥无效"); }
        finally { Arrays.fill(clear, (byte) 0); }
    }
    private static byte[] random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return bytes; }
    private static byte[] protect(byte[] bytes) { return dpapi(bytes, true); }
    private static byte[] unprotect(byte[] bytes) { return dpapi(bytes, false); }
    private static byte[] dpapi(byte[] bytes, boolean protect) {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
            throw new NodeRedException("DPAPI_UNAVAILABLE", "Node-RED凭据密钥要求Windows DPAPI CurrentUser");
        String script = "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; "
            + "$b=[Convert]::FromBase64String([Console]::In.ReadToEnd()); "
            + "$r=[Security.Cryptography.ProtectedData]::" + (protect ? "Protect" : "Unprotect")
            + "($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); [Console]::Out.Write([Convert]::ToBase64String($r))";
        Process process = null;
        try {
            Path powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32/WindowsPowerShell/v1.0/powershell.exe");
            process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream output = process.getOutputStream()) { output.write(Base64.getEncoder().encode(bytes)); }
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("DPAPI调用超时");
            if (process.exitValue() != 0) throw new IOException("DPAPI调用失败");
            byte[] output = process.getInputStream().readNBytes(32769);
            if (output.length > 32768) throw new IOException("DPAPI响应超限");
            return Base64.getDecoder().decode(new String(output, StandardCharsets.US_ASCII).trim());
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new NodeRedException("DPAPI_FAILED", "Windows当前用户无法保护或解密Node-RED密钥，请保留原密钥文件", error);
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
