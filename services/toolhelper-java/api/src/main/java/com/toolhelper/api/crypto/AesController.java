package com.toolhelper.api.crypto;

import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.crypto.AesContracts;
import com.toolhelper.application.crypto.AesService;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/java/crypto/aes")
public class AesController {
    private final AesService service;

    public AesController(AesService service) {
        this.service = service;
    }

    @PostMapping("/encrypt")
    public ApiResponse<AesContracts.AesResult> encrypt(@RequestBody AesContracts.AesRequest request,
                                                       HttpServletRequest http, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String traceId = traceId(http);
        return new ApiResponse<>(true, "OK", "AES 加密成功", service.encrypt(request), traceId);
    }

    @PostMapping("/decrypt")
    public ApiResponse<AesContracts.AesResult> decrypt(@RequestBody AesContracts.AesRequest request,
                                                       HttpServletRequest http, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String traceId = traceId(http);
        return new ApiResponse<>(true, "OK", "AES 解密成功", service.decrypt(request), traceId);
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestSecurityFilter.TRACE_ID);
    }
}
