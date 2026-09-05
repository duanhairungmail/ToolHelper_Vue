package com.toolhelper.api.database.workspace;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;

public final class UserDatabaseSession implements AutoCloseable {
    private final String id;
    private final Path path;
    private final HikariDataSource dataSource;
    private final Instant openedAt;
    private volatile Instant lastUsedAt;

    public UserDatabaseSession(String id, Path path, HikariDataSource dataSource) {
        this.id = id;
        this.path = path;
        this.dataSource = dataSource;
        this.openedAt = Instant.now();
        this.lastUsedAt = openedAt;
    }

    public String id() { return id; }
    public Path path() { return path; }
    public DataSource dataSource() { lastUsedAt = Instant.now(); return dataSource; }
    public Instant openedAt() { return openedAt; }
    public Instant lastUsedAt() { return lastUsedAt; }

    @Override
    public void close() {
        dataSource.close();
    }
}
