package com.toolhelper.infrastructure.nodered;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import org.springframework.core.env.Environment;

/** 运行目录、发布源和公钥仅由本机部署配置决定，网页不能修改信任根。 */
public record NodeRedProperties(Path home, Path data, Path backups, Path secret, int port, URI feed, Path publicKey, Path inbox, Set<String> nodeAllowlist) {
    public NodeRedProperties {
        home = home.toAbsolutePath().normalize(); data = data.toAbsolutePath().normalize();
        backups = backups.toAbsolutePath().normalize(); secret = secret.toAbsolutePath().normalize(); inbox = inbox.toAbsolutePath().normalize();
        nodeAllowlist = Set.copyOf(nodeAllowlist);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Node-RED 端口必须在1024–65535");
        List<Path> roots = List.of(home, data, backups, secret.getParent());
        for (int i = 0; i < roots.size(); i++) for (int j = i + 1; j < roots.size(); j++)
            if (roots.get(i).getParent() == null || roots.get(i).startsWith(roots.get(j)) || roots.get(j).startsWith(roots.get(i)))
                throw new IllegalArgumentException("程序、数据、备份和密钥必须位于独立的非根目录");
        if (feed != null && (!"https".equals(feed.getScheme()) || feed.getUserInfo() != null || feed.getQuery() != null || feed.getFragment() != null || !feed.getPath().endsWith("/")))
            throw new IllegalArgumentException("受控源必须是无凭据且以/结尾的HTTPS地址");
    }
    public static NodeRedProperties load(Environment env) {
        String local = env.getProperty("LOCALAPPDATA", System.getenv("LOCALAPPDATA"));
        Path base = local == null || local.isBlank() ? Path.of(System.getProperty("user.home"), ".local/share/ToolHelper") : Path.of(local, "ToolHelper");
        Path home = Path.of(env.getProperty("toolhelper.nodered.home", base.resolve("integrations/nodered").toString()));
        String feed = env.getProperty("toolhelper.nodered.feed", ""), key = env.getProperty("toolhelper.nodered.public-key", "");
        String allow = env.getProperty("toolhelper.nodered.node-allowlist", "");
        return new NodeRedProperties(home, Path.of(env.getProperty("toolhelper.nodered.data", base.resolve("data/nodered").toString())),
            Path.of(env.getProperty("toolhelper.nodered.backups", base.resolve("backups/nodered").toString())),
            Path.of(env.getProperty("toolhelper.nodered.secret", base.resolve("secrets/nodered-credential-secret.bin").toString())),
            env.getProperty("toolhelper.nodered.port", Integer.class, 1880), feed.isBlank() ? null : URI.create(feed), key.isBlank() ? null : Path.of(key),
            Path.of(env.getProperty("toolhelper.nodered.inbox", home.resolve("inbox").toString())),
            allow.isBlank() ? Set.of() : Set.of(allow.split(",")));
    }
    public Path credentials() { return home.resolve("private/admin.properties"); }
}
