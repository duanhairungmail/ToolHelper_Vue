package com.toolhelper.api.kylin;

import com.toolhelper.application.contract.ApiResponse;
import com.toolhelper.application.kylin.KylinContracts;
import com.toolhelper.application.kylin.KylinJobService;
import com.toolhelper.api.security.RequestSecurityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;

@RestController
public class KylinJobController {
    private final KylinJobService jobs;
    public KylinJobController(KylinJobService jobs){this.jobs=jobs;}
    @PostMapping("/api/java/kylin/sessions/{sessionId}/jobs") public ApiResponse<KylinContracts.JobInfo> submit(@PathVariable String sessionId,@RequestBody KylinContracts.CreateJobRequest request,HttpServletRequest http){return ok(jobs.submit(sessionId,request),http);}
    @GetMapping("/api/java/jobs/{jobId}") public ApiResponse<KylinContracts.JobInfo> get(@PathVariable String jobId,HttpServletRequest http){return ok(jobs.require(jobId),http);}
    @PostMapping("/api/java/jobs/{jobId}/cancel") public ApiResponse<Void> cancel(@PathVariable String jobId,HttpServletRequest http){jobs.cancel(jobId);return ok(null,http);}
    @GetMapping(value="/api/java/jobs/{jobId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter events(@PathVariable String jobId){SseEmitter emitter=new SseEmitter(10_000L); try { emitter.send(SseEmitter.event().id("1").name("job.snapshot").data(jobs.require(jobId))); emitter.complete(); } catch(Exception e){emitter.completeWithError(e);} return emitter; }
    private static <T> ApiResponse<T> ok(T data,HttpServletRequest request){return new ApiResponse<>(true,"OK","操作成功",data,(String)request.getAttribute(RequestSecurityFilter.TRACE_ID));}
}
