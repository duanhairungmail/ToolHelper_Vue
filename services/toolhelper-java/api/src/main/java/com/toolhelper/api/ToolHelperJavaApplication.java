package com.toolhelper.api;

import com.toolhelper.infrastructure.RuntimeProperties;
import com.toolhelper.infrastructure.InternalDbProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

@SpringBootApplication(scanBasePackages = "com.toolhelper")
@EnableScheduling
public class ToolHelperJavaApplication {
    public static void main(String[] args) {
        SpringApplication.run(ToolHelperJavaApplication.class, args);
    }

    @Bean
    RuntimeProperties runtimeProperties(Environment environment) {
        String origins = environment.getProperty("toolhelper.allowed-origins", "http://127.0.0.1:5173,http://localhost:5173");
        return new RuntimeProperties(environment.getProperty("toolhelper.session-token", ""),
                environment.getProperty("toolhelper.internal-token", ""),
                java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList());
    }

    @Bean
    InternalDbProperties internalDbProperties(Environment environment) {
        String localAppData = environment.getProperty("LOCALAPPDATA", System.getenv("LOCALAPPDATA"));
        Path defaultPath = localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData, "ToolHelper", "data", "toolhelper.db")
                : Path.of(System.getProperty("user.home"), ".local", "share", "ToolHelper", "data", "toolhelper.db");
        String configured = environment.getProperty("toolhelper.internal-db-path");
        return new InternalDbProperties(configured == null || configured.isBlank() ? defaultPath : Path.of(configured));
    }
}
