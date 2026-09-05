package com.toolhelper.api.ping;

import com.toolhelper.api.security.RequestSecurityFilter;
import com.toolhelper.application.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/internal/v1/ping-jobs")
public class PingHistoryController {
    private final JdbcTemplate jdbc;

    public PingHistoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PutMapping("/{jobId}")
    @Transactional
    public ApiResponse<Void> upsert(@PathVariable String jobId, @RequestBody PingSnapshot snapshot, HttpServletRequest request) {
        if (!jobId.equals(snapshot.jobId())) throw new IllegalArgumentException("jobId 与快照不一致");
        String traceId = (String) request.getAttribute(RequestSecurityFilter.TRACE_ID);
        var summary = snapshot.summary();
        jdbc.update("""
                INSERT INTO group_ping_runs(id,input,status,target_count,created_at,completed_at,trace_id)
                VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)
                ON CONFLICT(id) DO UPDATE SET status=excluded.status,target_count=excluded.target_count,completed_at=excluded.completed_at,trace_id=excluded.trace_id
                """, jobId, "agent", snapshot.status(), summary.targetTotal(), traceId);
        jdbc.update("DELETE FROM group_ping_results WHERE job_id = ?", jobId);
        for (PingResult result : snapshot.results()) {
            jdbc.update("""
                    INSERT INTO group_ping_results(job_id,address,input_index,completion_index,status,attempts,success_count,average_delay_ms,packet_loss_percent,error)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, jobId, result.address(), result.inputIndex(), result.completionIndex(), result.status(), result.attempts(), result.successCount(), result.averageDelayMs(), result.packetLossPercent(), result.error());
        }
        return new ApiResponse<>(true, "OK", "Ping 历史已保存", null, traceId);
    }

    public record PingSnapshot(String jobId, String status, PingSummary summary, List<PingResult> results) {}
    public record PingSummary(int targetTotal, int online, int offline, int partialLoss, Integer averageDelayMs) {}
    public record PingResult(String address, int inputIndex, int completionIndex, String status, int attempts, int successCount, Integer averageDelayMs, int packetLossPercent, String error) {}
}
