package com.toolhelper.application.kylin;

import java.time.Duration;

/** 远程访问端口，基础设施层负责 SSH/SFTP 具体实现。 */
public interface KylinRemoteClient {
    RemoteSession connect(ConnectionOptions options) throws Exception;
    record ConnectionOptions(String host, int port, String username, String password,
                             String privateKey, String privateKeyPassphrase, String sudoPassword) {
        public ConnectionOptions {
            if (port <= 0) port = 22;
            if (host == null || host.isBlank() || username == null || username.isBlank())
                throw new IllegalArgumentException("主机和用户名不能为空");
        }
    }
    interface RemoteSession extends AutoCloseable {
        RemoteResult execute(String command, Duration timeout) throws Exception;
        void upload(String remotePath, byte[] content) throws Exception;
        default void uploadText(String remotePath, String content) throws Exception { upload(remotePath, content.replace("\r\n", "\n").replace('\r', '\n').getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        String architecture();
        boolean sftpReady();
        @Override void close();
    }
    record RemoteResult(int exitCode, String stdout, String stderr, boolean changed) {}
}
