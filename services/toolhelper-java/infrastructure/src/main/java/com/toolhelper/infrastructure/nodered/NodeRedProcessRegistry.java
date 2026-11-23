package com.toolhelper.infrastructure.nodered;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.toolhelper.application.nodered.NodeRedContracts.*;

/** 只管理本实例创建的 Process/子进程，不接管 PID 文件中的历史进程。 */
public final class NodeRedProcessRegistry implements AutoCloseable {
    private final NodeRedProperties properties;
    private final NodeRedPackageService packages;
    private final NodeRedHealthService health;
    private final ObjectMapper json;
    private final NodeRedSettingsService settings;
    private final com.toolhelper.application.nodered.SerialPortCoordinator serialPorts;
    private final org.springframework.core.env.Environment environment;
    private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.CopyOnWriteArrayList<Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final String instanceId = UUID.randomUUID().toString();
    private final Deque<String> logs = new ArrayDeque<>();
    private final Map<Long, OwnedChild> children = new ConcurrentHashMap<>();
    private final Map<Long, Process> installScripts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> { Thread thread = new Thread(r, "nodered-owner-watch"); thread.setDaemon(true); return thread; });
    private volatile Process process;
    private Instant startedAt;
    private Release runningRelease;
    private FileChannel lockChannel;
    private FileLock lock;
    private volatile boolean closed;
    private final Thread shutdownHook = new Thread(this::close, "nodered-shutdown");

    public NodeRedProcessRegistry(NodeRedProperties properties, NodeRedPackageService packages, NodeRedHealthService health, ObjectMapper json, NodeRedSettingsService settings,
            com.toolhelper.application.nodered.SerialPortCoordinator serialPorts, org.springframework.core.env.Environment environment) {
        this.properties = properties; this.packages = packages; this.health = health; this.json = json; this.settings = settings; this.serialPorts = serialPorts; this.environment = environment;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        watcher.scheduleWithFixedDelay(this::trackChildren, 0, 250, TimeUnit.MILLISECONDS);
    }
    public synchronized Status start() {
        if (closed) throw new NodeRedException("SERVICE_CLOSING", "ToolHelper 正在退出");
        if (process != null && process.isAlive()) {
            Status status = status();
            if ("RUNNING".equals(status.state())) return status;
            throw new NodeRedException("PROCESS_UNHEALTHY", "托管进程存在但页面未就绪，请重启外挂");
        }
        Release release = packages.current();
        if (release == null) throw new NodeRedException("NOT_INSTALLED", "请先安装 Node-RED");
        packages.verifyInstalled(release);
        if (closed) throw new NodeRedException("SERVICE_CLOSING", "ToolHelper 正在退出");
        try {
            claimOwnership(); stop();
            // 只检测冲突，不会终止占用端口的未知进程。
            try (ServerSocket probe = new ServerSocket()) { probe.setReuseAddress(false); probe.bind(new InetSocketAddress("127.0.0.1", properties.port())); }
            NodeRedFiles.privateDirectory(properties.data());
            Map<String, String> secrets = settings.environment();
            Path directory = packages.directory(release);
            ProcessBuilder builder = new ProcessBuilder(directory.resolve("runtime/node.exe").toString(), "--require", "./coordinator-preload.cjs", "node_modules/node-red/red.js", "--settings", "settings.cjs", "--userDir", properties.data().toString()).directory(directory.resolve("app").toFile());
            // 不继承 ToolHelper 的服务令牌、NODE_OPTIONS、代理或动态模块注入配置。
            Map<String, String> inherited = new HashMap<>(builder.environment()); builder.environment().clear();
            for (String key : List.of("SystemRoot", "WINDIR", "ComSpec", "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA"))
                if (inherited.containsKey(key)) builder.environment().put(key, inherited.get(key));
            Map<String, String> env = builder.environment();
            env.putAll(secrets);
            env.put("TOOLHELPER_NODERED_PORT", Integer.toString(properties.port()));
            env.put("TOOLHELPER_NODERED_DATA", properties.data().toString());
            env.put("NODE_ENV", "production");
            int apiPort = environment.getProperty("local.server.port", Integer.class, environment.getProperty("server.port", Integer.class, 0));
            if (apiPort <= 0) throw new NodeRedException("API_NOT_READY", "Java回环协调服务尚未就绪");
            env.put("TOOLHELPER_SERIAL_API", "http://127.0.0.1:" + apiPort + "/api/internal/v1/serial/leases");
            env.put("TOOLHELPER_SERIAL_TOKEN", serialPorts.nodeRedToken());
            env.put("TOOLHELPER_SERIAL_ORIGIN", environment.getProperty("toolhelper.allowed-origins", "http://127.0.0.1:5173").split(",")[0]);
            env.put("TOOLHELPER_PARENT_PID", Long.toString(ProcessHandle.current().pid())); env.put("TOOLHELPER_INSTANCE_ID", instanceId);
            env.put("PATH", directory.resolve("runtime") + File.pathSeparator + inherited.getOrDefault("SystemRoot", "C:\\Windows") + "\\System32");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            if (closed) throw new NodeRedException("SERVICE_CLOSING", "ToolHelper 正在退出");
            process = builder.start();
            serialPorts.registerNodeRed(process.pid());
            if (closed) { process.destroyForcibly(); throw new NodeRedException("SERVICE_CLOSING", "ToolHelper 正在退出"); }
            startedAt = process.info().startInstant().orElse(Instant.now()); runningRelease = release;
            NodeRedFiles.atomicWrite(properties.home().resolve("process.json"), json.writeValueAsBytes(Map.of("pid", process.pid(), "startedAt", startedAt.toString(), "version", release.version(), "port", properties.port(), "instanceId", instanceId, "ownerPid", ProcessHandle.current().pid())));
            Instant deadline = Instant.now().plusSeconds(45);
            while (Instant.now().isBefore(deadline)) {
                if (closed) throw new NodeRedException("SERVICE_CLOSING", "ToolHelper 正在退出");
                if (!process.isAlive()) throw new NodeRedException("PROCESS_EXITED", "NodeRed 启动失败，退出码=" + process.exitValue());
                if (health.ready(properties.port(), instanceId, release.version())) { log("NodeRed 页面已就绪，版本 " + release.version()); return status(); }
                Thread.sleep(200);
            }
            throw new NodeRedException("START_TIMEOUT", "NodeRed 在 45 秒内未就绪");
        } catch (Exception error) {
            stop();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (error instanceof NodeRedException known) throw known;
            if (error instanceof BindException) throw new NodeRedException("PORT_IN_USE", "端口 " + properties.port() + " 被占用，请调整 NodeRed 端口或关闭已知应用", error);
            throw new NodeRedException("START_FAILED", "NodeRed 启动失败，请检查运行包和目录权限", error);
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
                if (!owned.waitFor(3, TimeUnit.SECONDS)) throw new NodeRedException("STOP_TIMEOUT", "Node-RED 进程未能在退出期限内结束");
                Instant deadline = Instant.now().plusSeconds(1);
                while (children.values().stream().anyMatch(OwnedChild::alive) && Instant.now().isBefore(deadline)) Thread.sleep(25);
                if (children.values().stream().anyMatch(OwnedChild::alive)) throw new NodeRedException("STOP_TIMEOUT", "NodeRed 子进程未能结束");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new NodeRedException("STOP_INTERRUPTED", "Node-RED 进程清理被中断", error); }
            log("已停止本实例托管的 Node-RED 进程树");
            process = null; children.clear(); serialPorts.registerNodeRed(null);
        }
        try { if (owned != null) Files.deleteIfExists(properties.home().resolve("process.json")); }
        catch (IOException error) { log("Node-RED 进程记录清理失败"); }
        return status();
    }
    public synchronized List<String> logs() { return List.copyOf(logs); }
    public synchronized void log(String message) {
        Instant at = Instant.now(); logs.addLast(at + " " + message); while (logs.size() > 200) logs.removeFirst();
        events.add(new Event(Long.toString(sequence.incrementAndGet()), "nodered.lifecycle", instanceId, at, instanceId, Map.of("level", "INFO", "message", message)));
        while (events.size() > 200) events.remove(0);
    }
    public List<Event> eventsAfter(long after) { return events.stream().filter(e -> Long.parseLong(e.eventId()) > after).toList(); }
    private void trackChildren() {
        List<Process> parents = new ArrayList<>(installScripts.values());
        Process current = process; if (current != null) parents.add(current);
        try { for (Process parent : parents) if (parent.isAlive()) parent.descendants().forEach(handle -> handle.info().startInstant().ifPresent(start -> children.putIfAbsent(handle.pid(), new OwnedChild(handle, start)))); }
        catch (RuntimeException error) { System.err.println("NodeRed 子进程状态读取失败"); }
    }
    /** 安装脚本与运行时共享退出回收登记；不把用户脚本交给 shell/npm。 */
    public void executeInstallScript(ProcessBuilder builder) throws IOException, InterruptedException {
        Process child;
        synchronized (this) {
            if (closed) throw new NodeRedException("SERVICE_CLOSING", "ToolHelper 正在退出");
            child = builder.start(); installScripts.put(child.pid(), child);
        }
        try {
            if (!child.waitFor(120, TimeUnit.SECONDS)) throw new IOException("经审批脚本执行超时");
            if (child.exitValue() != 0) throw new IOException("经审批脚本退出码=" + child.exitValue());
        } finally {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            if (child.isAlive()) child.destroyForcibly();
            child.waitFor(3, TimeUnit.SECONDS);
            installScripts.remove(child.pid());
            children.values().forEach(item -> item.destroy(true));
        }
    }
    public synchronized void claimOwnership() throws IOException {
        if (closed) throw new NodeRedException("SERVICE_CLOSING", "ToolHelper 正在退出");
        if (lock != null && lock.isValid()) return;
        NodeRedFiles.privateDirectory(properties.home());
        lockChannel = FileChannel.open(properties.home().resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); } catch (OverlappingFileLockException error) { lock = null; }
        if (lock == null) { releaseLock(); throw new NodeRedException("INSTANCE_IN_USE", "另一个 ToolHelper 实例正在管理 NodeRed"); }
        try {
            recoverRegisteredOrphan();
            if (closed) throw new NodeRedException("SERVICE_CLOSING", "ToolHelper 正在退出");
        } catch (IOException | RuntimeException error) { releaseLock(); throw error; }
    }
    /** 只在登记PID、启动时刻、node绝对路径和独立userDir全部匹配时清理孤立实例。 */
    private void recoverRegisteredOrphan() throws IOException {
        Path record = properties.home().resolve("process.json");
        if (!Files.isRegularFile(record)) return;
        var saved = json.readTree(record.toFile());
        long pid = saved.path("pid").asLong(-1);
        if (pid <= 0) throw new IOException("历史进程登记无效");
        Optional<ProcessHandle> found = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        if (found.isEmpty()) { Files.delete(record); return; }
        ProcessHandle orphan = found.get();
        var info = orphan.info();
        Instant observedStart = info.startInstant().orElseThrow(() -> new IOException("无法验证历史进程启动时间"));
        boolean sameStart = observedStart.toString().equals(saved.path("startedAt").asText());
        String command = info.command().orElse("");
        Release current = packages.current();
        String expected = current == null ? "" : packages.directory(current).resolve("runtime/node.exe").toString();
        String[] args = info.arguments().orElse(new String[0]);
        boolean ownArguments = Arrays.asList(args).contains(properties.data().toString()) && Arrays.asList(args).contains("node_modules/node-red/red.js");
        if (!sameStart) { Files.delete(record); return; } // PID已经复用，不接触新进程。
        long owner = saved.path("ownerPid").asLong(-1);
        if (owner <= 0 || ProcessHandle.of(owner).filter(ProcessHandle::isAlive).isPresent() || !command.equalsIgnoreCase(expected) || !ownArguments)
            throw new NodeRedException("ORPHAN_UNVERIFIABLE", "历史Node-RED进程仍存在但身份不能安全确认，请核对登记进程");
        List<ProcessHandle> descendants = orphan.descendants().toList();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly); orphan.destroyForcibly();
        try { orphan.onExit().get(5, TimeUnit.SECONDS); }
        catch (Exception error) { throw new IOException("孤立进程未能退出", error); }
        Files.delete(record); log("已清理本产品登记并验证身份的孤立Node-RED进程");
    }
    private void releaseLock() {
        try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); }
        catch (IOException error) { log("NodeRed 所有权锁释放失败"); }
        finally { lock = null; lockChannel = null; }
    }
    @Override public void close() {
        closed = true; watcher.shutdownNow();
        // 先通知就绪循环退出，再持锁收尾，避免启动写入与释放所有权锁交错。
        synchronized (this) {
            try {
                trackChildren(); children.values().forEach(child -> child.destroy(true));
                installScripts.values().forEach(Process::destroyForcibly);
                Process owned = process;
                if (owned != null) { owned.destroyForcibly(); if (!owned.waitFor(5, TimeUnit.SECONDS)) System.err.println("Node-RED退出清理超时"); }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { releaseLock(); try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { /* JVM退出期间由当前hook完成清理。 */ } }
        }
    }
    private record OwnedChild(ProcessHandle handle, Instant started) {
        boolean alive() { return handle.isAlive() && handle.info().startInstant().map(started::equals).orElse(false); }
        void destroy(boolean force) { if (alive()) { if (force) handle.destroyForcibly(); else handle.destroy(); } }
    }
}
