package com.toolhelper.api.database;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseErrorClassifierTest {
    @Test
    void mapsSqliteFailureMessagesToStableCodes() {
        assertEquals(DatabaseErrorCode.SQLITE_LOCKED,
                DatabaseErrorClassifier.classifyCode(new SQLException("database is locked")));
        assertEquals(DatabaseErrorCode.DATABASE_READ_ONLY,
                DatabaseErrorClassifier.classifyCode(new SQLException("attempt to write a readonly database")));
        assertEquals(DatabaseErrorCode.DATABASE_CORRUPT,
                DatabaseErrorClassifier.classifyCode(new SQLException("database disk image is malformed")));
        assertEquals(DatabaseErrorCode.DISK_FULL,
                DatabaseErrorClassifier.classifyCode(new SQLException("database or disk is full")));
    }

    @Test
    void searchesNestedCauses() {
        SQLException sqliteError = new SQLException("SQLITE_BUSY: database is locked");
        assertEquals(DatabaseErrorCode.SQLITE_LOCKED,
                DatabaseErrorClassifier.classifyCode(new IllegalStateException("query failed", sqliteError)));
    }
}
