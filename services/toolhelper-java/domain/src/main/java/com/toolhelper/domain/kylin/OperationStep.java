package com.toolhelper.domain.kylin;

import java.time.Instant;

public record OperationStep(String name, String status, Integer exitCode, boolean changed,
                            boolean rollbackAvailable, Instant startedAt, Instant endedAt,
                            String message) {}
