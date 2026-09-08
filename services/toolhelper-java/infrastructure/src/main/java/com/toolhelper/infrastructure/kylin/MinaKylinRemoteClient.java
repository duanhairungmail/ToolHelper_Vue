package com.toolhelper.infrastructure.kylin;

import com.toolhelper.application.kylin.KylinRemoteClient;
import com.toolhelper.application.kylin.KylinCommandPolicy;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;

/** Apache MINA SSHD 适配器。SSH 和 SFTP 任一失败都会关闭整个会话。 */
@Component
public class MinaKylinRemoteClient implements KylinRemoteClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    @Override public RemoteSession connect(ConnectionOptions options) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setSessionHeartbeat(org.apache.sshd.common.session.helpers.AbstractSession.HeartbeatType.IGNORE, null, 15_000L);
        client.start();
        ClientSession session = null;
        try {
            session = client.connect(options.username(), options.host(), options.port()).verify(CONNECT_TIMEOUT).getSession();
            if (options.privateKey() != null && !options.privateKey().isBlank()) {
                Path keyPath = Path.of(options.privateKey()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(keyPath)) throw new IllegalArgumentException("私钥文件不存在");
                FilePasswordProvider provider = options.privateKeyPassphrase() == null ? FilePasswordProvider.EMPTY : FilePasswordProvider.of(options.privateKeyPassphrase());
                Collection<KeyPair> keys = SecurityUtils.getKeyPairResourceParser().loadKeyPairs(null, keyPath, provider);
                if (keys.isEmpty()) throw new IllegalArgumentException("私钥未包含可用密钥");
                keys.forEach(session::addPublicKeyIdentity);
            } else if (options.password() != null) {
                session.addPasswordIdentity(options.password());
            }
            session.auth().verify(CONNECT_TIMEOUT);
            SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            MinaSession remote = new MinaSession(client, session, sftp, options);
            RemoteResult arch = remote.execute("uname -m", CONNECT_TIMEOUT);
            if (arch.exitCode() != 0) throw new IllegalStateException("无法读取远端架构");
            remote.architecture = arch.stdout().trim();
            return remote;
        } catch (Exception error) {
            if (session != null) session.close();
            client.stop();
            throw error;
        }
    }

    private static final class MinaSession implements RemoteSession {
        private final SshClient client; private final ClientSession session; private final SftpClient sftp; private final ConnectionOptions options;
        private String architecture; private boolean closed;
        MinaSession(SshClient client, ClientSession session, SftpClient sftp, ConnectionOptions options) { this.client=client; this.session=session; this.sftp=sftp; this.options=options; }
        @Override public RemoteResult execute(String command, Duration timeout) throws Exception {
            ensureOpen();
            String actual = command;
            ByteArrayInputStream stdin = null;
            if (options.sudoPassword() != null && !options.sudoPassword().isBlank() && !"root".equals(options.username())) {
                actual = "sudo -S -p '' -- sh -c " + KylinCommandPolicy.shellQuote(command);
                stdin = new ByteArrayInputStream((options.sudoPassword() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream stdout = new ByteArrayOutputStream(); ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (ChannelExec channel = session.createExecChannel(actual)) {
                channel.setOut(stdout); channel.setErr(stderr); if (stdin != null) channel.setIn(stdin);
                channel.open().verify(timeout); channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout.toMillis());
                return new RemoteResult(channel.getExitStatus() == null ? -1 : channel.getExitStatus(), redact(stdout.toString(StandardCharsets.UTF_8)), redact(stderr.toString(StandardCharsets.UTF_8)), false);
            }
        }
        @Override public void upload(String remotePath, byte[] content) throws Exception { ensureOpen(); try (SftpClient.CloseableHandle handle = sftp.open(remotePath, java.util.EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate))) { sftp.write(handle, 0, content); } }
        @Override public String architecture() { return architecture; }
        @Override public boolean sftpReady() { return !closed && sftp != null; }
        @Override public void close() { if (closed) return; closed=true; try { sftp.close(); } catch (Exception ignored) {} try { session.close(); } catch (Exception ignored) {} try { client.stop(); } catch (Exception ignored) {} }
        private void ensureOpen() { if (closed) throw new IllegalStateException("会话已关闭"); }
        private static String redact(String value) { return value.replaceAll("(?i)(password|passwd|token|secret)=\\S+", "$1=[REDACTED]"); }
    }
}
