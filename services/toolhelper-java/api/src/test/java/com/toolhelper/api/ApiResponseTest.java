package com.toolhelper.api;

import com.toolhelper.application.contract.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiResponseTest {
    @Test
    void preservesTraceIdAndSuccessContract() {
        ApiResponse<String> response = new ApiResponse<>(true, "OK", "ok", "data", "trace-1");
        assertTrue(response.success());
        assertEquals("trace-1", response.traceId());
    }
}
