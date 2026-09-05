package com.toolhelper.application.contract;

import java.time.Instant;
import java.util.Map;

public record SseEvent(String eventId, String type, String jobId, Instant timestamp,
                       String traceId, Map<String, Object> payload) {}
