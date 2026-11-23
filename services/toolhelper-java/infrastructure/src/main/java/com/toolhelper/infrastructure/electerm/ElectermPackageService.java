package com.toolhelper.infrastructure.electerm;

import com.toolhelper.application.electerm.ElectermContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** 验签在解析/解压之前完成，离线包同样使用外部配置的公钥。 */
public final class ElectermPackageService {
    private static final long MAX_ARCHIVE = 768L * 1024 * 1024;
    private static final long MAX_EXPANDED = 3L * 1024 * 1024 * 1024;
    private final ElectermProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Manifest(int schemaVersion, String id, String version, String nodeVersion, String platform,
                           String archive, String sha256, String nodeSha256, String lockSha256, String sourceCommit) {}
    public ElectermPackageService(ElectermProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }

    public String importOffline(InputStream input) {
        Path target = properties.inbox().resolve("upload-" + UUID.randomUUID() + ".zip");
        try {
            ElectermFiles.privateDirectory(properties.inbox());
            try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                long total = 0; byte[] bytes = new byte[65536]; int count;
                while ((count = input.read(bytes)) != -1) { total += count; if (total > MAX_ARCHIVE) throw new IOException("离线包大小超限"); output.write(bytes, 0, count); }
            }
            return target.getFileName().toString();
        } catch (IOException error) {
            try { Files.deleteIfExists(target); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw new ElectermException("PACKAGE_INVALID", "离线包保存失败或大小超出限制", error);
        }
    }
    public void discardUpload(String name) {
        if (!name.matches("upload-[0-9a-f-]{36}\\.zip")) throw new IllegalArgumentException("上传包名称无效");
        try { Files.deleteIfExists(properties.inbox().resolve(name)); }
        catch (IOException error) { System.err.println("Electerm 上传临时包清理失败"); }
    }

    public Release prepare(InstallRequest request) {
        Path staging = null;
        try {
            if (properties.publicKey() == null) throw new ElectermException("TRUST_NOT_CONFIGURED", "尚未配置 ToolHelper 发布公钥，无法安装");
            ElectermFiles.privateDirectory(properties.home());
            staging = Files.createTempDirectory(properties.home(), "staging-");
            Path source = null;
            if (request != null && request.offlinePackage() != null && !request.offlinePackage().isBlank()) {
                if (!request.offlinePackage().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}\\.zip")) throw new ElectermException("INVALID_PACKAGE_NAME", "离线包仅接受 inbox 中的 ZIP 文件名");
                source = properties.inbox().resolve(request.offlinePackage());
                ElectermFiles.noLinks(properties.inbox(), source);
                extract(source, staging.resolve("source"), MAX_ARCHIVE);
                source = staging.resolve("source");
            } else if (properties.feed() == null) throw new ElectermException("FEED_NOT_CONFIGURED", "尚未配置 ToolHelper 受控源，请导入签名离线包");
            byte[] manifestBytes = source == null ? downloadSmall(properties.feed().resolve("manifest.json"), 64 * 1024) : boundedRead(source.resolve("manifest.json"), 64 * 1024);
            byte[] signature = source == null ? downloadSmall(properties.feed().resolve("manifest.sig"), 1024) : boundedRead(source.resolve("manifest.sig"), 1024);
            verifySignature(manifestBytes, signature);
            Manifest manifest = json.readValue(manifestBytes, Manifest.class);
            validate(manifest);
            Release installed = current();
            if (installed != null && compareVersions(manifest.version(), installed.version()) < 0) throw new ElectermException("DOWNGRADE_REJECTED", "安装包版本低于当前版本，请使用受控恢复流程");
            Path archive = source == null ? staging.resolve("payload.zip") : source.resolve(manifest.archive());
            if (source == null) download(properties.feed().resolve(manifest.archive()), archive);
            requireHash(archive, manifest.sha256());
            Path payload = staging.resolve("payload");
            extract(archive, payload, MAX_EXPANDED);
            requireHash(payload.resolve("runtime/node.exe"), manifest.nodeSha256());
            requireWindowsX64(payload.resolve("runtime/node.exe"));
            requireHash(payload.resolve("app/package-lock.json"), manifest.lockSha256());
            for (String required : List.of("app/toolhelper-entry.mjs", "app/LICENSE", "app/dist/views/index.pug", "app/dist/assets/js/basic-" + manifest.version() + ".js", "app/node_modules/express/package.json", "runtime/LICENSE", "NOTICES.md"))
                if (!Files.isRegularFile(payload.resolve(required))) throw new IOException("运行包缺少必要文件：" + required);
            if (!manifest.version().equals(json.readTree(payload.resolve("app/package.json").toFile()).path("version").asText())) throw new IOException("运行包版本与清单不一致");
            Path versions = properties.home().resolve("versions"); Files.createDirectories(versions);
            // 每次安装使用新目录，防止重新安装失败时覆盖仍在使用的旧版本。
            String directory = manifest.id() + "-" + UUID.randomUUID();
            Path target = versions.resolve(directory);
            Files.move(payload, target, StandardCopyOption.ATOMIC_MOVE);
            Files.write(target.resolve("manifest.json"), manifestBytes);
            Files.write(target.resolve("manifest.sig"), signature);
            return new Release(directory, manifest.version(), manifest.nodeVersion());
        } catch (ElectermException error) { throw error; }
        catch (GeneralSecurityException error) { throw new ElectermException("SIGNATURE_INVALID", "发布清单签名验证失败", error); }
        catch (Exception error) { throw new ElectermException("PACKAGE_INVALID", "运行包下载或校验失败，请检查受控源、签名和包完整性", error); }
        finally { if (staging != null) try { ElectermFiles.removeTree(properties.home(), staging); } catch (IOException error) { System.err.println("Electerm 临时包清理失败"); } }
    }

