package com.toolhelper.api.electerm;

import com.toolhelper.application.electerm.ElectermContracts.*;
import com.toolhelper.application.electerm.ElectermLifecycleService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ElectermLifecycleServiceTest {
    @Test void failedUpdateRestoresVersionDataAndRunningState() {
        FakeHost host = new FakeHost(); host.running = true; host.rejectCandidate = true;
        ElectermLifecycleService service = new ElectermLifecycleService(host);
        ElectermException failure = assertThrows(ElectermException.class, () -> service.install(null));
        assertEquals("UPDATE_ROLLED_BACK", failure.code());
        assertEquals("old", host.release.id()); assertTrue(host.running);
        assertEquals(List.of("prepare", "stop", "backup:upgrade", "activate:new", "start:new", "stop", "restore:snapshot", "activate:old", "start:old"), host.calls);
    }
    @Test void invalidPackageLeavesExistingProcessUntouched() {
        FakeHost host = new FakeHost(); host.invalidPackage = true; host.running = true;
        assertThrows(ElectermException.class, () -> new ElectermLifecycleService(host).install(null));
        assertTrue(host.running); assertEquals(List.of("prepare"), host.calls);
    }
    @Test void stoppedInstallationReturnsToStoppedStateAfterHealthCheck() {
        FakeHost host = new FakeHost();
        Status result = new ElectermLifecycleService(host).install(null);
        assertEquals("STOPPED", result.state()); assertEquals("new", host.release.id());
        assertTrue(host.calls.contains("start:new"));
    }
    @Test void backupFailureBlocksActivationAndRestartsPriorProcess() {
        FakeHost host = new FakeHost(); host.running = true; host.backupFailure = true;
        assertThrows(ElectermException.class, () -> new ElectermLifecycleService(host).install(null));
        assertEquals("old", host.release.id()); assertTrue(host.running);
        assertFalse(host.calls.contains("activate:new"));
    }
    @Test void automaticBackupStopsAndResumesProcess() {
        FakeHost host = new FakeHost(); host.running = true;
        new ElectermLifecycleService(host).automaticBackup();
        assertEquals(List.of("stop", "backup:automatic", "start:old"), host.calls);
    }
    @Test void purgeRequiresSingleUseConfirmationAndDefaultUninstallPreservesData() {
        FakeHost host = new FakeHost(); ElectermLifecycleService service = new ElectermLifecycleService(host);
        assertThrows(ElectermException.class, () -> service.uninstall(true, "forged"));
        String token = service.requestPurge().token();
        service.uninstall(true, token); assertTrue(host.purged);
        assertThrows(ElectermException.class, () -> service.uninstall(true, token));
        host.purged = false; service.uninstall(false, null); assertFalse(host.purged);
    }
    @Test void concurrentStartsAreSerializedAndIdempotent() throws Exception {
        FakeHost host = new FakeHost(); ElectermLifecycleService service = new ElectermLifecycleService(host);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Status>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(threads.submit(service::start));
            for (Future<Status> result : results) assertEquals("RUNNING", result.get().state());
        }
        assertEquals(1, host.processStarts);
    }
    private static final class FakeHost implements Host {
        Release release = new Release("old", "5.3.25", "24.19.0");
        boolean running, rejectCandidate, invalidPackage, backupFailure, purged; int processStarts;
        List<String> calls = new ArrayList<>();
        @Override public Release current() { return release; }
        @Override public Release prepare(InstallRequest request) { calls.add("prepare"); if (invalidPackage) throw new ElectermException("SIGNATURE_INVALID", "bad signature"); return new Release("new", "5.3.26", "24.19.0"); }
        @Override public void activate(Release target) { release = target; calls.add("activate:" + (target == null ? "none" : target.id())); }
        @Override public Status status() { return new Status(running ? "RUNNING" : "STOPPED", null, Instant.now(), release == null ? null : release.version(), "24.19.0", 5577, "test", null, null); }
        @Override public Status start() { if (!running) { calls.add("start:" + release.id()); if (rejectCandidate && release.id().equals("new")) throw new ElectermException("START_TIMEOUT", "timeout"); running = true; processStarts++; } return status(); }
        @Override public Status stop() { calls.add("stop"); running = false; return status(); }
        @Override public String backup(String reason) { calls.add("backup:" + reason); if (backupFailure) throw new ElectermException("BACKUP_FAILED", "disk full"); return "snapshot"; }
        @Override public void restore(String id) { calls.add("restore:" + id); }
        @Override public boolean automaticBackupDue() { return true; }
        @Override public void uninstall(boolean purge) { purged = purge; }
        @Override public List<String> logs() { return List.copyOf(calls); }
    }
}
