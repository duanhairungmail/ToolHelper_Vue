package com.toolhelper.infrastructure.electerm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.io.*;

public final class ElectermHealthService {
    private final ObjectMapper json;
    public ElectermHealthService(ObjectMapper json) { this.json = json; }
    public boolean ready(int port, String instanceId, String version) {
        try {
            byte[] bytes = get(port, "/toolhelper/health", 4096);
            var body = json.readTree(bytes);
            if (!instanceId.equals(body.path("instanceId").asText()) || !version.equals(body.path("version").asText()) || !body.path("authenticated").asBoolean() || !body.path("localTerminalDisabled").asBoolean()) return false;
            String page = new String(get(port, "/", 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            return page.contains("/js/basic-" + version + ".js") && page.contains("container");
        } catch (IOException error) { return false; }
    }
    private static byte[] get(int port, String path, int maximum) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(500); connection.setReadTimeout(500); connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("健康检查未返回 200");
            try (InputStream input = connection.getInputStream()) { byte[] bytes = input.readNBytes(maximum + 1); if (bytes.length > maximum) throw new IOException("健康响应超限"); return bytes; }
        } finally { connection.disconnect(); }
    }
}
