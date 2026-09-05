package com.toolhelper.application.contract;

public record HealthData(String service, String version, String status, String traceId) {}
