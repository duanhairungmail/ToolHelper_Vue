package com.toolhelper.infrastructure.electerm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toolhelper.application.electerm.ElectermContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ElectermPackageTest {
    @TempDir Path directory;
    ElectermProperties properties(Path publicKey) { return new ElectermProperties(directory.resolve("program"), directory.resolve("data"), 5577, null, publicKey, directory.resolve("inbox")); }
    @Test void zipTraversalAndCaseCollisionsAreRejected() throws Exception {
        Path archive = directory.resolve("escape.zip");
        writeZip(archive, Map.of("../escape.txt", "outside"));
        assertThrows(IOException.class, () -> ElectermPackageService.extract(archive, directory.resolve("extract"), 1000));
        assertFalse(Files.exists(directory.resolve("escape.txt")));
        writeZip(archive, Map.of("a.txt", "one", "A.txt", "two"));
        assertThrows(IOException.class, () -> ElectermPackageService.extract(archive, directory.resolve("collision"), 1000));
    }
    @Test void zipExpansionIsBounded() throws Exception {
        Path archive = directory.resolve("large.zip"); writeZip(archive, Map.of("large", "x".repeat(1000)));
        assertThrows(IOException.class, () -> ElectermPackageService.extract(archive, directory.resolve("extract"), 100));
    }
    @Test void offlineImportNeedsConfiguredExternalTrustRoot() throws Exception {
        ElectermPackageService packages = new ElectermPackageService(properties(null), new ObjectMapper());
        assertEquals("TRUST_NOT_CONFIGURED", assertThrows(ElectermException.class, () -> packages.prepare(new InstallRequest("demo.zip"))).code());
    }
    @Test void forgedManifestFailsBeforeParsingOrExtractingPayload() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path key = directory.resolve("public.pem");
        Files.writeString(key, "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        Files.createDirectories(directory.resolve("inbox"));
        writeZip(directory.resolve("inbox/demo.zip"), Map.of("manifest.json", "{untrusted}", "manifest.sig", Base64.getEncoder().encodeToString(new byte[64])));
        ElectermPackageService packages = new ElectermPackageService(properties(key), new ObjectMapper());
        assertEquals("SIGNATURE_INVALID", assertThrows(ElectermException.class, () -> packages.prepare(new InstallRequest("demo.zip"))).code());
        assertFalse(Files.exists(directory.resolve("program/versions")));
    }
    @Test void filenameCannotEscapeOfflineInbox() throws Exception {
        Path key = directory.resolve("public.pem"); Files.writeString(key, "not-used");
        ElectermPackageService packages = new ElectermPackageService(properties(key), new ObjectMapper());
        assertEquals("INVALID_PACKAGE_NAME", assertThrows(ElectermException.class, () -> packages.prepare(new InstallRequest("../elsewhere.zip"))).code());
    }
    @Test void hashMismatchDoesNotPass() throws Exception {
        Path file = directory.resolve("file"); Files.writeString(file, "different");
        assertThrows(IOException.class, () -> ElectermPackageService.requireHash(file, "0".repeat(64)));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void signedOfflinePackageInstallsWithoutExecutingPayload() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path key = directory.resolve("public.pem");
        Files.writeString(key, "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        ElectermProperties properties = properties(key); Files.createDirectories(properties.inbox());
        byte[] pe = new byte[128]; pe[0] = 'M'; pe[1] = 'Z'; pe[60] = 64; pe[64] = 'P'; pe[65] = 'E'; pe[68] = 0x64; pe[69] = (byte) 0x86;
        Path payload = directory.resolve("payload.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(payload))) {
            zip.putNextEntry(new ZipEntry("runtime/node.exe")); zip.write(pe); zip.closeEntry();
            for (String name : List.of("runtime/LICENSE", "NOTICES.md", "app/LICENSE", "app/toolhelper-entry.mjs", "app/dist/views/index.pug", "app/dist/assets/js/basic-5.3.26.js", "app/node_modules/express/package.json", "app/package-lock.json", "app/package.json")) {
                zip.putNextEntry(new ZipEntry(name)); zip.write((name.equals("app/package.json") ? "{\"version\":\"5.3.26\"}" : "{}").getBytes()); zip.closeEntry();
            }
        }
        ObjectMapper json = new ObjectMapper();
        var manifest = new ElectermPackageService.Manifest(1, "demo", "5.3.26", "24.19.0", "win-x64", "payload.zip", hash(Files.readAllBytes(payload)), hash(pe), hash("{}".getBytes()), "a".repeat(40));
        byte[] bytes = json.writeValueAsBytes(manifest);
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(pair.getPrivate()); signature.update(bytes);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(properties.inbox().resolve("demo.zip")))) {
            for (var entry : Map.of("manifest.json", bytes, "manifest.sig", Base64.getEncoder().encode(signature.sign()), "payload.zip", Files.readAllBytes(payload)).entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        ElectermPackageService packages = new ElectermPackageService(properties, json);
        Release release = packages.prepare(new InstallRequest("demo.zip"));
        assertNull(packages.current()); // prepare 仅暂存，不切换当前版本。
        packages.activate(release); packages.verifyInstalled(release);
        assertEquals("5.3.26", packages.current().version());
        assertTrue(Files.exists(packages.directory(release).resolve("app/LICENSE")));
    }
    @Test void wrongRuntimeArchitectureIsRejected() throws Exception {
        Path fake = directory.resolve("node.exe"); Files.writeString(fake, "not a PE executable");
        assertThrows(IOException.class, () -> ElectermPackageService.requireWindowsX64(fake));
    }
    @Test void dataRoundTripIncludesWalAndKeepsRetention() throws Exception {
        ElectermProperties properties = properties(null); Files.createDirectories(properties.data());
        Files.writeString(properties.data().resolve("db.sqlite"), "before");
        Files.writeString(properties.data().resolve("db.sqlite-wal"), "wal-before");
        ElectermBackupService backups = new ElectermBackupService(properties);
        String snapshot = backups.backup("upgrade");
        Files.writeString(properties.data().resolve("db.sqlite"), "changed");
        Files.writeString(properties.data().resolve("new-file"), "new");
        backups.restore(snapshot);
        assertEquals("before", Files.readString(properties.data().resolve("db.sqlite")));
        assertEquals("wal-before", Files.readString(properties.data().resolve("db.sqlite-wal")));
        assertFalse(Files.exists(properties.data().resolve("new-file")));
        for (int i = 0; i < 6; i++) backups.backup("automatic");
        for (int i = 0; i < 3; i++) backups.backup("upgrade");
        try (var files = Files.list(properties.backups())) { assertEquals(5, files.filter(path -> path.getFileName().toString().startsWith("automatic-")).count()); }
        try (var files = Files.list(properties.backups())) { assertEquals(2, files.filter(path -> path.getFileName().toString().startsWith("upgrade-")).count()); }
        assertFalse(backups.automaticBackupDue());
    }
    @Test void controlledSourceCannotBeHttpOrCarryCredentials() {
        assertThrows(IllegalArgumentException.class, () -> new ElectermProperties(directory.resolve("p"), directory.resolve("d"), 5577, java.net.URI.create("http://example.test/"), null, directory.resolve("inbox")));
        assertThrows(IllegalArgumentException.class, () -> new ElectermProperties(directory.resolve("p"), directory.resolve("d"), 5577, java.net.URI.create("https://user:password@example.test/"), null, directory.resolve("inbox")));
    }
    private static void writeZip(Path archive, Map<String, String> files) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var file : files.entrySet()) { zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
