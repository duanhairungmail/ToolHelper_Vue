package com.toolhelper.api.kylin;

import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.kylin.KylinSessionService;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class KylinExceptionHandler {
    @ExceptionHandler(KylinSessionService.KylinSessionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handle(KylinSessionService.KylinSessionException error,HttpServletRequest request){return new ApiResponse<>(false,error.code(),error.getMessage(),null,(String)request.getAttribute(RequestSecurityFilter.TRACE_ID));}
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegal(IllegalArgumentException error,HttpServletRequest request){return new ApiResponse<>(false,"INVALID_REQUEST",error.getMessage(),null,(String)request.getAttribute(RequestSecurityFilter.TRACE_ID));}
}
