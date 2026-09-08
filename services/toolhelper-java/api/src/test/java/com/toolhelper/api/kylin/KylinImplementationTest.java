package com.toolhelper.api.kylin;

import com.toolhelper.application.kylin.KylinContracts;
import com.toolhelper.application.kylin.KylinJobService;
import com.toolhelper.application.kylin.KylinRemoteClient;
import com.toolhelper.application.kylin.KylinSessionService;
import com.toolhelper.application.kylin.KylinCommandPolicy;
import com.toolhelper.domain.kylin.KylinOperation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KylinImplementationTest {
    @Test
    void x86SessionAndScanComplete() throws Exception {
        FakeClient client = new FakeClient("x86_64");
        KylinSessionService sessions = new KylinSessionService(client);
        KylinJobService jobs = new KylinJobService(sessions);
        KylinContracts.SessionInfo info = sessions.open(new KylinContracts.CreateSessionRequest("host", 22, "root", "pw", null, null, null));
        assertEquals("x86_64", info.architecture());
        assertTrue(info.sftpReady());
        KylinContracts.JobInfo accepted = jobs.submit(info.id(), new KylinContracts.CreateJobRequest(KylinOperation.ACTIVATION_SCAN, null, false));
        KylinContracts.JobInfo result = waitFor(jobs, accepted.id());
        assertEquals("SUCCEEDED", result.state().name());
        assertTrue(result.steps().stream().anyMatch(step -> "VERIFY".equals(step.name())));
        sessions.close(info.id());
    }

    @Test
    void nonX86SessionIsRejected() {
        KylinSessionService sessions = new KylinSessionService(new FakeClient("aarch64"));
        KylinSessionService.KylinSessionException error = assertThrows(KylinSessionService.KylinSessionException.class,
                () -> sessions.open(new KylinContracts.CreateSessionRequest("host", 22, "root", "pw", null, null, null)));
        assertEquals("UNSUPPORTED_ARCHITECTURE", error.code());
    }

    @Test
    void changeRequiresBoundConfirmation() throws Exception {
        KylinSessionService sessions = new KylinSessionService(new FakeClient("amd64"));
        KylinJobService jobs = new KylinJobService(sessions);
        String sessionId = sessions.open(new KylinContracts.CreateSessionRequest("host", 22, "root", "pw", null, null, null)).id();
        assertThrows(KylinSessionService.KylinSessionException.class,
                () -> jobs.submit(sessionId, new KylinContracts.CreateJobRequest(KylinOperation.REBOOT_DEPLOY, UUID.randomUUID().toString(), true)));
        KylinContracts.Confirmation confirmation = jobs.issueConfirmation(sessionId, KylinOperation.REBOOT_DEPLOY);
        KylinContracts.JobInfo result = waitFor(jobs, jobs.submit(sessionId, new KylinContracts.CreateJobRequest(KylinOperation.REBOOT_DEPLOY, confirmation.token(), true)).id());
        assertEquals("FAILED", result.state().name(), "未绑定具体部署脚本时必须显式失败，不能假装变更成功");
    }

    @Test
    void pathPolicyBlocksTraversalAndSymlink() throws Exception {
        Path root = Files.createTempDirectory("kylin-policy");
        assertThrows(SecurityException.class, () -> KylinCommandPolicy.managedPath(root, root.resolve("..").resolve("outside")));
        Path link = root.resolve("link");
        try { Files.createSymbolicLink(link, root); assertThrows(SecurityException.class, () -> KylinCommandPolicy.managedPath(root, link.resolve("file"))); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) { /* Windows 无权限创建链接时跳过该环境分支 */ }
    }

    private static KylinContracts.JobInfo waitFor(KylinJobService jobs, String id) throws InterruptedException {
        for (int i = 0; i < 40; i++) { KylinContracts.JobInfo info = jobs.require(id); if (!"ACCEPTED".equals(info.state().name()) && !"RUNNING".equals(info.state().name())) return info; Thread.sleep(25); }
        fail("任务未在测试时间内完成"); return jobs.require(id);
    }

    private static final class FakeClient implements KylinRemoteClient {
        private final String architecture;
        FakeClient(String architecture) { this.architecture = architecture; }
        @Override public RemoteSession connect(ConnectionOptions options) { return new RemoteSession() {
            @Override public RemoteResult execute(String command, Duration timeout) { return new RemoteResult(0, "ok", "", false); }
            @Override public void upload(String remotePath, byte[] content) {}
            @Override public String architecture() { return architecture; }
            @Override public boolean sftpReady() { return true; }
            @Override public void close() {}
        }; }
    }
}
