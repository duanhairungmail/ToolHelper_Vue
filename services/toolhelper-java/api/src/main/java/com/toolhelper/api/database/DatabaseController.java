package com.toolhelper.api.database;

import com.toolhelper.api.database.workspace.DatabaseQueryTaskManager;
import com.toolhelper.api.database.workspace.DatabaseMutationService;
import com.toolhelper.api.database.workspace.UserDatabaseSession;
import com.toolhelper.api.database.workspace.UserDatabaseSessionRegistry;
import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.contract.DatabaseContracts;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.time.Instant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/database")
public class DatabaseController {
    private final UserDatabaseSessionRegistry sessions;
    private final DatabaseQueryTaskManager queries;
    private final DatabaseMutationService mutations;

    public DatabaseController(UserDatabaseSessionRegistry sessions, DatabaseQueryTaskManager queries, DatabaseMutationService mutations) {
        this.sessions = sessions;
        this.queries = queries;
        this.mutations = mutations;
    }

    @PostMapping("/sessions")
    public ApiResponse<DatabaseContracts.SessionInfo> open(@RequestBody DatabaseContracts.CreateSessionRequest request,
                                                           HttpServletRequest http) {
        String traceId = traceId(http);
        if (request.path() == null || request.path().isBlank()) throw new IllegalArgumentException("数据库路径不能为空");
        UserDatabaseSession session = sessions.open(Path.of(request.path()), request.password(), traceId);
        return ok(new DatabaseContracts.SessionInfo(session.id(), session.path().toString(), session.openedAt().toString()), traceId);
    }

    @PostMapping("/sessions/{sessionId}/test")
    public ApiResponse<Boolean> test(@PathVariable String sessionId, HttpServletRequest http) {
        String traceId = traceId(http);
        UserDatabaseSession session = sessions.require(sessionId);
        try (Connection connection = session.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            statement.execute();
            return ok(true, traceId);
        } catch (Exception error) {
            throw new IllegalStateException("数据库连接测试失败", error);
        }
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> close(@PathVariable String sessionId, HttpServletRequest http) {
        sessions.close(sessionId, traceId(http));
        return ok(null, traceId(http));
    }

    @GetMapping("/sessions/{sessionId}/metadata")
    public ApiResponse<List<DatabaseContracts.MetadataNode>> metadata(@PathVariable String sessionId, HttpServletRequest http) {
        UserDatabaseSession session = sessions.require(sessionId);
        List<DatabaseContracts.MetadataNode> nodes = new ArrayList<>();
        String sql = "SELECT type, name, tbl_name FROM sqlite_master WHERE type IN ('table','view','index','trigger') AND name NOT LIKE 'sqlite_%' ORDER BY type, name";
        try (Connection connection = session.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            while (result.next()) nodes.add(new DatabaseContracts.MetadataNode(result.getString(1), result.getString(2), result.getString(3)));
        } catch (Exception error) {
            throw new IllegalStateException("读取 SQLite 元数据失败", error);
        }
        return ok(nodes, traceId(http));
    }

    @GetMapping("/sessions/{sessionId}/metadata/{table}/columns")
    public ApiResponse<List<DatabaseContracts.MetadataColumn>> columns(@PathVariable String sessionId, @PathVariable String table, HttpServletRequest http) {
        return ok(mutations.columns(sessionId, table), traceId(http));
    }

    @PostMapping("/sessions/{sessionId}/mutations")
    public ApiResponse<Integer> mutate(@PathVariable String sessionId, @RequestBody DatabaseContracts.MutationRequest request, HttpServletRequest http) {
        return ok(mutations.mutate(sessionId, request, traceId(http)), traceId(http));
    }

    @PostMapping("/sessions/{sessionId}/queries")
    public ApiResponse<DatabaseContracts.QueryAccepted> query(@PathVariable String sessionId,
                                                               @RequestBody DatabaseContracts.QueryRequest request,
                                                               HttpServletRequest http) {
        String traceId = traceId(http);
        return ok(queries.submit(sessionId, request, traceId), traceId);
    }

    @GetMapping("/queries/{taskId}")
    public ApiResponse<DatabaseContracts.QueryResult> result(@PathVariable String taskId, HttpServletRequest http) {
        return ok(queries.requireResult(taskId), traceId(http));
    }

    @PostMapping("/queries/{taskId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String taskId, HttpServletRequest http) {
        queries.cancel(taskId);
        return ok(null, traceId(http));
    }

    @GetMapping(value = "/queries/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String taskId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return queries.events(taskId, lastEventId);
    }

    @PostMapping("/queries/{taskId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String taskId,
                                         @RequestParam(defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format) && !"xlsx".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("仅支持 csv 或 xlsx 导出");
        }
        byte[] content = queries.exportCsv(taskId);
        String filename = "toolhelper-query-" + taskId + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return ResponseEntity.ok().headers(headers).body(content);
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestSecurityFilter.TRACE_ID);
    }

    private static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(true, "OK", "操作成功", data, traceId);
    }
}
