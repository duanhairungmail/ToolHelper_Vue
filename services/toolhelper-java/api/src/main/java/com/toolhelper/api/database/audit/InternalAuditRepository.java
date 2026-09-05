package com.toolhelper.api.database.audit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class InternalAuditRepository {
    private final JdbcTemplate jdbc;

    public InternalAuditRepository(@Qualifier("internalJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String sessionId, String operation, long durationMs, long rowCount, String resultCode, String traceId) {
        jdbc.update("INSERT INTO audit_events(session_id, operation_type, duration_ms, row_count, result_code, created_at, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionId, operation, durationMs, rowCount, resultCode, Instant.now().toString(), traceId);
    }

    public void taskStarted(String taskId, String traceId) {
        jdbc.update("INSERT INTO task_runs(id, kind, status, created_at, trace_id) VALUES (?, ?, ?, ?, ?)",
                taskId, "database.query", "RUNNING", Instant.now().toString(), traceId);
    }

    public void taskFinished(String taskId, String status, long rowCount, String errorCode, String traceId) {
        jdbc.update("UPDATE task_runs SET status = ?, completed_at = ?, row_count = ?, error_code = ? WHERE id = ?",
                status, Instant.now().toString(), rowCount, errorCode, taskId);
    }
}
