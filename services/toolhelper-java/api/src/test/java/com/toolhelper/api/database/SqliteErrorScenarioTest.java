package com.toolhelper.api.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteErrorScenarioTest {
    @Test
    void classifiesARealExclusiveWriterConflict() throws Exception {
        Path file = Files.createTempFile("toolhelper-lock-", ".sqlite");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + file);
             Connection blocked = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            writer.createStatement().executeUpdate("CREATE TABLE lock_test (id INTEGER PRIMARY KEY, value TEXT)");
            writer.setAutoCommit(false);
            writer.createStatement().executeUpdate("INSERT INTO lock_test(value) VALUES ('held')");
            blocked.createStatement().execute("PRAGMA busy_timeout=100");
            SQLException error = assertThrows(SQLException.class,
                    () -> blocked.createStatement().executeUpdate("INSERT INTO lock_test(value) VALUES ('blocked')"));
            assertEquals(DatabaseErrorCode.SQLITE_LOCKED, DatabaseErrorClassifier.classifyCode(error));
            writer.rollback();
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
