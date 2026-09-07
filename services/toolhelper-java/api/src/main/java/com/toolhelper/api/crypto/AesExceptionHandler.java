package com.toolhelper.api.crypto;

import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.crypto.AesOperationException;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AesExceptionHandler {
    @ExceptionHandler(AesOperationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> invalid(AesOperationException error, HttpServletRequest request) {
        return new ApiResponse<>(false, error.code(), error.getMessage(), null,
                (String) request.getAttribute(RequestSecurityFilter.TRACE_ID));
    }
}
