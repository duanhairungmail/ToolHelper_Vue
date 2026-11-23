package com.toolhelper.api.electerm;

import com.toolhelper.application.electerm.ElectermContracts.*;
import com.toolhelper.application.electerm.ElectermLifecycleService;
import com.toolhelper.infrastructure.electerm.ElectermPackageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ElectermControllerTest {
    @Test void installSupportsEmptyBodyAndMultipartWithoutMappingAmbiguity() throws Exception {
        ElectermLifecycleService service = mock(ElectermLifecycleService.class);
        ElectermPackageService packages = mock(ElectermPackageService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ElectermController(service, packages)).setControllerAdvice(new ElectermExceptionHandler()).build();
        mvc.perform(post("/api/integrations/electerm/install")).andExpect(status().isOk());
        verify(service).install(null);
        when(packages.importOffline(any())).thenReturn("upload-test.zip");
        mvc.perform(multipart("/api/integrations/electerm/install").file(new MockMultipartFile("package", "local.zip", "application/zip", new byte[]{1}))).andExpect(status().isOk());
        verify(service).install(new InstallRequest("upload-test.zip"));
        verify(packages).discardUpload("upload-test.zip");
    }
    @Test void portConflictReturnsStableCodeAndSafeMessage() throws Exception {
        ElectermLifecycleService service = mock(ElectermLifecycleService.class);
        when(service.start()).thenThrow(new ElectermException("PORT_IN_USE", "端口 5577 被占用"));
        var mvc = MockMvcBuilders.standaloneSetup(new ElectermController(service, mock(ElectermPackageService.class))).setControllerAdvice(new ElectermExceptionHandler()).build();
        mvc.perform(post("/api/integrations/electerm/start")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PORT_IN_USE"));
    }
}
