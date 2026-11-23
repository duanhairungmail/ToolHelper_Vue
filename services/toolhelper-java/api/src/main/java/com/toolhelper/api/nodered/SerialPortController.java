package com.toolhelper.api.nodered;

import com.toolhelper.application.nodered.SerialPortCoordinator;
import com.toolhelper.application.nodered.NodeRedContracts.NodeRedException;
import com.toolhelper.infrastructure.RuntimeProperties;
import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/api/internal/v1/serial/leases")
public final class SerialPortController {
    private final SerialPortCoordinator coordinator;
    private final RuntimeProperties runtime;
    public SerialPortController(SerialPortCoordinator coordinator, RuntimeProperties runtime) { this.coordinator = coordinator; this.runtime = runtime; }
    public record Acquire(String port, long pid) {}
    @GetMapping public ApiResponse<List<SerialPortCoordinator.Lease>> list(HttpServletRequest request) { return ok(coordinator.list(), request); }
    @PostMapping public ApiResponse<SerialPortCoordinator.Lease> acquire(@RequestBody Acquire body, HttpServletRequest request) {
        return ok(coordinator.acquire(body.port(), owner(request), body.pid()), request);
    }
    @DeleteMapping("/{id}") public ApiResponse<Void> release(@PathVariable String id, HttpServletRequest request) {
        coordinator.release(id, owner(request)); return ok(null, request);
    }
    private String owner(HttpServletRequest request) {
        String presented = request.getHeader("Authorization").substring(7);
        if (coordinator.matchesNodeRedToken(presented)) return "Node-RED";
        if (MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), runtime.internalToken().getBytes(StandardCharsets.UTF_8))) return "C# Agent";
        throw new NodeRedException("SERIAL_OWNER_INVALID", "串口租约只允许受管服务申请，网页只可查看");
    }
    private static <T> ApiResponse<T> ok(T value, HttpServletRequest request) { return new ApiResponse<>(true, "OK", "操作完成", value, (String)request.getAttribute(RequestSecurityFilter.TRACE_ID)); }
}
