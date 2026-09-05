package com.toolhelper.api;

import com.toolhelper.infrastructure.RuntimeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.toolhelper")
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
}
