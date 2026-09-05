package com.toolhelper.api.database.workspace;

import com.toolhelper.api.database.audit.InternalAuditRepository;
import com.toolhelper.application.contract.DatabaseContracts;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class DatabaseMutationService {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final UserDatabaseSessionRegistry sessions;
    private final InternalAuditRepository audit;

    public DatabaseMutationService(UserDatabaseSessionRegistry sessions, InternalAuditRepository audit) {
        this.sessions = sessions;
        this.audit = audit;
    }

    public List<DatabaseContracts.MetadataColumn> columns(String sessionId, String table) {
        requireIdentifier(table, "表名");
        UserDatabaseSession session = sessions.require(sessionId);
        List<DatabaseContracts.MetadataColumn> columns = new ArrayList<>();
        try (Connection connection = session.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(\"" + table + "\")"); ResultSet result = statement.executeQuery()) {
            while (result.next()) columns.add(new DatabaseContracts.MetadataColumn(result.getString("name"), result.getString("type"), result.getInt("pk") > 0, result.getInt("notnull") > 0));
        } catch (Exception error) {
            throw new IllegalStateException("读取表列失败", error);
        }
        return columns;
    }

    public int mutate(String sessionId, DatabaseContracts.MutationRequest request, String traceId) {
        if (!request.confirmHighRisk()) throw new IllegalArgumentException("变更集需要二次确认");
        requireIdentifier(request.table(), "表名");
        requireIdentifier(request.primaryKeyColumn(), "主键列名");
        if (request.changes() == null || request.changes().isEmpty()) throw new IllegalArgumentException("变更集不能为空");
        List<DatabaseContracts.MetadataColumn> columns = columns(sessionId, request.table());
        long primaryKeyCount = columns.stream().filter(DatabaseContracts.MetadataColumn::primaryKey).count();
        if (primaryKeyCount != 1 || columns.stream().noneMatch(column -> column.primaryKey() && column.name().equals(request.primaryKeyColumn()))) {
            throw new IllegalArgumentException("只允许编辑具有唯一主键的结果集");
        }
        for (String column : request.changes().keySet()) requireIdentifier(column, "变更列名");
        String assignments = String.join(", ", request.changes().keySet().stream().map(column -> quote(column) + " = ?").toList());
        String sql = "UPDATE " + quote(request.table()) + " SET " + assignments + " WHERE " + quote(request.primaryKeyColumn()) + " = ?";
        UserDatabaseSession session = sessions.require(sessionId);
        long started = System.currentTimeMillis();
        try (Connection connection = session.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            int index = 1;
            for (Object value : request.changes().values()) statement.setObject(index++, value);
            statement.setObject(index, request.primaryKeyValue());
            int updated = statement.executeUpdate();
            connection.commit();
            audit.record(sessionId, "UPDATE", System.currentTimeMillis() - started, updated, "OK", traceId);
            return updated;
        } catch (Exception error) {
            audit.record(sessionId, "UPDATE", System.currentTimeMillis() - started, 0, "MUTATION_FAILED", traceId);
            throw new IllegalStateException("变更集提交失败", error);
        }
    }

    private static void requireIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException(label + "不合法");
    }

    private static String quote(String identifier) { return "\"" + identifier + "\""; }
}
