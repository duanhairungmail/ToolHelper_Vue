package com.toolhelper.infrastructure.kylin;

import com.toolhelper.application.kylin.KylinSessionService;
import org.springframework.scheduling.annotation.Scheduled;

public class KylinSessionCleanup {
    private final KylinSessionService sessions;
    public KylinSessionCleanup(KylinSessionService sessions) { this.sessions = sessions; }
    @Scheduled(fixedDelay = 60_000L)
    public void cleanup() { sessions.expireIdleSessions(); }
}
