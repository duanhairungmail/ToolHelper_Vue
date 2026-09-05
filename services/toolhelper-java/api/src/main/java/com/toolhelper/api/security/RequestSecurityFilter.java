package com.toolhelper.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.infrastructure.RuntimeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Component
public class RequestSecurityFilter extends OncePerRequestFilter {
    public static final String TRACE_ID = "toolhelper.traceId";
    private final RuntimeProperties properties;
    private final ObjectMapper objectMapper;

    public RequestSecurityFilter(RuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = traceId(request.getHeader("X-Trace-Id"));
        request.setAttribute(TRACE_ID, traceId);
        response.setHeader("X-Trace-Id", traceId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");

        if (request.getRemoteAddr() == null || !(request.getRemoteAddr().equals("127.0.0.1") || request.getRemoteAddr().equals("::1"))) {
            reject(response, 403, "LOOPBACK_REQUIRED", "仅允许回环请求", traceId);
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || properties.allowedOrigins() == null || !properties.allowedOrigins().contains(origin)) {
            reject(response, 403, "ORIGIN_INVALID", "请求来源不在白名单", traceId);
            return;
        }
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Trace-Id, Last-Event-ID");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        response.setHeader("Vary", "Origin");
        if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        String authorization = request.getHeader("Authorization");
        String presented = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (presented.isEmpty()) {
            reject(response, 401, "AUTH_REQUIRED", "缺少服务令牌", traceId);
            return;
        }
        byte[] expected = properties.internalToken().getBytes(StandardCharsets.UTF_8);
        if (!matches(presented, properties.sessionToken()) && !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
            reject(response, 401, "AUTH_INVALID", "服务令牌无效", traceId);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(String presented, String expected) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, int status, String code, String message, String traceId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ApiResponse<>(false, code, message, null, traceId));
    }

    private static String traceId(String candidate) {
        return candidate != null && candidate.length() <= 128 && candidate.matches("[A-Za-z0-9._:-]+")
                ? candidate : UUID.randomUUID().toString().replace("-", "");
    }
}
