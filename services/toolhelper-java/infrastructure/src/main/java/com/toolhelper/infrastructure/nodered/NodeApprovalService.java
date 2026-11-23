package com.toolhelper.infrastructure.nodered;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toolhelper.application.nodered.NodeRedLifecycleService;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.toolhelper.application.nodered.NodeRedContracts.*;

/** 节点包只接收发布方已签名的完整依赖树；审阅、审批和执行确认互相独立。 */
public final class NodeApprovalService {
    public record PackageInfo(String path, String name, String version, String sha256, String license, String maintenance, String vulnerabilities, Map<String, String> installScripts) {}
    public record Script(String path, String source) {}
    public record Review(List<PackageInfo> packages, List<String> nativeDependencies, List<Script> scripts,
                         List<String> permissions, List<String> networkAccess, String rollbackImpact, Instant reviewedAt) {}
    public record Inspection(String id, String sha256, Review review, boolean allowlisted, boolean approved, Instant expiresAt) {}
    private record Pending(Release release, Inspection inspection, String reviewer, String reason) {}
    private final NodeRedProperties properties;
    private final NodeRedPackageService packages;
    private final NodeRedLifecycleService lifecycle;
    private final NodeRedProcessRegistry processes;
    private final NodeRedBackupService backups;
    private final ObjectMapper json;
    private final Map<String, Pending> pending = new HashMap<>();
    public NodeApprovalService(NodeRedProperties properties, NodeRedPackageService packages, NodeRedLifecycleService lifecycle,
            NodeRedProcessRegistry processes, NodeRedBackupService backups, ObjectMapper json) {
        this.properties = properties; this.packages = packages; this.lifecycle = lifecycle; this.processes = processes; this.backups = backups; this.json = json;
    }
    public Inspection inspect(InstallRequest request) {
        synchronized (lifecycle) {
            if (request == null || !request.confirmed()) throw new NodeRedException("CONFIRMATION_REQUIRED", "请先确认导入待审节点包");
            lifecycle.status(); claim();
            Release candidate = packages.prepareNodes(request);
            try {
                Path root = packages.directory(candidate);
                Review review = json.readValue(root.resolve("app/review.json").toFile(), Review.class);
                validate(review, root);
                boolean allowlisted = properties.nodeAllowlist().contains(candidate.sha256());
                Inspection inspection = new Inspection(UUID.randomUUID().toString(), candidate.sha256(), review, allowlisted, allowlisted, Instant.now().plusSeconds(600));
                pending.entrySet().removeIf(entry -> entry.getValue().inspection().expiresAt().isBefore(Instant.now()));
                if (pending.size() >= 20) throw new NodeRedException("NODE_REVIEW_LIMIT", "待审节点包过多，请取消旧申请");
                pending.put(inspection.id(), new Pending(candidate, inspection, allowlisted ? "configured-allowlist" : null, allowlisted ? "configured SHA-256 allowlist" : null));
                audit(pending.get(inspection.id()), "INSPECTED"); return inspection;
            } catch (NodeRedException error) { throw error; }
            catch (Exception error) { throw new NodeRedException("NODE_REVIEW_INVALID", "节点包缺少有效的许可证、维护、漏洞或脚本审阅材料", error); }
        }
    }
    public Inspection approve(String id, String reviewer, String reason) {
        synchronized (lifecycle) {
            Pending item = require(id);
            if (reviewer == null || !reviewer.matches("[^\\p{Cntrl}]{1,80}") || reason == null || reason.isBlank() || reason.length() > 1000)
                throw new NodeRedException("NODE_APPROVAL_REQUIRED", "人工审批需填写审批人和审批理由");
            Inspection old = item.inspection();
            Inspection approved = new Inspection(old.id(), old.sha256(), old.review(), old.allowlisted(), true, old.expiresAt());
            Pending updated = new Pending(item.release(), approved, reviewer, reason);
            audit(updated, "APPROVED");
            pending.put(id, updated); return approved;
        }
    }
    public void cancel(String id) {
        synchronized (lifecycle) {
            Pending item = require(id); audit(item, "CANCELLED"); pending.remove(id);
        }
    }
    public Status install(String id, boolean confirmed) {
        synchronized (lifecycle) {
            // 所有确认守卫必须先于停服、备份、文件变更和脚本执行。
            Pending item = require(id);
            if (!confirmed) throw new NodeRedException("CONFIRMATION_REQUIRED", "安装确认已取消，未执行节点变更");
            if (!item.inspection().approved()) throw new NodeRedException("NODE_APPROVAL_REQUIRED", "节点尚未命中白名单或完成人工审批");
            claim(); packages.verifyInstalled(item.release());
            pending.remove(id); audit(item, "CONFIRMED");
            boolean running = "RUNNING".equals(lifecycle.status().state());
            lifecycle.stop();
            String snapshot;
            try { snapshot = backups.backup("nodes"); }
            catch (RuntimeException error) { audit(item, "BACKUP_FAILED"); if (running) lifecycle.start(); throw error; }
            try {
                Path source = packages.directory(item.release()).resolve("app");
                // 不执行 package.json 的任意命令；仅执行已展示、已签名的显式 .cjs 安装入口。
                Release runtime = packages.current();
                if (runtime == null) throw new NodeRedException("NOT_INSTALLED", "请先安装Node-RED运行时");
                for (Script script : item.inspection().review().scripts()) {
                    audit(item, "SCRIPT_STARTED:" + script.path());
                    try { runScript(runtime, source, script.path()); audit(item, "SCRIPT_SUCCEEDED:" + script.path()); }
                    catch (Exception error) { audit(item, "SCRIPT_FAILED:" + script.path() + ":" + error.getClass().getSimpleName()); throw error; }
                }
                // 脚本可以生成原生文件，但不能通过链接把复制/删除范围扩展到受管树以外。
                validateTree(source);
                for (String name : List.of("node_modules", "package.json", "package-lock.json")) {
                    Path target = properties.data().resolve(name);
                    NodeRedFiles.noLinks(properties.data(), target);
                    if (Files.isDirectory(target)) NodeRedFiles.removeTree(properties.data(), target); else Files.deleteIfExists(target);
                    Path input = source.resolve(name);
                    try (var walk = Files.walk(input)) {
                        for (Path file : walk.sorted().toList()) {
                            Path output = target.resolve(input.relativize(file));
                            if (Files.isDirectory(file)) Files.createDirectories(output);
                            else { Files.createDirectories(output.getParent()); Files.copy(file, output); }
                        }
                    }
                }
                Status status = lifecycle.start();
                if (!running) status = lifecycle.stop();
                audit(item, "INSTALLED"); processes.log("经审批的Node-RED节点包安装完成");
                return status;
            } catch (Exception error) {
                try { lifecycle.stop(); backups.restore(snapshot); if (running) lifecycle.start(); audit(item, "ROLLED_BACK"); }
                catch (Exception rollback) { audit(item, "ROLLBACK_FAILED"); throw new NodeRedException("NODE_ROLLBACK_FAILED", "节点变更恢复失败，请保留节点变更前备份", rollback); }
                throw new NodeRedException("NODE_UPDATE_ROLLED_BACK", "节点安装失败，已恢复变更前快照；外部脚本副作用须按审阅记录人工回退", error);
            }
        }
    }
    void validate(Review review, Path root) throws Exception {
        if (review.packages() == null || review.packages().isEmpty() || review.scripts() == null || review.nativeDependencies() == null
            || review.permissions() == null || review.networkAccess() == null || review.rollbackImpact() == null || review.rollbackImpact().isBlank()
            || review.reviewedAt() == null || review.reviewedAt().isAfter(Instant.now()) || review.reviewedAt().isBefore(Instant.now().minus(Duration.ofDays(30))))
            throw new IOException("审阅材料缺失或已过期");
        Path app = root.resolve("app"); validateTree(app);
        Set<String> declaredPackages = new HashSet<>();
        for (PackageInfo info : review.packages()) {
            if (info.name() == null || !info.name().matches("(@[a-z0-9._-]+/)?[a-z0-9._-]+") || info.version() == null
                || info.license() == null || !info.license().matches("[A-Za-z0-9.+() -]{2,100}")
                || !"active".equals(info.maintenance()) || !"none".equals(info.vulnerabilities()) || info.sha256() == null || !info.sha256().matches("[a-f0-9]{64}")
                || info.path() == null || !info.path().matches("node_modules/(?:@[a-z0-9._-]+/)?[a-z0-9._-]+(?:/node_modules/(?:@[a-z0-9._-]+/)?[a-z0-9._-]+)*")
                || !declaredPackages.add(info.path()) || info.installScripts() == null)
                throw new IOException("节点完整性、许可证、维护状态或已知漏洞不符合策略");
            Path manifest = app.resolve(info.path()).resolve("package.json");
            var actual = json.readTree(manifest.toFile());
            if (!info.name().equals(actual.path("name").asText()) || !info.version().equals(actual.path("version").asText()) || !info.license().equals(actual.path("license").asText()))
                throw new IOException("节点声明与实际包不一致");
            NodeRedPackageService.requireHash(manifest, info.sha256());
            Map<String, String> scripts = new TreeMap<>();
            for (String name : List.of("preinstall", "install", "postinstall", "prepare"))
                if (actual.path("scripts").has(name)) scripts.put(name, actual.path("scripts").path(name).asText());
            if (!scripts.equals(info.installScripts())) throw new IOException("包生命周期脚本必须完整披露，即使本机不会自动执行");
        }
        Set<String> actualPackages = new HashSet<>(), nativeFiles = new HashSet<>();
        try (var files = Files.walk(app.resolve("node_modules"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = app.relativize(file).toString().replace('\\', '/');
                if (path.matches("node_modules/(?:@[^/]+/)?[^/]+(?:/node_modules/(?:@[^/]+/)?[^/]+)*/package\\.json"))
                    actualPackages.add(path.substring(0, path.length() - "/package.json".length()));
                if (path.toLowerCase(Locale.ROOT).matches(".*\\.(node|dll|exe|so|dylib)$")) nativeFiles.add(path);
            }
        }
        if (!actualPackages.equals(declaredPackages) || !nativeFiles.equals(new HashSet<>(review.nativeDependencies())))
            throw new IOException("审阅清单必须覆盖所有传递依赖和原生二进制文件");
        Set<String> entries = new HashSet<>();
        for (Script script : review.scripts()) {
            if (script.path() == null || !script.path().matches("install/[A-Za-z0-9_-]+\\.cjs") || !entries.add(script.path()) || script.source() == null || script.source().length() > 64 * 1024)
                throw new IOException("脚本入口无效或无法审阅");
            if (!script.source().equals(Files.readString(root.resolve("app").resolve(script.path())))) throw new IOException("脚本审阅内容不匹配");
        }
    }
    private static void validateTree(Path source) throws IOException {
        try (var walk = Files.walk(source)) { for (Path path : walk.toList()) NodeRedFiles.noLinks(source, path); }
    }
    private void runScript(Release runtime, Path directory, String script) throws Exception {
        packages.verifyInstalled(runtime);
        Path runtimeDirectory = packages.directory(runtime);
        ProcessBuilder builder = new ProcessBuilder(runtimeDirectory.resolve("runtime/node.exe").toString(), "--require",
            runtimeDirectory.resolve("app/coordinator-preload.cjs").toString(), directory.resolve(script).toString()).directory(directory.toFile());
        Map<String, String> inherited = new HashMap<>(builder.environment()); builder.environment().clear();
        for (String key : List.of("SystemRoot", "WINDIR", "TEMP", "TMP")) if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
        builder.environment().put("PATH", packages.directory(runtime).resolve("runtime").toString());
        builder.environment().put("TOOLHELPER_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
        // 安装脚本只复用父进程看护，不取得运行流程的串口专用令牌。
        builder.environment().put("TOOLHELPER_SERIAL_API", "http://127.0.0.1:1/disabled");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        processes.executeInstallScript(builder);
    }
    private Pending require(String id) {
        Pending item = pending.get(id);
        if (item == null || item.inspection().expiresAt().isBefore(Instant.now())) throw new NodeRedException("NODE_REVIEW_EXPIRED", "节点审阅已过期，请重新导入");
        return item;
    }
    private void claim() { try { processes.claimOwnership(); } catch (IOException error) { throw new NodeRedException("INSTANCE_IN_USE", "无法取得Node-RED目录所有权", error); } }
    private void audit(Pending pending, String result) {
        try {
            Inspection inspection = pending.inspection();
            Path directory = properties.home().resolve("audit"); NodeRedFiles.privateDirectory(directory);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString()); entry.put("inspectionId", inspection.id()); entry.put("sha256", inspection.sha256());
            entry.put("packages", inspection.review().packages()); entry.put("approval", pending.reviewer() == null ? "pending" : pending.reviewer());
            entry.put("approvalReason", pending.reason()); entry.put("result", result);
            Files.writeString(directory.resolve("nodes.jsonl"), json.writeValueAsString(entry) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) { throw new NodeRedException("NODE_AUDIT_FAILED", "节点审计写入失败，已阻止继续操作", error); }
    }
}
