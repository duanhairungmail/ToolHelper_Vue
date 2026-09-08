package com.toolhelper.infrastructure.kylin;

import com.toolhelper.application.kylin.KylinJobService;
import com.toolhelper.application.kylin.KylinRemoteClient;
import com.toolhelper.application.kylin.KylinSessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KylinBeansConfig {
    @Bean KylinSessionService kylinSessionService(KylinRemoteClient client) { return new KylinSessionService(client); }
    @Bean KylinJobService kylinJobService(KylinSessionService sessions) { return new KylinJobService(sessions); }
    @Bean KylinSessionCleanup kylinSessionCleanup(KylinSessionService sessions) { return new KylinSessionCleanup(sessions); }
}
