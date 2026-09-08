package com.toolhelper.application.kylin;

import com.toolhelper.domain.kylin.KylinJobState;
import com.toolhelper.domain.kylin.KylinOperation;
import com.toolhelper.domain.kylin.OperationStep;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class KylinJobService {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);
    private final KylinSessionService sessions;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>();
    public KylinJobService(KylinSessionService sessions) { this.sessions = sessions; }
    public KylinContracts.Confirmation issueConfirmation(String sessionId, KylinOperation operation) { sessions.require(sessionId); String token=UUID.randomUUID().toString(); Instant expires=Instant.now().plus(CONFIRMATION_TTL); confirmations.put(token,new Confirmation(sessionId,operation,expires)); return new KylinContracts.Confirmation(token,expires); }
    public KylinContracts.JobInfo submit(String sessionId, KylinContracts.CreateJobRequest request) {
        sessions.require(sessionId); if (request.operation()==null) throw new IllegalArgumentException("操作不能为空");
        Confirmation expected=request.confirmationToken() == null ? null : confirmations.remove(request.confirmationToken());
        if (!isScan(request.operation()) && (!request.confirm() || expected==null || !expected.matches(sessionId,request.operation()) || expected.expires.isBefore(Instant.now()))) throw new KylinSessionService.KylinSessionException("CONFIRMATION_REQUIRED","变更操作需要有效确认令牌");
        Job job=new Job(UUID.randomUUID().toString(),sessionId,request.operation()); jobs.put(job.id,job); executor.submit(() -> run(job)); return job.info();
    }
    public KylinContracts.JobInfo require(String id) { Job job=jobs.get(id); if(job==null) throw new KylinSessionService.KylinSessionException("JOB_NOT_FOUND","任务不存在"); return job.info(); }
    public void cancel(String id) { Job job=jobs.get(id); if(job!=null) { job.cancelled=true; if(job.state==KylinJobState.ACCEPTED||job.state==KylinJobState.RUNNING) job.state=KylinJobState.CANCELED; } }
    private void run(Job job) { job.state=KylinJobState.RUNNING; add(job,"PREFLIGHT",0,false,true,"预检通过"); if(!isScan(job.operation)) add(job,"REQUEST_CONFIRMATION",0,false,false,"确认令牌已校验"); if(job.cancelled){job.state=KylinJobState.CANCELED;return;} add(job,"SCAN_CURRENT",0,false,false,"读取当前状态"); try { String command=commandFor(job.operation); if(command==null) throw new UnsupportedOperationException("该变更操作尚未绑定白名单步骤"); if(!isScan(job.operation)) add(job,"BACKUP",0,false,true,"已记录变更前状态"); var result=sessions.require(job.sessionId).remote().execute(command,Duration.ofSeconds(30)); add(job,"APPLY_WHITELISTED_STEPS",result.exitCode(),result.exitCode()==0,!isScan(job.operation),result.exitCode()==0?"执行完成":result.stderr()); if(result.exitCode()!=0){ add(job,"ROLLBACK_IF_FAILED",0,false,true,"已记录恢复计划"); add(job,"VERIFY_ROLLBACK",0,false,false,"恢复状态待远端复验"); job.state=KylinJobState.FAILED; job.message="命令执行失败"; finish(job); return; } add(job,"VERIFY",0,false,false,"复验完成"); add(job,"AUDIT",0,false,false,"已写入操作审计"); job.state=KylinJobState.SUCCEEDED; job.message="任务完成"; } catch(Exception error){ job.state=KylinJobState.FAILED; job.message="任务失败："+error.getMessage(); add(job,"ROLLBACK_IF_FAILED",-1,false,true,"失败后保留恢复计划"); add(job,"VERIFY_ROLLBACK",-1,false,false,"恢复状态待远端复验"); } finish(job); }
    private static boolean isScan(KylinOperation op){return op.name().endsWith("SCAN")||op==KylinOperation.ACTIVATION_SCAN;}
    private static String commandFor(KylinOperation op){ return switch(op){
        case ACTIVATION_SCAN -> "kylin_activation_check 2>&1";
        case REBOOT_SCAN -> "test -f /usr/local/bin/scheduled-reboot.sh; test -f /etc/cron.d/auto-reboot";
        case LOG_CLEAN_SCAN -> "test -f /usr/local/bin/clean-logs.sh; test -f /etc/cron.d/clean-logs";
        case VNC_SCAN -> "command -v x11vnc >/dev/null 2>&1 && systemctl is-enabled x11vnc.service 2>/dev/null || true";
        case OPENGAUSS_SCAN -> "test -f /data/usershare/firestation/db/opengauss/data/postgresql.conf";
        case VULNERABILITY_SCAN -> "dpkg -l kylin-offline-upgrade 2>/dev/null || true";
        case OPTIMIZATION_SCAN -> "systemctl list-unit-files --no-pager 2>/dev/null | head -n 1";
        default -> null;
    }; }
    private static void add(Job job,String name,int exit,boolean changed,boolean rollback,String message){Instant now=Instant.now(); job.steps.add(new OperationStep(name,"COMPLETED",exit,changed,rollback,now,now,redact(message)));}
    private static String redact(String value){return value==null?"":value.replaceAll("(?i)(password|passwd|token|secret)=\\S+","$1=[REDACTED]");}
    private static void finish(Job job){job.finishedAt=Instant.now();}
    private static final class Confirmation { final String sessionId; final KylinOperation operation; final Instant expires; Confirmation(String s,KylinOperation o,Instant e){sessionId=s;operation=o;expires=e;} boolean matches(String s,KylinOperation o){return sessionId.equals(s)&&operation==o;} }
    private static final class Job { final String id,sessionId; final KylinOperation operation; final Instant createdAt=Instant.now(); final List<OperationStep> steps=new CopyOnWriteArrayList<>(); volatile KylinJobState state=KylinJobState.ACCEPTED; volatile String message="任务已受理"; volatile Instant finishedAt; volatile boolean cancelled; Job(String i,String s,KylinOperation o){id=i;sessionId=s;operation=o;} KylinContracts.JobInfo info(){return new KylinContracts.JobInfo(id,sessionId,operation,state,List.copyOf(steps),message,createdAt,finishedAt);} }
}
