package com.toolhelper.api.nodered;

import com.toolhelper.application.nodered.NodeRedContracts.NodeRedException;
import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public final class NodeRedExceptionHandler {
    @ExceptionHandler(NodeRedException.class)
    public ResponseEntity<ApiResponse<Void>> handle(NodeRedException error, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ApiResponse<>(false, error.code(), error.getMessage(), null, (String) request.getAttribute(RequestSecurityFilter.TRACE_ID)));
    }
}
