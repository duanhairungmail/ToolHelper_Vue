package com.toolhelper.api.nodered;

import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.nodered.*;
import com.toolhelper.application.nodered.NodeRedContracts.*;
import com.toolhelper.infrastructure.nodered.*;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/integrations/nodered")
public final class NodeRedController {
    private final NodeRedLifecycleService lifecycle;
    private final NodeRedPackageService packages;
    private final NodeApprovalService nodes;
    private final NodeRedProcessRegistry processes;
    public NodeRedController(NodeRedLifecycleService lifecycle, NodeRedPackageService packages, NodeApprovalService nodes, NodeRedProcessRegistry processes) {
        this.lifecycle = lifecycle; this.packages = packages; this.nodes = nodes; this.processes = processes;
    }
    @GetMapping("/status") public ApiResponse<Status> status(HttpServletRequest request) { return ok(lifecycle.status(), request); }
    @PostMapping(value = {"/install", "/updates"}, consumes = "application/json")
    public ApiResponse<Status> install(@RequestBody InstallRequest body, HttpServletRequest request) { return ok(lifecycle.install(body), request); }
    @PostMapping(value = {"/install", "/updates"}, consumes = "multipart/form-data")
    public ApiResponse<Status> offline(@RequestParam("package") MultipartFile file, @RequestParam boolean confirmed, HttpServletRequest request) throws IOException {
        if (!confirmed) throw new NodeRedException("CONFIRMATION_REQUIRED", "请先确认导入签名运行包");
        String name; try (var input = file.getInputStream()) { name = packages.importOffline(input); }
        try { return ok(lifecycle.install(new InstallRequest(name, true)), request); }
        finally { packages.discardUpload(name); }
    }
    @PostMapping("/start") public ApiResponse<Status> start(HttpServletRequest request) { return ok(lifecycle.start(), request); }
    @PostMapping("/stop") public ApiResponse<Status> stop(HttpServletRequest request) { return ok(lifecycle.stop(), request); }
    @PostMapping("/restart") public ApiResponse<Status> restart(HttpServletRequest request) { return ok(lifecycle.restart(), request); }
    @DeleteMapping("/runtime") public ApiResponse<Status> uninstall(@RequestParam boolean confirmed, HttpServletRequest request) {
        if (!confirmed) throw new NodeRedException("CONFIRMATION_REQUIRED", "卸载运行时需明确确认");
        return ok(lifecycle.uninstall(), request);
    }
    @PostMapping("/backups") public ApiResponse<String> backup(HttpServletRequest request) { return ok(lifecycle.backup(), request); }
    public record Restore(String id, boolean confirmed) {}
    @PostMapping("/restore") public ApiResponse<Status> restore(@RequestBody Restore body, HttpServletRequest request) { return ok(lifecycle.restore(body.id(), body.confirmed()), request); }
    @PostMapping(value = "/nodes/review", consumes = "multipart/form-data")
    public ApiResponse<NodeApprovalService.Inspection> inspect(@RequestParam("package") MultipartFile file, @RequestParam boolean confirmed, HttpServletRequest request) throws IOException {
        if (!confirmed) throw new NodeRedException("CONFIRMATION_REQUIRED", "请先确认导入待审节点包");
        String name; try (var input = file.getInputStream()) { name = packages.importOffline(input); }
        try { return ok(nodes.inspect(new InstallRequest(name, true)), request); } finally { packages.discardUpload(name); }
    }
    public record Approval(String reviewer, String reason) {}
    public record Confirmation(boolean confirmed) {}
    @PostMapping("/nodes/{id}/approve") public ApiResponse<NodeApprovalService.Inspection> approve(@PathVariable String id, @RequestBody Approval body, HttpServletRequest request) { return ok(nodes.approve(id, body.reviewer(), body.reason()), request); }
    @PostMapping("/nodes/{id}/install") public ApiResponse<Status> installNodes(@PathVariable String id, @RequestBody Confirmation body, HttpServletRequest request) { return ok(nodes.install(id, body.confirmed()), request); }
    @DeleteMapping("/nodes/{id}") public ApiResponse<Void> cancel(@PathVariable String id, HttpServletRequest request) { nodes.cancel(id); return ok(null, request); }

    @GetMapping(value = "/events", produces = "text/event-stream")
    public SseEmitter events(@RequestHeader(value = "Last-Event-ID", defaultValue = "0") String lastId) {
        long after;
        try { after = Long.parseLong(lastId); } catch (NumberFormatException error) { throw new NodeRedException("EVENT_ID_INVALID", "事件编号无效"); }
        SseEmitter emitter = new SseEmitter(60_000L); AtomicBoolean complete = new AtomicBoolean();
        emitter.onCompletion(() -> complete.set(true)); emitter.onTimeout(() -> complete.set(true)); emitter.onError(error -> complete.set(true));
        long cursor = after;
        Thread.ofVirtual().name("nodered-events").start(() -> {
            long current = cursor;
            try {
                for (int i = 0; i < 55 && !complete.get(); i++) {
                    for (Event event : processes.eventsAfter(current)) { emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event)); current = Long.parseLong(event.eventId()); }
                    emitter.send(SseEmitter.event().comment("keepalive")); Thread.sleep(1000);
                }
                emitter.complete();
            } catch (IOException error) { emitter.complete(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); emitter.complete(); }
        });
        return emitter;
    }
    private static <T> ApiResponse<T> ok(T value, HttpServletRequest request) { return new ApiResponse<>(true, "OK", "操作完成", value, (String) request.getAttribute(RequestSecurityFilter.TRACE_ID)); }
}
