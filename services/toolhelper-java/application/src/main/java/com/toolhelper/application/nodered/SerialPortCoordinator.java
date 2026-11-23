package com.toolhelper.application.nodered;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.toolhelper.application.nodered.NodeRedContracts.*;

/** 进程存活期间租约不会自动过期，防止长时间串口操作被另一个服务抢占。 */
public final class SerialPortCoordinator {
    public record Lease(String id, String port, String owner, long pid, Instant startedAt, String releaseHint) {}
    private final Map<String, Lease> leases = new HashMap<>();
    private final String nodeRedToken = UUID.randomUUID().toString() + UUID.randomUUID();
    private volatile Long nodeRedPid;
    public String nodeRedToken() { return nodeRedToken; }
    public boolean matchesNodeRedToken(String token) { return MessageDigest.isEqual(nodeRedToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)); }
    public void registerNodeRed(Long pid) { nodeRedPid = pid; }

    public synchronized Lease acquire(String rawPort, String owner, long pid) {
        String port = normalize(rawPort);
        if (!Set.of("Node-RED", "C# Agent").contains(owner) || "Node-RED".equals(owner) && !Objects.equals(nodeRedPid, pid))
            throw new NodeRedException("SERIAL_OWNER_INVALID", "串口请求不属于当前受管服务");
        Instant started = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(p -> p.info().startInstant())
            .orElseThrow(() -> new NodeRedException("SERIAL_OWNER_INVALID", "串口所属进程不可验证"));
        cleanExited();
        Lease existing = leases.get(port);
        if (existing != null) throw new NodeRedException("SERIAL_PORT_IN_USE", port + " 被 " + existing.owner() + " 占用；" + existing.releaseHint());
        Lease lease = new Lease(UUID.randomUUID().toString(), port, owner, pid, started,
            "Node-RED".equals(owner) ? "请关闭相应串口流程或停止Node-RED" : "请在C#串口工具中关闭该连接");
        leases.put(port, lease); return lease;
    }
    public synchronized void release(String id, String owner) {
        Lease lease = leases.values().stream().filter(l -> l.id().equals(id)).findFirst().orElse(null);
        if (lease == null) return;
        if (!lease.owner().equals(owner)) throw new NodeRedException("SERIAL_OWNER_INVALID", "不能释放其他服务的串口租约");
        leases.remove(lease.port());
    }
    public synchronized List<Lease> list() { cleanExited(); return List.copyOf(leases.values()); }
    private void cleanExited() { leases.values().removeIf(l -> ProcessHandle.of(l.pid()).filter(ProcessHandle::isAlive)
        .flatMap(p -> p.info().startInstant()).map(l.startedAt()::equals).orElse(false) == false); }
    public static String normalize(String port) {
        if (port == null) throw new NodeRedException("SERIAL_PORT_INVALID", "缺少串口号");
        String value = port.toUpperCase(Locale.ROOT);
        if (value.startsWith("\\\\.\\")) value = value.substring(4);
        if (!value.matches("COM[1-9][0-9]{0,4}")) throw new NodeRedException("SERIAL_PORT_INVALID", "仅支持Windows COM串口号");
        return value;
    }
}
