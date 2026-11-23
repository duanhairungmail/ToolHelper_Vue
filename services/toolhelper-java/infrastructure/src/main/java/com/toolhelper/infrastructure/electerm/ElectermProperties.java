package com.toolhelper.infrastructure.electerm;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

/** 路径和受控源由本机配置决定，网页不能更改信任根或下载地址。 */
public record ElectermProperties(Path home, Path data, int port, URI feed, Path publicKey, Path inbox) {
    public ElectermProperties {
        home = home.toAbsolutePath().normalize(); data = data.toAbsolutePath().normalize();
        inbox = inbox.toAbsolutePath().normalize();
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Electerm 端口必须在 1024–65535 范围");
        if (home.getParent() == null || data.getParent() == null || data.startsWith(home) || home.startsWith(data)) throw new IllegalArgumentException("程序与数据必须位于两个独立的非根目录");
        if (feed != null && (!"https".equals(feed.getScheme()) || feed.getUserInfo() != null || feed.getQuery() != null || feed.getFragment() != null || !feed.getPath().endsWith("/")))
            throw new IllegalArgumentException("受控源必须是无凭据、无查询参数并以 / 结尾的 HTTPS 地址");
    }

    public static ElectermProperties load(Environment environment) {
        String local = environment.getProperty("LOCALAPPDATA", System.getenv("LOCALAPPDATA"));
        Path base = local == null || local.isBlank() ? Path.of(System.getProperty("user.home"), ".local", "share", "ToolHelper") : Path.of(local, "ToolHelper");
        Path home = Path.of(environment.getProperty("toolhelper.electerm.home", base.resolve("integrations/electerm").toString()));
        String feed = environment.getProperty("toolhelper.electerm.feed", "");
        String key = environment.getProperty("toolhelper.electerm.public-key", "");
        return new ElectermProperties(home, Path.of(environment.getProperty("toolhelper.electerm.data", base.resolve("data/electerm").toString())),
                environment.getProperty("toolhelper.electerm.port", Integer.class, 5577), feed.isBlank() ? null : URI.create(feed),
                key.isBlank() ? null : Path.of(key), Path.of(environment.getProperty("toolhelper.electerm.inbox", home.resolve("inbox").toString())));
    }
    public Path backups() { return home.resolve("backups"); }
    public Path credentials() { return home.resolve("private/auth.properties"); }
}
