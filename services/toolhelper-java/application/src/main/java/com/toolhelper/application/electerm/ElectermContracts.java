package com.toolhelper.application.electerm;

import java.time.Instant;
import java.util.List;

public final class ElectermContracts {
    private ElectermContracts() {}

    public record Release(String id, String version, String nodeVersion) {}
    public record Status(String state, Long pid, Instant startedAt, String version, String nodeVersion,
                         int port, String instanceId, String url, String diagnostic) {}
    /** 离线导入只允许服务器配置的 inbox 中的包名，不接收任意文件路径或下载地址。 */
    public record InstallRequest(String offlinePackage) {}
    public record PurgeConfirmation(String token, Instant expiresAt) {}

    public interface Host {
        Release current();
        Release prepare(InstallRequest request);
        void activate(Release release);
        Status status();
        Status start();
        Status stop();
        String backup(String reason);
        void restore(String backupId);
        boolean automaticBackupDue();
        void uninstall(boolean purgeData);
        List<String> logs();
    }

    public static final class ElectermException extends RuntimeException {
        private final String code;
        public ElectermException(String code, String message) { super(message); this.code = code; }
        public ElectermException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
        public String code() { return code; }
    }
}
