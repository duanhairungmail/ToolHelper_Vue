package com.toolhelper.api.database.workspace;

import com.toolhelper.api.database.security.DatabasePathPolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

@Component
public class UserDatabaseSessionFactory {
    private final DatabasePathPolicy pathPolicy;

    public UserDatabaseSessionFactory(DatabasePathPolicy pathPolicy) {
        this.pathPolicy = pathPolicy;
    }

    public UserDatabaseSession open(Path requested, char[] password) {
        Path path = pathPolicy.validate(requested);
        try {
            HikariConfig pool = new HikariConfig();
            pool.setJdbcUrl("jdbc:sqlite:" + path);
            pool.setPoolName("toolhelper-user-" + UUID.randomUUID());
            pool.setMaximumPoolSize(2);
            pool.setMinimumIdle(0);
            pool.setIdleTimeout(30_000);
            pool.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL;");
            return new UserDatabaseSession(UUID.randomUUID().toString(), path, new HikariDataSource(pool));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }
}
