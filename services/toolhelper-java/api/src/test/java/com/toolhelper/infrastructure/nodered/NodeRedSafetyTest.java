package com.toolhelper.infrastructure.nodered;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toolhelper.application.nodered.*;
import com.toolhelper.application.nodered.NodeRedContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 开发单测使用隔离目录/合成包，不启动Node-RED或访问物理设备。 */
class NodeRedSafetyTest {
    @TempDir Path root;
    NodeRedProperties properties(Path key) { return new NodeRedProperties(root.resolve("program"), root.resolve("data"), root.resolve("backups"), root.resolve("secrets/key.bin"), 1880, null, key, root.resolve("inbox"), Set.of()); }
    @Test void zipTraversalCaseCollisionAndExpansionAreRejected() throws Exception {
        Path zip = root.resolve("invalid.zip");
        for (Map<String, byte[]> entries : List.of(Map.of("../escape", new byte[1]), Map.of("a", new byte[1], "A", new byte[1]), Map.of("huge", new byte[1024]))) {
            zip(zip, entries); assertThrows(IOException.class, () -> NodeRedPackageService.extract(zip, root.resolve(UUID.randomUUID().toString()), 100));
        }
        assertFalse(Files.exists(root.resolve("escape")));
    }
    @Test void missingTrustAndForgedSignatureFailBeforePayloadExecution() throws Exception {
        var untrusted = new NodeRedPackageService(properties(null), new ObjectMapper());
        assertEquals("TRUST_NOT_CONFIGURED", assertThrows(NodeRedException.class, () -> untrusted.prepare(new InstallRequest(null, true))).code());
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); Path key = root.resolve("public.pem");
        Files.writeString(key, "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        Files.createDirectories(root.resolve("inbox")); zip(root.resolve("inbox/demo.zip"), Map.of("manifest.json", "{not-json}".getBytes(), "manifest.sig", Base64.getEncoder().encode(new byte[64])));
        var signed = new NodeRedPackageService(properties(key), new ObjectMapper());
        assertEquals("SIGNATURE_INVALID", assertThrows(NodeRedException.class, () -> signed.prepare(new InstallRequest("demo.zip", true))).code());
        assertFalse(Files.exists(root.resolve("program/versions")));
    }
    @Test void existingDataWithoutSecretNeverGeneratesNewKeyOrBackup() throws Exception {
        var props = properties(null); Files.createDirectories(props.data()); Files.writeString(props.data().resolve("flows_cred.json"), "encrypted");
        var settings = new NodeRedSettingsService(props);
        assertEquals("CREDENTIAL_SECRET_MISSING", assertThrows(NodeRedException.class, settings::environment).code());
        assertEquals("CREDENTIAL_SECRET_MISSING", assertThrows(NodeRedException.class, () -> new NodeRedBackupService(props, settings).backup("upgrade")).code());
        assertFalse(Files.exists(props.secret()));
    }
    @Test void backupRoundTripAndSevenTwoRetentionPreserveSealedKey() throws Exception {
        var props = properties(null); Files.createDirectories(props.data()); Files.createDirectories(props.secret().getParent());
        Files.write(props.secret(), new byte[]{1, 2, 3}); Files.writeString(props.data().resolve("flows.json"), "before");
        var settings = mock(NodeRedSettingsService.class); var backups = new NodeRedBackupService(props, settings);
        String snapshot = backups.backup("migration"); Files.writeString(props.data().resolve("flows.json"), "after"); backups.restore(snapshot);
        assertEquals("before", Files.readString(props.data().resolve("flows.json"))); assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(props.secret()));
        for (int i = 0; i < 8; i++) backups.backup("automatic"); for (int i = 0; i < 3; i++) backups.backup("upgrade");
        try (var files = Files.list(props.backups())) { assertEquals(7, files.filter(p -> p.getFileName().toString().startsWith("automatic-")).count()); }
        try (var files = Files.list(props.backups())) { assertEquals(2, files.filter(p -> p.getFileName().toString().startsWith("upgrade-")).count()); }
        assertFalse(backups.automaticBackupDue());
        try (var files = Files.list(props.backups())) { for (Path p : files.filter(p -> p.getFileName().toString().startsWith("automatic-")).toList()) Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(8)))); }
        assertTrue(backups.automaticBackupDue());
    }
    @Test void failedOrphanVerificationReleasesOwnershipLockAndCannotBeBypassed() throws Exception {
        var props = properties(null); Files.createDirectories(props.home());
        var json = new ObjectMapper(); ProcessHandle current = ProcessHandle.current();
        Files.writeString(props.home().resolve("process.json"), json.writeValueAsString(Map.of("pid", current.pid(), "startedAt", current.info().startInstant().orElseThrow().toString(), "ownerPid", current.pid())));
        try (var registry = new NodeRedProcessRegistry(props, mock(NodeRedPackageService.class), mock(NodeRedHealthService.class), json, mock(NodeRedSettingsService.class), new SerialPortCoordinator(), new MockEnvironment())) {
            for (int i = 0; i < 2; i++) assertEquals("ORPHAN_UNVERIFIABLE", assertThrows(NodeRedException.class, registry::claimOwnership).code());
            assertTrue(current.isAlive()); Files.delete(props.home().resolve("process.json")); registry.claimOwnership();
        }
    }
    @Test void reviewMustCoverNestedDependenciesNativeFilesAndLifecycleScripts() throws Exception {
        Path app = root.resolve("app"), packageRoot = app.resolve("node_modules/example"); Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("package.json"), "{\"name\":\"example\",\"version\":\"1.0.0\",\"license\":\"MIT\",\"scripts\":{\"install\":\"node build.cjs\"}}");
        var info = new NodeApprovalService.PackageInfo("node_modules/example", "example", "1.0.0", hash(packageRoot.resolve("package.json")), "MIT", "active", "none", Map.of("install", "node build.cjs"));
        var review = new NodeApprovalService.Review(List.of(info), List.of(), List.of(), List.of("current user"), List.of(), "Restore snapshot", Instant.now().minusSeconds(1));
        var service = new NodeApprovalService(properties(null), null, null, null, null, new ObjectMapper()); service.validate(review, root);
        Files.write(packageRoot.resolve("native.node"), new byte[1]); assertThrows(IOException.class, () -> service.validate(review, root));
        Files.delete(packageRoot.resolve("native.node")); Path nested = packageRoot.resolve("node_modules/hidden"); Files.createDirectories(nested); Files.writeString(nested.resolve("package.json"), "{}");
        assertThrows(IOException.class, () -> service.validate(review, root));
    }
    @Test void unapprovedAndCancelledNodeInstallDoNotStopBackupOrRunScripts() {
        var lifecycle = mock(NodeRedLifecycleService.class); var packages = mock(NodeRedPackageService.class); var processes = mock(NodeRedProcessRegistry.class); var backups = mock(NodeRedBackupService.class);
        var service = new NodeApprovalService(properties(null), packages, lifecycle, processes, backups, new ObjectMapper());
        assertEquals("NODE_REVIEW_EXPIRED", assertThrows(NodeRedException.class, () -> service.install("unknown", false)).code());
        verifyNoInteractions(lifecycle, packages, processes, backups);
    }
    @Test void signedNodeBundleVerifiesEveryFileAndRejectsLocalTampering() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); Path key = root.resolve("public.pem");
        Files.writeString(key, "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        var props = properties(key); Files.createDirectories(props.inbox()); var json = new ObjectMapper();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("app/package-lock.json", "{}".getBytes()); files.put("app/review.json", "{}".getBytes());
        files.put("app/node_modules/example/package.json", "{\"name\":\"example\"}".getBytes());
        Map<String, String> index = new LinkedHashMap<>(); for (var file : files.entrySet()) index.put(file.getKey(), hashBytes(file.getValue()));
        byte[] indexBytes = json.writeValueAsBytes(index); files.put("files.json", indexBytes);
        Path payload = root.resolve("payload.zip"); zip(payload, files);
        var manifest = new NodeRedPackageService.Manifest(1, "unit-nodes", "1.0.0", "24.19.0", "win-x64", "payload.zip", hash(payload), "0".repeat(64), hashBytes("{}".getBytes()), hashBytes(indexBytes), "nodes");
        byte[] bytes = json.writeValueAsBytes(manifest); Signature signer = Signature.getInstance("Ed25519"); signer.initSign(pair.getPrivate()); signer.update(bytes);
        zip(props.inbox().resolve("unit.zip"), Map.of("manifest.json", bytes, "manifest.sig", Base64.getEncoder().encode(signer.sign()), "payload.zip", Files.readAllBytes(payload)));
        var packages = new NodeRedPackageService(props, json); Release release = packages.prepareNodes(new InstallRequest("unit.zip", true));
        assertNull(packages.current()); packages.verifyInstalled(release);
        Files.writeString(packages.directory(release).resolve("app/node_modules/example/package.json"), "tampered");
        assertEquals("INSTALLATION_INVALID", assertThrows(NodeRedException.class, () -> packages.verifyInstalled(release)).code());
    }
    @Test void reviewedBundleRequiresApprovalAndSeparateConfirmationAndAuditsReason() throws Exception {
        Path app = root.resolve("app"), module = app.resolve("node_modules/example"); Files.createDirectories(module);
        Files.writeString(module.resolve("package.json"), "{\"name\":\"example\",\"version\":\"1.0.0\",\"license\":\"MIT\"}");
        var json = new ObjectMapper().findAndRegisterModules();
        var info = new NodeApprovalService.PackageInfo("node_modules/example", "example", "1.0.0", hash(module.resolve("package.json")), "MIT", "active", "none", Map.of());
        var review = new NodeApprovalService.Review(List.of(info), List.of(), List.of(), List.of(), List.of(), "restore userDir", Instant.now().minusSeconds(1));
        json.writeValue(app.resolve("review.json").toFile(), review);
        var lifecycle = mock(NodeRedLifecycleService.class); var packages = mock(NodeRedPackageService.class); var processes = mock(NodeRedProcessRegistry.class); var backups = mock(NodeRedBackupService.class);
        var release = new Release("unit", "1.0.0", "24.19.0", "nodes", "a".repeat(64)); when(packages.prepareNodes(any())).thenReturn(release); when(packages.directory(release)).thenReturn(root);
        var props = properties(null); var service = new NodeApprovalService(props, packages, lifecycle, processes, backups, json);
        var inspection = service.inspect(new InstallRequest("unit.zip", true)); clearInvocations(lifecycle, packages, processes, backups);
        assertEquals("NODE_APPROVAL_REQUIRED", assertThrows(NodeRedException.class, () -> service.install(inspection.id(), true)).code());
        assertEquals("CONFIRMATION_REQUIRED", assertThrows(NodeRedException.class, () -> service.install(inspection.id(), false)).code());
        service.approve(inspection.id(), "unit-reviewer", "isolated review reason");
        assertEquals("CONFIRMATION_REQUIRED", assertThrows(NodeRedException.class, () -> service.install(inspection.id(), false)).code());
        service.cancel(inspection.id()); verifyNoInteractions(lifecycle, packages, processes, backups);
        String audit = Files.readString(props.home().resolve("audit/nodes.jsonl")); assertTrue(audit.contains("isolated review reason")); assertTrue(audit.contains("CANCELLED"));
    }
    private static String hashBytes(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private static void zip(Path path, Map<String, byte[]> entries) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) { for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); } }
    }
}
