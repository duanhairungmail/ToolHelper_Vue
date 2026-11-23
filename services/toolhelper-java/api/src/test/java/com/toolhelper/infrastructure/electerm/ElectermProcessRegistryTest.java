package com.toolhelper.infrastructure.electerm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toolhelper.application.electerm.ElectermContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ElectermProcessRegistryTest {
    @TempDir Path directory;
    @Test void shutdownDoesNotWaitForTheStartupMonitor() throws Exception {
        ElectermProperties properties = new ElectermProperties(directory.resolve("program"), directory.resolve("data"), 5577, null, null, directory.resolve("inbox"));
        ElectermPackageService packages = mock(ElectermPackageService.class);
        Release release = new Release("demo", "5.3.26", "24.19.0");
        when(packages.current()).thenReturn(release);
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("test did not release startup"); return null; }).when(packages).verifyInstalled(release);
        ElectermProcessRegistry registry = new ElectermProcessRegistry(properties, packages, mock(ElectermHealthService.class), new ObjectMapper());
        try (ExecutorService thread = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Status> starting = thread.submit(registry::start);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try { assertTimeoutPreemptively(Duration.ofSeconds(1), registry::close); }
            finally { resume.countDown(); }
            ExecutionException error = assertThrows(ExecutionException.class, () -> starting.get(3, TimeUnit.SECONDS));
            assertEquals("SERVICE_CLOSING", ((ElectermException) error.getCause()).code());
        } finally { resume.countDown(); registry.close(); }
    }
}
