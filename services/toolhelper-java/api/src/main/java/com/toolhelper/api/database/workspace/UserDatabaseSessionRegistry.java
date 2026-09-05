package com.toolhelper.api.database.workspace;

import com.toolhelper.api.database.DatabaseErrorCode;
import com.toolhelper.api.database.DatabaseErrorClassifier;
import com.toolhelper.api.database.DatabaseOperationException;
import com.toolhelper.api.database.audit.InternalAuditRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserDatabaseSessionRegistry implements AutoCloseable {
    private static final Duration IDLE_LIMIT = Duration.ofMinutes(10);
    private final UserDatabaseSessionFactory factory;
    private final InternalAuditRepository audit;
    private final Map<String, UserDatabaseSession> sessions = new ConcurrentHashMap<>();

    public UserDatabaseSessionRegistry(UserDatabaseSessionFactory factory, InternalAuditRepository audit) {
        this.factory = factory;
        this.audit = audit;
    }

    public UserDatabaseSession open(Path path, char[] password, String traceId) {
        try {
            UserDatabaseSession session = factory.open(path, password);
            sessions.put(session.id(), session);
            audit.record(session.id(), "SESSION_OPEN", 0, 0, "OK", traceId);
            return session;
        } catch (DatabaseOperationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw DatabaseErrorClassifier.classify("打开数据库会话失败", error);
        }
    }

    public UserDatabaseSession require(String id) {
        UserDatabaseSession session = sessions.get(id);
        if (session == null) throw new DatabaseOperationException(DatabaseErrorCode.DATABASE_SESSION_CLOSED, "数据库会话不存在或已关闭");
        return session;
    }

    public void close(String id, String traceId) {
        UserDatabaseSession session = sessions.remove(id);
        if (session != null) {
            session.close();
            audit.record(session.id(), "SESSION_CLOSE", 0, 0, "OK", traceId);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void closeIdleSessions() {
        Instant deadline = Instant.now().minus(IDLE_LIMIT);
        sessions.values().stream().filter(session -> session.lastUsedAt().isBefore(deadline))
                .forEach(session -> close(session.id(), "system"));
    }

    @Override
    public void close() {
        sessions.keySet().forEach(id -> close(id, "shutdown"));
    }
}
