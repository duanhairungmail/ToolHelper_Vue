package com.toolhelper.application.nodered;

import java.time.Instant;
import java.util.List;

public final class NodeRedContracts {
    private NodeRedContracts() {}
    public record Release(String id, String version, String nodeVersion, String kind, String sha256) {}
    public record Status(String state, Long pid, Instant startedAt, String version, String nodeVersion,
                         int port, String instanceId, String url, String diagnostic) {}
    public record InstallRequest(String offlinePackage, boolean confirmed) {}
    public record Event(String eventId, String type, String jobId, Instant timestamp, String traceId, java.util.Map<String, String> payload) {}
    public interface Host {
        Release current(); Release prepare(InstallRequest request); void activate(Release release);
        Status status(); Status start(); Status stop(); String backup(String reason); void restore(String id);
        boolean automaticBackupDue(); void uninstall(); List<String> logs();
    }
    public static final class NodeRedException extends RuntimeException {
        private final String code;
        public NodeRedException(String code, String message) { super(message); this.code = code; }
        public NodeRedException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
        public String code() { return code; }
    }
}
