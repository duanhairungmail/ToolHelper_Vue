package com.toolhelper.application.kylin;

import com.toolhelper.domain.kylin.KylinJobState;
import com.toolhelper.domain.kylin.KylinOperation;
import com.toolhelper.domain.kylin.KylinSessionState;
import com.toolhelper.domain.kylin.OperationStep;
import java.time.Instant;
import java.util.List;

public final class KylinContracts {
    private KylinContracts() {}
    public record CreateSessionRequest(String host, Integer port, String username, String password,
                                       String privateKey, String privateKeyPassphrase, String sudoPassword) {}
    public record SessionInfo(String id, String host, int port, String username, KylinSessionState state,
                              String architecture, boolean sftpReady, Instant createdAt, Instant lastUsedAt) {}
    public record CreateJobRequest(KylinOperation operation, String confirmationToken, boolean confirm) {}
    public record Confirmation(String token, Instant expiresAt) {}
    public record JobInfo(String id, String sessionId, KylinOperation operation, KylinJobState state,
                          List<OperationStep> steps, String message, Instant createdAt, Instant finishedAt) {}
}
