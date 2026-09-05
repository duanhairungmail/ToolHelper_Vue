package com.toolhelper.api;

import com.toolhelper.api.security.RequestSecurityFilter;
import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.contract.HealthData;
import com.toolhelper.domain.ServiceIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/actuator/health")
    public ApiResponse<HealthData> health(HttpServletRequest request) {
        String traceId = (String) request.getAttribute(RequestSecurityFilter.TRACE_ID);
        return new ApiResponse<>(true, "OK", "服务正常", new HealthData(
                ServiceIdentity.NAME, ServiceIdentity.VERSION, "UP", traceId), traceId);
    }
}
