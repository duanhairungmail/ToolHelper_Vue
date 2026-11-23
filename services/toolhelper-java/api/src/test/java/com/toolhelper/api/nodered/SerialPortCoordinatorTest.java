package com.toolhelper.api.nodered;

import com.toolhelper.application.nodered.SerialPortCoordinator;
import com.toolhelper.application.nodered.NodeRedContracts.NodeRedException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SerialPortCoordinatorTest {
    @Test void bothOwnersShareNormalizedExclusiveLeaseAndCannotReleaseEachOther() {
        var ports = new SerialPortCoordinator(); long pid = ProcessHandle.current().pid(); ports.registerNodeRed(pid);
        var lease = ports.acquire("\\\\.\\com3", "C# Agent", pid);
        assertEquals("COM3", lease.port());
        var error = assertThrows(NodeRedException.class, () -> ports.acquire("COM3", "Node-RED", pid));
        assertEquals("SERIAL_PORT_IN_USE", error.code()); assertTrue(error.getMessage().contains("C# Agent"));
        assertThrows(NodeRedException.class, () -> ports.release(lease.id(), "Node-RED"));
        ports.release(lease.id(), "C# Agent");
        var node = ports.acquire("COM3", "Node-RED", pid);
        assertThrows(NodeRedException.class, () -> ports.acquire("com3", "C# Agent", pid));
        ports.release(node.id(), "Node-RED"); assertTrue(ports.list().isEmpty());
    }
    @Test void unregisteredNodeAndInvalidPortsAreRejected() {
        var ports = new SerialPortCoordinator();
        assertThrows(NodeRedException.class, () -> ports.acquire("COM3", "Node-RED", ProcessHandle.current().pid()));
        for (String port : new String[]{"COM0", "COM-1", "COM1/../COM2", "ttyUSB0", "COM1 "}) assertThrows(NodeRedException.class, () -> SerialPortCoordinator.normalize(port));
    }
}
