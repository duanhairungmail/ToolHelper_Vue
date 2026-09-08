package com.toolhelper.api.kylin;

import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.kylin.KylinContracts;
import com.toolhelper.application.kylin.KylinJobService;
import com.toolhelper.application.kylin.KylinSessionService;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/java/kylin/sessions")
public class KylinSessionController {
    private final KylinSessionService sessions; private final KylinJobService jobs;
    public KylinSessionController(KylinSessionService sessions,KylinJobService jobs){this.sessions=sessions;this.jobs=jobs;}
    @PostMapping public ApiResponse<KylinContracts.SessionInfo> open(@RequestBody KylinContracts.CreateSessionRequest request,HttpServletRequest http){return ok(sessions.open(request),http);}
    @GetMapping("/{id}") public ApiResponse<KylinContracts.SessionInfo> get(@PathVariable String id,HttpServletRequest http){return ok(sessions.require(id).info(),http);}
    @DeleteMapping("/{id}") public ApiResponse<Void> close(@PathVariable String id,HttpServletRequest http){sessions.close(id);return ok(null,http);}
    @PostMapping("/{id}/confirmations") public ApiResponse<KylinContracts.Confirmation> confirm(@PathVariable String id,@RequestParam com.toolhelper.domain.kylin.KylinOperation operation,HttpServletRequest http){return ok(jobs.issueConfirmation(id,operation),http);}
    private static <T> ApiResponse<T> ok(T data,HttpServletRequest request){return new ApiResponse<>(true,"OK","操作成功",data,(String)request.getAttribute(RequestSecurityFilter.TRACE_ID));}
}
