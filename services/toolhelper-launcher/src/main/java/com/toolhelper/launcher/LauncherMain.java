package com.toolhelper.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 启动本机三端，等待健康检查通过，并在退出时回收受管进程。 */
public final class LauncherMain {
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEALTH_RETRY_INTERVAL = Duration.ofMillis(300);

    private LauncherMain() {}

    public static void main(String[] args) throws Exception {
        RuntimeSession session = new RuntimeSession(LauncherConfig.load());
        Thread shutdownHook = new Thread(session::closeQuietly, "toolhelper-launcher-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            session.start();
            session.awaitProcesses();
        } finally {
            session.close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 正在关闭，shutdown hook 已经接管清理。
            }
        }
    }

    private static final class RuntimeSession implements AutoCloseable {
        private final LauncherConfig config;
        private final ProcessSupervisor processes = new ProcessSupervisor();
        private RuntimeFile runtimeFile;
        private boolean closed;

        private RuntimeSession(LauncherConfig config) {
            this.config = config;
        }

        private void start() throws Exception {
            config.validate();
            int javaPort = freePort();
            int localPort = freePort();
            int frontendPort = freePort();
            String sessionToken = token();
            String internalToken = token();
            String frontendOrigin = "http://127.0.0.1:" + frontendPort;

            runtimeFile = RuntimeFile.write(config.frontendDir.resolve("public/runtime-config.js"),
                    "window.__TOOLHELPER_RUNTIME_CONFIG__={" +
                            quote("javaApiBase", "http://127.0.0.1:" + javaPort) + "," +
                            quote("localApiBase", "http://127.0.0.1:" + localPort) + "," +
                            quote("sessionToken", sessionToken) + "};\n");

            Map<String, String> javaEnvironment = Map.of(
                    "TOOLHELPER_JAVA_PORT", Integer.toString(javaPort),
                    "TOOLHELPER_SESSION_TOKEN", sessionToken,
                    "TOOLHELPER_INTERNAL_TOKEN", internalToken,
                    "TOOLHELPER_ALLOWED_ORIGINS", frontendOrigin);
            Map<String, String> csharpEnvironment = Map.of(
                    "TOOLHELPER_LOCAL_PORT", Integer.toString(localPort),
                    "TOOLHELPER_SESSION_TOKEN", sessionToken,
                    "TOOLHELPER_INTERNAL_TOKEN", internalToken,
                    "TOOLHELPER_ALLOWED_ORIGINS", frontendOrigin);

            processes.start("java", config.javaDir, javaCommand(), javaEnvironment);
            processes.start("csharp", config.csharpDir, csharpCommand(), csharpEnvironment);
            processes.start("frontend", config.frontendDir, frontendCommand(frontendPort), Map.of());

            awaitHealth("Java", URI.create("http://127.0.0.1:" + javaPort + "/actuator/health"),
                    frontendOrigin, sessionToken, "UP");
            awaitHealth("C#", URI.create("http://127.0.0.1:" + localPort + "/health"),
                    frontendOrigin, sessionToken, "UP");
            awaitHttp200("Vue", URI.create(frontendOrigin + "/"));

            System.out.println("ToolHelper 已启动：" + frontendOrigin + "/");
            openBrowser(frontendOrigin + "/");
        }

        private void awaitProcesses() throws InterruptedException, IOException {
            while (!closed) {
                ProcessSupervisor.ExitedProcess exited = processes.findExited();
                if (exited != null) {
                    throw new IOException(exited.name() + " 进程意外退出，退出码=" + exited.exitCode());
                }
                Thread.sleep(500);
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            processes.close();
            if (runtimeFile != null) runtimeFile.restore();
        }

        private void closeQuietly() {
            try {
                close();
            } catch (RuntimeException error) {
                System.err.println("Launcher 清理失败：" + error.getMessage());
            }
        }
    }

    private static final class LauncherConfig {
        private final Path rootDir;
        private final Path frontendDir;
        private final Path javaDir;
        private final Path csharpDir;

        private LauncherConfig(Path rootDir, Path frontendDir, Path javaDir, Path csharpDir) {
            this.rootDir = rootDir;
            this.frontendDir = frontendDir;
            this.javaDir = javaDir;
            this.csharpDir = csharpDir;
        }

        private static LauncherConfig load() {
            Path root = pathProperty("toolhelper.rootDir", detectRoot());
            return new LauncherConfig(root,
                    pathProperty("toolhelper.frontendDir", root),
                    pathProperty("toolhelper.javaDir", root.resolve("services/toolhelper-java")),
                    pathProperty("toolhelper.csharpDir", root.resolve("services/toolhelper-agent")));
        }

        private static Path detectRoot() {
            Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            for (Path probe = current; probe != null; probe = probe.getParent()) {
                if (Files.isRegularFile(probe.resolve("package.json"))
                        && Files.isDirectory(probe.resolve("services/toolhelper-java"))) {
                    return probe;
                }
            }
            if (current.endsWith(Path.of("services", "toolhelper-java"))) {
                return current.getParent().getParent();
            }
            return current;
        }

        private static Path pathProperty(String name, Path fallback) {
            String value = System.getProperty(name);
            return (value == null || value.isBlank())
                    ? fallback.toAbsolutePath().normalize()
                    : Path.of(value).toAbsolutePath().normalize();
        }

        private void validate() {
            requireDirectory(rootDir, "项目根目录");
            requireDirectory(frontendDir, "前端目录");
            requireDirectory(javaDir, "Java 工程目录");
            requireDirectory(csharpDir.resolve("ToolHelper.Agent.Api"), "C# API 工程目录");
            if (!Files.isRegularFile(frontendDir.resolve("node_modules/vite/bin/vite.js"))) {
                throw new IllegalStateException("缺少前端依赖，请先执行 npm install：" + frontendDir.resolve("node_modules/vite"));
            }
            String wrapper = isWindows() ? "gradlew.bat" : "gradlew";
            if (!Files.isRegularFile(javaDir.resolve(wrapper))) {
                throw new IllegalStateException("缺少 Gradle Wrapper：" + javaDir.resolve(wrapper));
            }
        }

        private static void requireDirectory(Path path, String label) {
            if (!Files.isDirectory(path)) throw new IllegalStateException(label + "不存在：" + path);
        }
    }

    private static final class ProcessSupervisor implements AutoCloseable {
        private final List<ManagedProcess> processes = new ArrayList<>();

        private synchronized Process start(String name, Path directory, List<String> command, Map<String, String> environment)
                throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process;
            try {
                process = builder.start();
            } catch (IOException error) {
                throw new IOException("启动 " + name + " 失败：" + String.join(" ", command), error);
            }
            ManagedProcess managed = new ManagedProcess(name, process);
            processes.add(managed);
            Thread.ofVirtual().name("toolhelper-" + name + "-output").start(() ->
                    process.inputReader(StandardCharsets.UTF_8).lines()
                            .forEach(line -> System.out.println("[" + name + "] " + line)));
            return process;
        }

        private synchronized ExitedProcess findExited() {
            for (ManagedProcess process : processes) {
                if (!process.process.isAlive()) {
                    return new ExitedProcess(process.name, process.process.exitValue());
                }
            }
            return null;
        }

        @Override
        public synchronized void close() {
            List<ManagedProcess> copy = new ArrayList<>(processes);
            Collections.reverse(copy);
            for (ManagedProcess process : copy) stop(process.process);
            processes.clear();
        }

        private static void stop(Process process) {
            List<ProcessHandle> descendants = process.toHandle().descendants().toList();
            for (int index = descendants.size() - 1; index >= 0; index--) descendants.get(index).destroy();
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    for (int index = descendants.size() - 1; index >= 0; index--) descendants.get(index).destroyForcibly();
                    process.destroyForcibly();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private record ManagedProcess(String name, Process process) {}

        private record ExitedProcess(String name, int exitCode) {}
    }

    private record RuntimeFile(Path path, byte[] previous) {
        private static RuntimeFile write(Path path, String content) throws IOException {
            Files.createDirectories(path.getParent());
            byte[] previous = Files.exists(path) ? Files.readAllBytes(path) : null;
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return new RuntimeFile(path, previous);
        }

        private void restore() {
            try {
                if (previous == null) Files.deleteIfExists(path);
                else Files.write(path, previous);
            } catch (IOException error) {
                System.err.println("运行时配置清理失败：" + path + "，" + error.getMessage());
            }
        }
    }

    private static void awaitHealth(String name, URI uri, String origin, String token, String marker)
            throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(HEALTH_TIMEOUT);
        IOException lastError = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                LocalHttpResponse response = localGet(uri, Map.of(
                        "Origin", origin,
                        "Authorization", "Bearer " + token,
                        "X-Trace-Id", "launcher-health-" + token.substring(0, 8)));
                if (response.statusCode() == 200 && response.body().contains(marker)
                        && response.body().contains("traceId")) return;
                lastError = new IOException(name + " 健康检查返回 HTTP " + response.statusCode());
            } catch (IOException error) {
                lastError = error;
            }
            Thread.sleep(HEALTH_RETRY_INTERVAL.toMillis());
        }
        throw new IOException(name + " 健康检查超时：" + uri, lastError);
    }

    private static void awaitHttp200(String name, URI uri) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(HEALTH_TIMEOUT);
        IOException lastError = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                LocalHttpResponse response = localGet(uri, Map.of());
                if (response.statusCode() == 200) return;
                lastError = new IOException(name + " 返回 HTTP " + response.statusCode());
            } catch (IOException error) {
                lastError = error;
            }
            Thread.sleep(HEALTH_RETRY_INTERVAL.toMillis());
        }
        throw new IOException(name + " 健康检查超时：" + uri, lastError);
    }

    private static LocalHttpResponse localGet(URI uri, Map<String, String> headers) throws IOException {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1) {
            throw new IOException("健康检查地址必须是带端口的 HTTP 回环地址：" + uri);
        }
        String target = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) target += "?" + uri.getRawQuery();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1_000);
            socket.setSoTimeout(1_000);
            StringBuilder request = new StringBuilder()
                    .append("GET ").append(target).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(uri.getHost()).append(":").append(uri.getPort()).append("\r\n")
                    .append("Connection: close\r\n");
            headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
            request.append("\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int lineEnd = response.indexOf("\r\n");
            int headerEnd = response.indexOf("\r\n\r\n");
            if (lineEnd < 0 || headerEnd < 0) throw new IOException("健康检查响应格式无效");
            String[] statusLine = response.substring(0, lineEnd).split(" ", 3);
            if (statusLine.length < 2) throw new IOException("健康检查状态行无效");
            return new LocalHttpResponse(Integer.parseInt(statusLine[1]), response.substring(headerEnd + 4));
        }
    }

    private record LocalHttpResponse(int statusCode, String body) {}

    private static List<String> javaCommand() {
        if (isWindows()) return List.of("cmd.exe", "/c", "gradlew.bat", ":api:bootRun", "--no-daemon");
        return List.of("./gradlew", ":api:bootRun", "--no-daemon");
    }

    private static List<String> csharpCommand() {
        return List.of("dotnet", "run", "--project", "ToolHelper.Agent.Api", "--no-launch-profile");
    }

    private static List<String> frontendCommand(int port) {
        return List.of("node", "node_modules/vite/bin/vite.js", "--host", "127.0.0.1",
                "--port", Integer.toString(port), "--strictPort");
    }

    private static void openBrowser(String url) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            System.out.println("当前环境不支持自动打开浏览器，请访问：" + url);
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (IOException | UnsupportedOperationException error) {
            System.out.println("自动打开浏览器失败，请访问：" + url);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String quote(String name, String value) {
        return "\"" + name + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
