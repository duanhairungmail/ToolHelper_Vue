package com.toolhelper.api.database;

import com.toolhelper.api.database.workspace.DatabaseQueryTaskManager;
import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class DatabaseExceptionHandler {
    @ExceptionHandler(DatabaseQueryTaskManager.HighRiskConfirmationRequiredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> highRisk(DatabaseQueryTaskManager.HighRiskConfirmationRequiredException error, HttpServletRequest request) {
        return new ApiResponse<>(false, "HIGH_RISK_CONFIRMATION_REQUIRED", error.getMessage(), null, traceId(request));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalid(RuntimeException error, HttpServletRequest request) {
        return new ApiResponse<>(false, "DATABASE_REQUEST_INVALID", error.getMessage(), null, traceId(request));
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestSecurityFilter.TRACE_ID);
    }
}
