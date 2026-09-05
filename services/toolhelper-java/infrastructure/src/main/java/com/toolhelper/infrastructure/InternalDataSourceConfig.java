package com.toolhelper.infrastructure;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;

@Configuration
public class InternalDataSourceConfig {
    @Bean(name = "internalDataSource")
    DataSource internalDataSource(InternalDbProperties properties) {
        try {
            Files.createDirectories(properties.databasePath().toAbsolutePath().normalize().getParent());
        } catch (IOException error) {
            throw new IllegalStateException("无法创建 ToolHelper 内部数据库目录", error);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5_000);
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + properties.databasePath().toAbsolutePath().normalize());
        return dataSource;
    }

    @Bean
    Flyway internalFlyway(@Qualifier("internalDataSource") DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
        // 迁移失败必须阻止 Spring 上下文就绪，不能静默重建内部库。
        flyway.migrate();
        return flyway;
    }

    @Bean(name = "internalJdbcTemplate")
    JdbcTemplate internalJdbcTemplate(@Qualifier("internalDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
