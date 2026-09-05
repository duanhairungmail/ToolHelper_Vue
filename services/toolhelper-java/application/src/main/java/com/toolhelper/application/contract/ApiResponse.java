package com.toolhelper.application.contract;

public record ApiResponse<T>(boolean success, String code, String message, T data, String traceId) {}
