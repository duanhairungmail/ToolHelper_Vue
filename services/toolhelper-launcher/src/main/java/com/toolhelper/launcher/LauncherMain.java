package com.toolhelper.launcher;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/** 为本机三端分配端口和短期令牌，并通过启动注入提供给前端。 */
public final class LauncherMain {
    private LauncherMain() {}

    public static void main(String[] args) throws IOException {
        Path frontend = Path.of(System.getProperty("toolhelper.frontendDir", "."));
        int javaPort = freePort();
        int localPort = freePort();
        String sessionToken = token();
        String internalToken = token();

        Path runtimeFile = frontend.resolve("public/runtime-config.js");
        Files.createDirectories(runtimeFile.getParent());
        Files.writeString(runtimeFile, "window.__TOOLHELPER_RUNTIME_CONFIG__=" +
                "{" + quote("javaApiBase", "http://127.0.0.1:" + javaPort) + "," +
                quote("localApiBase", "http://127.0.0.1:" + localPort) + "," +
                quote("sessionToken", sessionToken) + "};\n", StandardCharsets.UTF_8);

        System.out.printf("TOOLHELPER_JAVA_PORT=%d%nTOOLHELPER_LOCAL_PORT=%d%n", javaPort, localPort);
        System.out.println("TOOLHELPER_SESSION_TOKEN=" + sessionToken);
        System.out.println("TOOLHELPER_INTERNAL_TOKEN=" + internalToken);
        System.out.println("ToolHelper runtime config written to " + runtimeFile.toAbsolutePath());
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
}
