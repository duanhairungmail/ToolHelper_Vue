package com.toolhelper.api.nodered;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toolhelper.api.security.RequestSecurityFilter;
import com.toolhelper.application.nodered.*;
import com.toolhelper.application.nodered.NodeRedContracts.NodeRedException;
import com.toolhelper.infrastructure.RuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NodeRedSecurityTest {
    private final SerialPortCoordinator ports = new SerialPortCoordinator();
    private final RuntimeProperties runtime = new RuntimeProperties("session", "internal", List.of("http://127.0.0.1:5173"));
    @Test void dedicatedNodeTokenOnlyAuthorizesSerialPaths() throws Exception {
        var filter = new RequestSecurityFilter(runtime, new ObjectMapper(), ports);
        for (String path : List.of("/api/internal/v1/serial/leases", "/api/integrations/nodered/start", "/api/internal/v1/serial/leases-other")) {
            var request = request(path, ports.nodeRedToken()); var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            assertEquals(path.equals("/api/internal/v1/serial/leases") ? 200 : 401, response.getStatus());
        }
    }
    @Test void browserTokenCanInspectButCannotClaimOrReleasePorts() {
        var controller = new SerialPortController(ports, runtime); var request = request("/api/internal/v1/serial/leases", "session");
        assertTrue(controller.list(request).data().isEmpty());
        assertEquals("SERIAL_OWNER_INVALID", assertThrows(NodeRedException.class, () -> controller.acquire(new SerialPortController.Acquire("COM3", ProcessHandle.current().pid()), request)).code());
        assertThrows(NodeRedException.class, () -> controller.release("unknown", request));
    }
    private MockHttpServletRequest request(String path, String token) {
        var request = new MockHttpServletRequest("POST", path); request.setRemoteAddr("127.0.0.1");
        request.addHeader("Origin", "http://127.0.0.1:5173"); request.addHeader("Authorization", "Bearer " + token); return request;
    }
}
