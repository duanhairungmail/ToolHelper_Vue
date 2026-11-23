package com.toolhelper.api.nodered;

import com.toolhelper.application.nodered.*;
import com.toolhelper.application.nodered.NodeRedContracts.*;
import com.toolhelper.infrastructure.nodered.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeRedControllerTest {
    @Test void cancelledMultipartDoesNotPersistUploadOrInstallNodes() throws Exception {
        var lifecycle = mock(NodeRedLifecycleService.class); var packages = mock(NodeRedPackageService.class); var nodes = mock(NodeApprovalService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new NodeRedController(lifecycle, packages, nodes, mock(NodeRedProcessRegistry.class))).setControllerAdvice(new NodeRedExceptionHandler()).build();
        for (String part : new String[]{"install", "nodes/review"}) mvc.perform(multipart("/api/integrations/nodered/" + part).file(new MockMultipartFile("package", "x.zip", "application/zip", new byte[]{1})).param("confirmed", "false"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));
        verifyNoInteractions(lifecycle, packages, nodes);
    }
    @Test void jsonAndMultipartMappingsPassExplicitConfirmationAndDiscardUpload() throws Exception {
        var lifecycle = mock(NodeRedLifecycleService.class); var packages = mock(NodeRedPackageService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new NodeRedController(lifecycle, packages, mock(NodeApprovalService.class), mock(NodeRedProcessRegistry.class))).setControllerAdvice(new NodeRedExceptionHandler()).build();
        mvc.perform(post("/api/integrations/nodered/updates").contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isOk());
        verify(lifecycle).install(new InstallRequest(null, true));
        when(packages.importOffline(any())).thenReturn("upload-test.zip");
        mvc.perform(multipart("/api/integrations/nodered/install").file(new MockMultipartFile("package", new byte[]{1})).param("confirmed", "true")).andExpect(status().isOk());
        verify(lifecycle).install(new InstallRequest("upload-test.zip", true)); verify(packages).discardUpload("upload-test.zip");
    }
}
