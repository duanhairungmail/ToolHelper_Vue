package com.toolhelper.api.database;

import com.toolhelper.api.database.workspace.DatabaseQueryTaskManager;
import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(DatabaseOperationException.class)
    ResponseEntity<ApiResponse<Void>> database(DatabaseOperationException error, HttpServletRequest request) {
        ApiResponse<Void> response = new ApiResponse<>(false, error.code().value(), error.getMessage(), null, traceId(request));
        return ResponseEntity.status(error.code().httpStatus()).body(response);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalid(RuntimeException error, HttpServletRequest request) {
        DatabaseErrorCode fallback = error instanceof IllegalArgumentException
                ? DatabaseErrorCode.DATABASE_REQUEST_INVALID
                : DatabaseErrorCode.DATABASE_OPERATION_FAILED;
        DatabaseOperationException classified = DatabaseErrorClassifier.classify(error.getMessage(), error, fallback);
        return new ApiResponse<>(false, classified.code().value(), classified.getMessage(), null, traceId(request));
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestSecurityFilter.TRACE_ID);
    }
}
