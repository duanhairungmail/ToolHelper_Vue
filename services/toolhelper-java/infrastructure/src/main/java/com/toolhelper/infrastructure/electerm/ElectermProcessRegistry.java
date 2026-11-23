package com.toolhelper.infrastructure.electerm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.toolhelper.application.electerm.ElectermContracts.*;

/** 只管理本实例创建的 Process/子进程，不接管 PID 文件中的历史进程。 */
public final class ElectermProcessRegistry implements AutoCloseable {
    private final ElectermProperties properties;
    private final ElectermPackageService packages;
    private final ElectermHealthService health;
    private final ObjectMapper json;
    private final String instanceId = UUID.randomUUID().toString();
    private final Deque<String> logs = new ArrayDeque<>();
    private final Map<Long, OwnedChild> children = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> { Thread thread = new Thread(r, "electerm-owner-watch"); thread.setDaemon(true); return thread; });
    private volatile Process process;
    private Instant startedAt;
    private Release runningRelease;
    private FileChannel lockChannel;
    private FileLock lock;
    private volatile boolean closed;
    private final Thread shutdownHook = new Thread(this::close, "electerm-shutdown");

    public ElectermProcessRegistry(ElectermProperties properties, ElectermPackageService packages, ElectermHealthService health, ObjectMapper json) {
        this.properties = properties; this.packages = packages; this.health = health; this.json = json;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        watcher.scheduleWithFixedDelay(this::trackChildren, 0, 250, TimeUnit.MILLISECONDS);
    }
    public synchronized Status start() {
        if (closed) throw new ElectermException("SERVICE_CLOSING", "ToolHelper 正在退出");
        if (process != null && process.isAlive()) {
            Status status = status();
            if ("RUNNING".equals(status.state())) return status;
            throw new ElectermException("PROCESS_UNHEALTHY", "托管进程存在但页面未就绪，请重启外挂");
        }
        Release release = packages.current();
        if (release == null) throw new ElectermException("NOT_INSTALLED", "请先安装 Electerm Web");
        packages.verifyInstalled(release);
        if (closed) throw new ElectermException("SERVICE_CLOSING", "ToolHelper 正在退出");
        try {
            stop(); claimOwnership();
            // 只检测冲突，不会终止占用端口的未知进程。
            try (ServerSocket probe = new ServerSocket()) { probe.setReuseAddress(false); probe.bind(new InetSocketAddress("127.0.0.1", properties.port())); }
            ElectermFiles.privateDirectory(properties.data());
            Properties auth = credentials();
            Path directory = packages.directory(release);
            ProcessBuilder builder = new ProcessBuilder(directory.resolve("runtime/node.exe").toString(), "toolhelper-entry.mjs").directory(directory.resolve("app").toFile());
            // 不继承 ToolHelper 的服务令牌、NODE_OPTIONS、代理或动态模块注入配置。
            Map<String, String> inherited = new HashMap<>(builder.environment()); builder.environment().clear();
            for (String key : List.of("SystemRoot", "WINDIR", "ComSpec", "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA"))
                if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
            Map<String, String> env = builder.environment();
            env.putAll(Map.of("HOST", "127.0.0.1", "PORT", Integer.toString(properties.port()), "NODE_ENV", "production", "ENABLE_AUTH", "1", "DISABLE_LOCAL_TERMINAL", "1", "DISABLE_AUTO_UPDATE", "1", "DB_PATH", properties.data().toString(), "TOKEN_EXPIRED_TIME", "8h"));
            env.put("SERVER_USER", "toolhelper"); env.put("SERVER_PASS", auth.getProperty("password")); env.put("SERVER_SECRET", auth.getProperty("secret"));
            env.put("TOOLHELPER_PARENT_PID", Long.toString(ProcessHandle.current().pid())); env.put("TOOLHELPER_INSTANCE_ID", instanceId);
            env.put("PATH", directory.resolve("runtime") + File.pathSeparator + inherited.getOrDefault("SystemRoot", "C:\\Windows") + "\\System32");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            if (closed) throw new ElectermException("SERVICE_CLOSING", "ToolHelper 正在退出");
            process = builder.start();
            if (closed) { process.destroyForcibly(); throw new ElectermException("SERVICE_CLOSING", "ToolHelper 正在退出"); }
            startedAt = process.info().startInstant().orElse(Instant.now()); runningRelease = release;
            ElectermFiles.atomicWrite(properties.home().resolve("process.json"), json.writeValueAsBytes(Map.of("pid", process.pid(), "startedAt", startedAt.toString(), "version", release.version(), "port", properties.port(), "instanceId", instanceId, "ownerPid", ProcessHandle.current().pid())));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                if (closed) throw new ElectermException("SERVICE_CLOSING", "ToolHelper 正在退出");
                if (!process.isAlive()) throw new ElectermException("PROCESS_EXITED", "Electerm 启动失败，退出码=" + process.exitValue());
                if (health.ready(properties.port(), instanceId, release.version())) { log("Electerm 页面已就绪，版本 " + release.version()); return status(); }
                Thread.sleep(200);
            }
            throw new ElectermException("START_TIMEOUT", "Electerm 在 30 秒内未就绪");
        } catch (Exception error) {
            stop();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (error instanceof ElectermException known) throw known;
            if (error instanceof BindException) throw new ElectermException("PORT_IN_USE", "端口 " + properties.port() + " 被占用，请调整 Electerm 端口或关闭已知应用", error);
            throw new ElectermException("START_FAILED", "Electerm 启动失败，请检查运行包和目录权限", error);
        }
    }
    public synchronized Status status() {
        Release release = packages.current();
        boolean alive = process != null && process.isAlive();
        boolean ready = alive && runningRelease != null && health.ready(properties.port(), instanceId, runningRelease.version());
        return new Status(release == null ? "NOT_INSTALLED" : ready ? "RUNNING" : alive ? "STARTING" : "STOPPED", alive ? process.pid() : null, alive ? startedAt : null,
                release == null ? null : release.version(), release == null ? null : release.nodeVersion(), properties.port(), instanceId,
                ready ? "http://127.0.0.1:" + properties.port() + "/" : null, alive && !ready ? "进程存在，页面尚未就绪" : null);
    }
    public synchronized Status stop() {
        Process owned = process;
        if (owned != null) {
            trackChildren();
            children.values().forEach(child -> child.destroy(false));
            owned.destroy();
            try {
                if (!owned.waitFor(2, TimeUnit.SECONDS)) owned.destroyForcibly();
                children.values().forEach(child -> child.destroy(true));
                if (!owned.waitFor(3, TimeUnit.SECONDS)) throw new ElectermException("STOP_TIMEOUT", "Electerm 进程未能在退出期限内结束");
                Instant deadline = Instant.now().plusSeconds(1);
                while (children.values().stream().anyMatch(OwnedChild::alive) && Instant.now().isBefore(deadline)) Thread.sleep(25);
                if (children.values().stream().anyMatch(OwnedChild::alive)) throw new ElectermException("STOP_TIMEOUT", "Electerm 子进程未能结束");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ElectermException("STOP_INTERRUPTED", "Electerm 进程清理被中断", error); }
            log("已停止本实例托管的 Electerm 进程树");
            process = null; children.clear();
        }
        try { Files.deleteIfExists(properties.home().resolve("process.json")); }
        catch (IOException error) { log("Electerm 进程记录清理失败"); }
        return status();
    }
    public synchronized List<String> logs() { return List.copyOf(logs); }
    public synchronized void log(String message) { logs.addLast(Instant.now() + " " + message); while (logs.size() > 200) logs.removeFirst(); }
    private void trackChildren() {
        Process current = process;
        if (current == null || !current.isAlive()) return;
        try { current.descendants().forEach(handle -> handle.info().startInstant().ifPresent(start -> children.putIfAbsent(handle.pid(), new OwnedChild(handle, start)))); }
        catch (RuntimeException error) { System.err.println("Electerm 子进程状态读取失败"); }
    }
    public synchronized void claimOwnership() throws IOException {
        if (closed) throw new ElectermException("SERVICE_CLOSING", "ToolHelper 正在退出");
        if (lock != null && lock.isValid()) return;
        ElectermFiles.privateDirectory(properties.home());
        lockChannel = FileChannel.open(properties.home().resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); } catch (OverlappingFileLockException error) { lock = null; }
        if (lock == null) { releaseLock(); throw new ElectermException("INSTANCE_IN_USE", "另一个 ToolHelper 实例正在管理 Electerm"); }
        if (closed) { releaseLock(); throw new ElectermException("SERVICE_CLOSING", "ToolHelper 正在退出"); }
    }
    private void releaseLock() {
        try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); }
        catch (IOException error) { log("Electerm 所有权锁释放失败"); }
        finally { lock = null; lockChannel = null; }
    }
    private Properties credentials() throws IOException {
        Path path = properties.credentials();
        ElectermFiles.privateDirectory(path.getParent());
        Properties auth = new Properties();
        if (Files.exists(path)) { ElectermFiles.privateAccess(path); try (Reader input = Files.newBufferedReader(path)) { auth.load(input); } }
        else {
            auth.setProperty("password", token()); auth.setProperty("secret", token());
            StringWriter writer = new StringWriter(); auth.store(writer, "Electerm local login; do not commit or share");
            ElectermFiles.atomicWrite(path, writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); ElectermFiles.privateAccess(path);
        }
        for (String key : List.of("password", "secret")) if (auth.getProperty(key, "").length() < 24) throw new IOException("Electerm 本机认证配置无效");
        return auth;
    }
    private static String token() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    @Override public void close() {
        if (closed) return; closed = true; watcher.shutdownNow();
        // shutdown hook 不等待 start() 的 30 秒就绪锁，退出期间直接清理持有的进程句柄。
        Process owned = process;
        try {
            if (owned != null) {
                trackChildren(); children.values().forEach(child -> child.destroy(true)); owned.destroyForcibly();
                if (!owned.waitFor(5, TimeUnit.SECONDS)) System.err.println("Electerm 退出清理超时");
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { releaseLock(); try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { /* JVM 退出期间由当前 hook 完成清理。 */ } }
    }
    private record OwnedChild(ProcessHandle handle, Instant started) {
        boolean alive() { return handle.isAlive() && handle.info().startInstant().map(started::equals).orElse(false); }
        void destroy(boolean force) { if (alive()) { if (force) handle.destroyForcibly(); else handle.destroy(); } }
    }
}
