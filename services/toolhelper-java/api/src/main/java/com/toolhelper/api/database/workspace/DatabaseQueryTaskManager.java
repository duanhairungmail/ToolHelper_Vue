package com.toolhelper.api.database.workspace;

import com.toolhelper.api.database.audit.InternalAuditRepository;
import com.toolhelper.api.database.security.SqlRiskClassifier;
import com.toolhelper.application.contract.DatabaseContracts;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DatabaseQueryTaskManager implements AutoCloseable {
    private final UserDatabaseSessionRegistry sessions;
    private final SqlRiskClassifier riskClassifier;
    private final InternalAuditRepository audit;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, QueryTask> tasks = new ConcurrentHashMap<>();

    public DatabaseQueryTaskManager(UserDatabaseSessionRegistry sessions, SqlRiskClassifier riskClassifier,
                                    InternalAuditRepository audit) {
        this.sessions = sessions;
        this.riskClassifier = riskClassifier;
        this.audit = audit;
    }

    public DatabaseContracts.QueryAccepted submit(String sessionId, DatabaseContracts.QueryRequest request, String traceId) {
        UserDatabaseSession session = sessions.require(sessionId);
        SqlRiskClassifier.Risk risk = riskClassifier.classify(request.sql());
        if (risk.internalPathAccess()) throw new IllegalArgumentException("禁止 ATTACH 到 ToolHelper 内部数据库");
        if (risk.highRisk() && !request.confirmHighRisk()) throw new HighRiskConfirmationRequiredException(risk.operation());

        String taskId = UUID.randomUUID().toString();
        QueryTask task = new QueryTask(taskId, sessionId, request, traceId);
        tasks.put(taskId, task);
        audit.taskStarted(taskId, traceId);
        task.future = executor.submit(() -> execute(task, session));
        return new DatabaseContracts.QueryAccepted(taskId, traceId);
    }

    public DatabaseContracts.QueryResult requireResult(String taskId) {
        QueryTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("查询任务不存在");
        return task.result;
    }

    public void cancel(String taskId) {
        QueryTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("查询任务不存在");
        task.cancelled = true;
        if (task.statement != null) try { task.statement.cancel(); } catch (Exception ignored) { }
        if (task.future != null) task.future.cancel(true);
    }

    public SseEmitter events(String taskId, String lastEventId) {
        QueryTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("查询任务不存在");
        SseEmitter emitter = new SseEmitter(30_000L);
        executor.submit(() -> {
            try {
                while (!task.completed && !task.cancelled) Thread.sleep(50);
                if (!"1".equals(lastEventId)) {
                    DatabaseContracts.QueryResult result = task.result;
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("status", result.status());
                    payload.put("rowCount", result.rowCount());
                    payload.put("errorCode", result.errorCode());
                    emitter.send(SseEmitter.event().id("1").name("query.completed")
                            .data(new DatabaseContracts.TaskEvent("1", "query.completed", taskId,
                                    Instant.now().toString(), task.traceId, payload)));
                }
                emitter.complete();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    public byte[] exportCsv(String taskId) {
        DatabaseContracts.QueryResult result = requireResult(taskId);
        if (!"SUCCEEDED".equals(result.status())) throw new IllegalStateException("查询尚未成功完成");
        StringBuilder csv = new StringBuilder();
        appendCsvRow(csv, result.columns());
        for (List<Object> row : result.rows()) appendCsvRow(csv, row);
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void execute(QueryTask task, UserDatabaseSession session) {
        long started = System.currentTimeMillis();
        long rowCount = 0;
        String resultCode = "OK";
        try (Connection connection = session.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(task.request.sql())) {
            task.statement = statement;
            statement.setQueryTimeout(30);
            String keyword = task.request.sql().stripLeading().split("\\s+", 2)[0].toUpperCase();
            if (SetOfQueries.READ.contains(keyword)) {
                int page = clamp(task.request.page(), 0, 1_000_000);
                int pageSize = clamp(task.request.pageSize(), 1, 500);
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) columns.add(metadata.getColumnLabel(i));
                    long skipped = 0;
                    List<List<Object>> rows = new ArrayList<>();
                    boolean hasMore = false;
                    while (resultSet.next()) {
                        rowCount++;
                        if (skipped < (long) page * pageSize) { skipped++; continue; }
                        if (rows.size() == pageSize) { hasMore = true; break; }
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) row.add(resultSet.getObject(i));
                        rows.add(row);
                    }
                    task.result = new DatabaseContracts.QueryResult(task.id, "SUCCEEDED", columns, rows, rowCount, hasMore, null, task.traceId);
                }
            } else {
                rowCount = statement.executeUpdate();
                task.result = new DatabaseContracts.QueryResult(task.id, "SUCCEEDED", List.of(), List.of(), rowCount, false, null, task.traceId);
            }
        } catch (Exception error) {
            resultCode = task.cancelled ? "QUERY_CANCELLED" : "QUERY_FAILED";
            task.result = new DatabaseContracts.QueryResult(task.id, task.cancelled ? "CANCELLED" : "FAILED",
                    List.of(), List.of(), rowCount, false, resultCode, task.traceId);
        } finally {
            task.completed = true;
            task.statement = null;
            audit.taskFinished(task.id, task.result.status(), task.result.rowCount(), task.result.errorCode(), task.traceId);
            audit.record(task.sessionId, task.request.sql().stripLeading().split("\\s+", 2)[0].toUpperCase(),
                    System.currentTimeMillis() - started, task.result.rowCount(), resultCode, task.traceId);
        }
    }

    private static int clamp(Integer value, int min, int max) {
        return Math.max(min, Math.min(max, value == null ? min : value));
    }

    private static void appendCsvRow(StringBuilder csv, List<?> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) csv.append(',');
            String value = row.get(i) == null ? "" : String.valueOf(row.get(i));
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    @Override
    public void close() {
        executor.close();
    }

    private static final class SetOfQueries {
        private static final java.util.Set<String> READ = java.util.Set.of("SELECT", "WITH", "PRAGMA", "EXPLAIN");
    }

    private static final class QueryTask {
        private final String id;
        private final String sessionId;
        private final DatabaseContracts.QueryRequest request;
        private final String traceId;
        private volatile java.util.concurrent.Future<?> future;
        private volatile PreparedStatement statement;
        private volatile boolean cancelled;
        private volatile boolean completed;
        private volatile DatabaseContracts.QueryResult result;

        private QueryTask(String id, String sessionId, DatabaseContracts.QueryRequest request, String traceId) {
            this.id = id;
            this.sessionId = sessionId;
            this.request = request;
            this.traceId = traceId;
            this.result = new DatabaseContracts.QueryResult(id, "RUNNING", List.of(), List.of(), 0, false, null, traceId);
        }
    }

    public static final class HighRiskConfirmationRequiredException extends RuntimeException {
        public HighRiskConfirmationRequiredException(String operation) { super("高风险 SQL 需要二次确认：" + operation); }
    }
}
