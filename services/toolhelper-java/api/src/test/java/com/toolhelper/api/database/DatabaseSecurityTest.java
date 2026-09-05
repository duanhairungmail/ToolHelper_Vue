package com.toolhelper.api.database;

import com.toolhelper.api.database.security.SqlRiskClassifier;
import com.toolhelper.infrastructure.InternalDbProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSecurityTest {
    @Test
    void classifiesDangerousSqlAndBlocksInternalAttachTarget() {
        SqlRiskClassifier classifier = new SqlRiskClassifier(new InternalDbProperties(Path.of("C:/ToolHelper/data/toolhelper.db")));
        assertTrue(classifier.classify("UPDATE users SET name='x'").highRisk());
        assertTrue(classifier.classify("ATTACH 'toolhelper.db' AS internal").internalPathAccess());
    }
}
