package com.toolhelper.application.contract;

import java.util.List;
import java.util.Map;

public final class DatabaseContracts {
    private DatabaseContracts() {}

    public record CreateSessionRequest(String path, char[] password) {}
    public record SessionInfo(String sessionId, String path, String openedAt) {}
    public record MetadataNode(String type, String name, String tableName) {}
    public record MetadataColumn(String name, String type, boolean primaryKey, boolean notNull) {}
    public record QueryRequest(String sql, Integer page, Integer pageSize, boolean confirmHighRisk) {}
    public record QueryAccepted(String taskId, String traceId) {}
    public record QueryResult(String taskId, String status, List<String> columns, List<List<Object>> rows,
                              long rowCount, boolean hasMore, String errorCode, String traceId) {}
    public record QueryExport(String taskId, String format, String fileName) {}
    public record MutationRequest(String table, String primaryKeyColumn, Object primaryKeyValue,
                                  Map<String, Object> changes, boolean confirmHighRisk) {}
    public record TaskEvent(String eventId, String type, String jobId, String timestamp, String traceId,
                             Map<String, Object> payload) {}
}
