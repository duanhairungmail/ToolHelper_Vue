package com.toolhelper.api.database.security;

import com.toolhelper.infrastructure.InternalDbProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Component
public class SqlRiskClassifier {
    private static final Set<String> HIGH_RISK = Set.of("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "REPLACE", "VACUUM", "ATTACH", "DETACH");
    private final String internalPath;

    public SqlRiskClassifier(InternalDbProperties properties) {
        this.internalPath = properties.databasePath().toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    public Risk classify(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        String normalized = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ").trim();
        String keyword = normalized.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        boolean attach = "ATTACH".equals(keyword);
        String lowered = normalized.toLowerCase(Locale.ROOT);
        boolean internalPathAccess = lowered.contains(internalPath) || lowered.contains("toolhelper.db");
        if (attach && internalPathAccess) return new Risk(keyword, true, true);
        return new Risk(keyword, HIGH_RISK.contains(keyword), false);
    }

    public record Risk(String operation, boolean highRisk, boolean internalPathAccess) {}
}