    public Release current() {
        Path pointer = properties.home().resolve("current.json");
        if (!Files.exists(pointer)) return null;
        try { Release release = json.readValue(pointer.toFile(), Release.class); directory(release); return release; }
        catch (IOException error) { throw new ElectermException("INSTALLATION_INVALID", "安装指针无效，请重新导入签名包", error); }
    }
    public void activate(Release release) {
        try {
            if (release == null) Files.deleteIfExists(properties.home().resolve("current.json"));
            else { directory(release); ElectermFiles.atomicWrite(properties.home().resolve("current.json"), json.writeValueAsBytes(release)); }
        } catch (IOException error) { throw new ElectermException("INSTALLATION_INVALID", "无法切换安装版本", error); }
    }
    public Path directory(Release release) throws IOException {
        if (release == null || !release.id().matches("[A-Za-z0-9._-]{1,180}")) throw new IOException("安装版本标识无效");
        Path directory = properties.home().resolve("versions").resolve(release.id());
        ElectermFiles.noLinks(properties.home(), directory);
        return directory;
    }
    public void verifyInstalled(Release release) {
        try {
            Path path = directory(release);
            byte[] manifest = boundedRead(path.resolve("manifest.json"), 64 * 1024);
            verifySignature(manifest, boundedRead(path.resolve("manifest.sig"), 1024));
            Manifest parsed = json.readValue(manifest, Manifest.class); validate(parsed);
            if (!release.version().equals(parsed.version()) || !release.nodeVersion().equals(parsed.nodeVersion())) throw new IOException("安装指针与签名版本不一致");
            requireHash(path.resolve("runtime/node.exe"), parsed.nodeSha256());
            requireHash(path.resolve("app/package-lock.json"), parsed.lockSha256());
        } catch (Exception error) { throw new ElectermException("INSTALLATION_INVALID", "已安装程序校验失败，请重新安装签名包", error); }
    }
    private void validate(Manifest m) throws IOException {
        if (m.schemaVersion() != 1 || m.id() == null || !m.id().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}") || m.version() == null || !m.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || m.nodeVersion() == null || !m.nodeVersion().matches("24\\.[0-9]+\\.[0-9]+") || !"win-x64".equals(m.platform()) || !"payload.zip".equals(m.archive())
                || m.sourceCommit() == null || !m.sourceCommit().matches("[0-9a-f]{40}")) throw new IOException("发布清单字段无效");
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw new ElectermException("UNSUPPORTED_PLATFORM", "当前运行包仅支持 Windows X64");
    }
    private void verifySignature(byte[] bytes, byte[] encoded) throws GeneralSecurityException, IOException {
        if (properties.publicKey() == null) throw new GeneralSecurityException("缺少信任公钥");
        String pem = Files.readString(properties.publicKey()).replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem))));
        verifier.update(bytes);
        try { if (!verifier.verify(Base64.getMimeDecoder().decode(encoded))) throw new SignatureException("签名不匹配"); }
        catch (IllegalArgumentException error) { throw new SignatureException("签名编码无效", error); }
    }
    private static int compareVersions(String first, String second) {
        String[] a = first.split("\\."), b = second.split("\\.");
        for (int i = 0; i < 3; i++) { int compared = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i])); if (compared != 0) return compared; }
        return 0;
    }
    static void requireWindowsX64(Path executable) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(executable.toFile(), "r")) {
            if (file.readUnsignedShort() != 0x4d5a || file.length() < 64) throw new IOException("运行时不是 Windows PE 程序");
            file.seek(0x3c); long offset = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
            if (offset > file.length() - 6) throw new IOException("PE 偏移无效");
            file.seek(offset);
            if (file.readInt() != 0x50450000 || Short.reverseBytes(file.readShort()) != (short) 0x8664) throw new IOException("运行时架构不是 Windows X64");
        }
    }
    static void requireHash(Path file, String expected) throws Exception {
        if (expected == null || !expected.matches("[a-f0-9]{64}")) throw new IOException("无效的 SHA-256");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { byte[] bytes = new byte[65536]; int count; while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count); }
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected), digest.digest())) throw new IOException("SHA-256 不匹配");
    }
    static void extract(Path zip, Path target, long maximum) throws IOException {
        if (Files.size(zip) > MAX_ARCHIVE) throw new IOException("运行包超出大小上限");
        Files.createDirectories(target); long total = 0; int count = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (++count > 100_000 || name.startsWith("/") || name.contains(":") || name.contains("\0") || Arrays.stream(name.split("/")).anyMatch(part -> part.equals("..") || part.endsWith(".") || part.endsWith(" ") || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))) throw new IOException("ZIP 条目无效");
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("ZIP 中存在重复路径");
                Path path = target.resolve(name).normalize();
                if (!path.startsWith(target.normalize())) throw new IOException("ZIP 路径越界");
                if (entry.isDirectory()) { Files.createDirectories(path); continue; }
                Files.createDirectories(path.getParent());
                try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536]; int size;
                    while ((size = input.read(buffer)) != -1) { total += size; if (total > maximum) throw new IOException("ZIP 解压大小超限"); output.write(buffer, 0, size); }
                }
            }
        }
    }
    private static byte[] boundedRead(Path path, int max) throws IOException { if (Files.size(path) > max) throw new IOException("清单超限"); return Files.readAllBytes(path); }
    private byte[] downloadSmall(URI uri, int max) throws Exception {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) { if (response.statusCode() != 200) throw new IOException("下载清单失败"); byte[] bytes = input.readNBytes(max + 1); if (bytes.length > max) throw new IOException("清单超限"); return bytes; }
    }
    private void download(URI uri, Path destination) throws Exception {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(destination)) {
            if (response.statusCode() != 200) throw new IOException("下载运行包失败");
            long total = 0; byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) { total += count; if (total > MAX_ARCHIVE) throw new IOException("下载大小超限"); output.write(bytes, 0, count); }
        }
    }
}
