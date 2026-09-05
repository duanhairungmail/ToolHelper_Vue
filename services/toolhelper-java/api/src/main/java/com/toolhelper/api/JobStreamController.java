package com.toolhelper.api;

import com.toolhelper.api.security.RequestSecurityFilter;
import com.toolhelper.application.contract.SseEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;

@RestController
public class JobStreamController {
    @GetMapping(value = "/api/jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String jobId, @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(10_000L);
        if (!"1".equals(lastEventId)) {
            String traceId = (String) request.getAttribute(RequestSecurityFilter.TRACE_ID);
            SseEvent event = new SseEvent("1", "job.completed", jobId, Instant.now(), traceId,
                    Map.of("level", "INFO", "message", "任务已完成"));
            try {
                emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event));
                emitter.complete();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        } else {
            emitter.complete();
        }
        return emitter;
    }
}
