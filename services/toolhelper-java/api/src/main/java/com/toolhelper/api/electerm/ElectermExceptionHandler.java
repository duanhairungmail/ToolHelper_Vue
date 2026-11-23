package com.toolhelper.api.electerm;

import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.electerm.ElectermContracts.ElectermException;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = ElectermController.class)
public class ElectermExceptionHandler {
    @ExceptionHandler(ElectermException.class)
    ResponseEntity<ApiResponse<Void>> handle(ElectermException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>(false, error.code(), error.getMessage(), null, (String) request.getAttribute(RequestSecurityFilter.TRACE_ID)));
    }
}
