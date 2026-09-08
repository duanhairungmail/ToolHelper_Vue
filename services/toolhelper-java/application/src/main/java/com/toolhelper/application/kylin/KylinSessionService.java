package com.toolhelper.application.kylin;

import com.toolhelper.domain.kylin.KylinSessionState;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KylinSessionService {
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private final KylinRemoteClient remoteClient;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    public KylinSessionService(KylinRemoteClient remoteClient) { this.remoteClient = remoteClient; }
    public KylinContracts.SessionInfo open(KylinContracts.CreateSessionRequest request) {
        String id = UUID.randomUUID().toString();
        KylinRemoteClient.ConnectionOptions options = new KylinRemoteClient.ConnectionOptions(request.host(), request.port() == null ? 22 : request.port(), request.username(), request.password(), request.privateKey(), request.privateKeyPassphrase(), request.sudoPassword());
        try {
            KylinRemoteClient.RemoteSession remote = remoteClient.connect(options);
            String architecture = remote.architecture();
            if (!"x86_64".equalsIgnoreCase(architecture) && !"amd64".equalsIgnoreCase(architecture)) { remote.close(); throw new KylinSessionException("UNSUPPORTED_ARCHITECTURE", "仅支持 KylinOS X86_64"); }
            Session session = new Session(id, options, remote, Instant.now()); sessions.put(id, session); return session.info();
        } catch (KylinSessionException error) { throw error; }
        catch (Exception error) { throw new KylinSessionException("SESSION_CONNECT_FAILED", "SSH/SFTP 会话建立失败", error); }
    }
    public Session require(String id) { Session session = sessions.get(id); if (session == null || session.state != KylinSessionState.READY) throw new KylinSessionException("SESSION_NOT_READY", "会话不存在或已关闭"); session.lastUsedAt = Instant.now(); return session; }
    public void close(String id) { Session session = sessions.remove(id); if (session != null) session.close(); }
    public void expireIdleSessions() { Instant deadline = Instant.now().minus(IDLE_TIMEOUT); sessions.values().removeIf(session -> { if (session.lastUsedAt.isBefore(deadline)) { session.close(); return true; } return false; }); }
    public static final class Session implements AutoCloseable {
        private final String id; private final KylinRemoteClient.ConnectionOptions options; private final KylinRemoteClient.RemoteSession remote; private final Instant createdAt; private volatile Instant lastUsedAt; private volatile KylinSessionState state = KylinSessionState.READY;
        Session(String id, KylinRemoteClient.ConnectionOptions options, KylinRemoteClient.RemoteSession remote, Instant createdAt) { this.id=id; this.options=options; this.remote=remote; this.createdAt=createdAt; this.lastUsedAt=createdAt; }
        public String id() { return id; } public KylinRemoteClient.ConnectionOptions options() { return options; } public KylinRemoteClient.RemoteSession remote() { return remote; }
        public KylinContracts.SessionInfo info() { return new KylinContracts.SessionInfo(id, options.host(), options.port(), options.username(), state, remote.architecture(), remote.sftpReady(), createdAt, lastUsedAt); }
        @Override public void close() { state=KylinSessionState.CLOSED; remote.close(); }
    }
    public static class KylinSessionException extends RuntimeException { private final String code; public KylinSessionException(String code,String message){super(message);this.code=code;} public KylinSessionException(String code,String message,Throwable cause){super(message,cause);this.code=code;} public String code(){return code;} }
}
