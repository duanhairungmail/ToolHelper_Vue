package com.toolhelper.api.electerm;

import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.electerm.ElectermContracts.*;
import com.toolhelper.application.electerm.ElectermLifecycleService;
import com.toolhelper.api.security.RequestSecurityFilter;
import com.toolhelper.infrastructure.electerm.ElectermPackageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/integrations/electerm")
public class ElectermController {
    private final ElectermLifecycleService service;
    private final ElectermPackageService packages;
    public ElectermController(ElectermLifecycleService service, ElectermPackageService packages) { this.service = service; this.packages = packages; }
    @GetMapping("/status") public ApiResponse<Status> status(HttpServletRequest request) { return ok(service.status(), request); }
    @PostMapping(value = "/install", consumes = {"application/json", "application/octet-stream", "*/*"}) public ApiResponse<Status> install(@RequestBody(required = false) InstallRequest body, HttpServletRequest request) { return ok(service.install(body), request); }
    @PostMapping(value = "/install", consumes = "multipart/form-data") public ApiResponse<Status> importOffline(@RequestParam("package") MultipartFile file, HttpServletRequest request) throws IOException {
        String name;
        try (var input = file.getInputStream()) { name = packages.importOffline(input); }
        try { return ok(service.install(new InstallRequest(name)), request); }
        finally { packages.discardUpload(name); }
    }
    @PostMapping("/start") public ApiResponse<Status> start(HttpServletRequest request) { return ok(service.start(), request); }
    @PostMapping("/stop") public ApiResponse<Status> stop(HttpServletRequest request) { return ok(service.stop(), request); }
    @PostMapping("/restart") public ApiResponse<Status> restart(HttpServletRequest request) { return ok(service.restart(), request); }
    @GetMapping("/logs") public ApiResponse<List<String>> logs(HttpServletRequest request) { return ok(service.logs(), request); }
    @PostMapping("/purge-confirmation") public ApiResponse<PurgeConfirmation> confirm(HttpServletRequest request) { return ok(service.requestPurge(), request); }
    public record UninstallRequest(boolean purgeData, String confirmationToken) {}
    @PostMapping("/uninstall") public ApiResponse<Status> uninstall(@RequestBody(required = false) UninstallRequest body, HttpServletRequest request) { return ok(service.uninstall(body != null && body.purgeData(), body == null ? null : body.confirmationToken()), request); }
    private static <T> ApiResponse<T> ok(T data, HttpServletRequest request) { return new ApiResponse<>(true, "OK", "操作完成", data, (String) request.getAttribute(RequestSecurityFilter.TRACE_ID)); }
}
