package com.toolhelper.api.nodered;

import com.toolhelper.application.nodered.NodeRedContracts.*;
import com.toolhelper.application.nodered.NodeRedLifecycleService;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeRedLifecycleServiceTest {
    @Test void cancelledInstallAndRestoreHaveNoHostSideEffects() {
        FakeHost host = new FakeHost(); var service = new NodeRedLifecycleService(host);
        assertEquals("CONFIRMATION_REQUIRED", assertThrows(NodeRedException.class, () -> service.install(new InstallRequest(null, false))).code());
        assertThrows(NodeRedException.class, () -> service.restore("backup", false));
        assertTrue(host.calls.isEmpty());
    }
    @Test void failedUpdateRestoresVersionDataAndRunningState() {
        FakeHost host = new FakeHost(); host.running = true; host.rejectCandidate = true;
        var service = new NodeRedLifecycleService(host);
        assertEquals("UPDATE_ROLLED_BACK", assertThrows(NodeRedException.class, () -> service.install(new InstallRequest(null, true))).code());
        assertEquals("old", host.release.id()); assertTrue(host.running);
        assertEquals(List.of("prepare", "stop", "backup:upgrade", "activate:new", "start:new", "stop", "restore:snapshot", "activate:old", "start:old"), host.calls);
    }
    @Test void invalidPackageLeavesExistingProcessUntouched() {
        FakeHost host = new FakeHost(); host.invalidPackage = true; host.running = true;
        assertThrows(NodeRedException.class, () -> new NodeRedLifecycleService(host).install(new InstallRequest(null, true)));
        assertTrue(host.running); assertEquals(List.of("prepare"), host.calls);
    }
    @Test void stoppedInstallationReturnsToStoppedAfterReadinessCheck() {
        FakeHost host = new FakeHost();
        assertEquals("STOPPED", new NodeRedLifecycleService(host).install(new InstallRequest(null, true)).state());
        assertEquals("new", host.release.id()); assertTrue(host.calls.contains("start:new"));
    }
    @Test void backupFailureBlocksActivationAndResumesProcess() {
        FakeHost host = new FakeHost(); host.running = true; host.backupFailure = true;
        assertThrows(NodeRedException.class, () -> new NodeRedLifecycleService(host).install(new InstallRequest(null, true)));
        assertEquals("old", host.release.id()); assertTrue(host.running); assertFalse(host.calls.contains("activate:new"));
    }
    @Test void invalidRestoreRollsBackToSafetySnapshot() {
        FakeHost host = new FakeHost(); host.running = true;
        assertEquals("RESTORE_ROLLED_BACK", assertThrows(NodeRedException.class, () -> new NodeRedLifecycleService(host).restore("invalid", true)).code());
        assertTrue(host.running); assertTrue(host.calls.contains("restore:snapshot"));
    }
    @Test void automaticBackupStopsAndResumesOnlyWhenDue() {
        FakeHost host = new FakeHost(); host.running = true;
        new NodeRedLifecycleService(host).automaticBackup();
        assertEquals(List.of("stop", "backup:automatic", "start:old"), host.calls);
        host.calls.clear(); host.backupDue = false; new NodeRedLifecycleService(host).automaticBackup(); assertTrue(host.calls.isEmpty());
    }
    @Test void concurrentStartsAreSerializedAndIdempotent() throws Exception {
        FakeHost host = new FakeHost(); var service = new NodeRedLifecycleService(host);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Status>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(service::start));
            for (var result : results) assertEquals("RUNNING", result.get().state());
        }
        assertEquals(1, host.starts);
    }
    private static final class FakeHost implements Host {
        Release release = new Release("old", "5.0.9", "24.19.0", "runtime", "a".repeat(64));
        boolean running, rejectCandidate, invalidPackage, backupFailure; boolean backupDue = true; int starts;
        List<String> calls = new ArrayList<>();
        public Release current() { return release; }
        public Release prepare(InstallRequest request) { calls.add("prepare"); if (invalidPackage) throw new NodeRedException("SIGNATURE_INVALID", "bad"); return new Release("new", "5.0.10", "24.19.0", "runtime", "b".repeat(64)); }
        public void activate(Release value) { release = value; calls.add("activate:" + (value == null ? "none" : value.id())); }
        public Status status() { return new Status(running ? "RUNNING" : "STOPPED", null, null, release == null ? null : release.version(), "24.19.0", 1880, "unit", null, null); }
        public Status start() { if (!running) { calls.add("start:" + release.id()); if (rejectCandidate && "new".equals(release.id())) throw new NodeRedException("START_TIMEOUT", "bad"); running = true; starts++; } return status(); }
        public Status stop() { calls.add("stop"); running = false; return status(); }
        public String backup(String reason) { calls.add("backup:" + reason); if (backupFailure) throw new NodeRedException("BACKUP_FAILED", "bad"); return "snapshot"; }
        public void restore(String id) { calls.add("restore:" + id); if ("invalid".equals(id)) throw new NodeRedException("RESTORE_FAILED", "bad"); }
        public boolean automaticBackupDue() { return backupDue; }
        public void uninstall() { calls.add("uninstall"); }
        public List<String> logs() { return List.copyOf(calls); }
    }
}
