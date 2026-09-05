package com.toolhelper.api.database;

import java.sql.SQLException;
import java.util.Locale;

/**
 * 将 SQLite/JDBC 的驱动差异收敛为稳定错误码。
 * 只把确定的 SQLite 错误归类，其余异常保留为通用数据库操作失败。
 */
public final class DatabaseErrorClassifier {
    private DatabaseErrorClassifier() {}

    public static DatabaseOperationException classify(String message, Throwable error) {
        return classify(message, error, DatabaseErrorCode.DATABASE_OPERATION_FAILED);
    }

    public static DatabaseOperationException classify(String message, Throwable error, DatabaseErrorCode fallback) {
        if (error instanceof DatabaseOperationException databaseError) return databaseError;
        DatabaseErrorCode code = classifyCode(error, fallback);
        return new DatabaseOperationException(code, message, error);
    }

    public static DatabaseErrorCode classifyCode(Throwable error) {
        return classifyCode(error, DatabaseErrorCode.DATABASE_OPERATION_FAILED);
    }

    public static DatabaseErrorCode classifyCode(Throwable error, DatabaseErrorCode fallback) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String state = normalize(sqlException.getSQLState());
                if (state.contains("BUSY") || state.contains("LOCKED")) return DatabaseErrorCode.SQLITE_LOCKED;
            }
            String text = normalize(current.getMessage());
            if (containsAny(text, "SQLITE_BUSY", "SQLITE_LOCKED", "DATABASE IS LOCKED", "DATABASE TABLE IS LOCKED")) {
                return DatabaseErrorCode.SQLITE_LOCKED;
            }
            if (containsAny(text, "SQLITE_READONLY", "READONLY", "READ-ONLY", "READ ONLY")) {
                return DatabaseErrorCode.DATABASE_READ_ONLY;
            }
            if (containsAny(text, "SQLITE_CORRUPT", "MALFORMED", "NOT A DATABASE", "FILE IS ENCRYPTED")) {
                return DatabaseErrorCode.DATABASE_CORRUPT;
            }
            if (containsAny(text, "SQLITE_FULL", "DISK FULL", "DATABASE OR DISK IS FULL", "NO SPACE LEFT")) {
                return DatabaseErrorCode.DISK_FULL;
            }
            current = current.getCause();
        }
        return fallback;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
