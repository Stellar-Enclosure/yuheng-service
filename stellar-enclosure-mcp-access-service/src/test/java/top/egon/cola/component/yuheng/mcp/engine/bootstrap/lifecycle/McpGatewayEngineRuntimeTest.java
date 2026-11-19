package top.egon.cola.component.yuheng.mcp.engine.bootstrap.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpLkgRecoveryIT;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class McpGatewayEngineRuntimeTest {
    @TempDir Path directory;

    @Test
    void readinessAndExactHistoryComeFromVerifiedDeploymentFilesUntilTheirExpiry() throws Exception {
        var publication = McpLkgRecoveryIT.publication(directory, true);
        var runtime = publication.runtime();
        assertFalse(runtime.ready()); assertNull(runtime.active());
        runtime.start();
        try {
            assertTrue(runtime.isRunning()); assertTrue(runtime.ready());
            var active = runtime.active();
            assertEquals("2", active.version());
            assertSame(active, runtime.pinned(active.identity().key()));
            var file = publication.mounts().historicalCatalogs().getFirst();
            var old = publication.compiler().loadHistorical(file.file(), file.sha256());
            assertEquals("1", runtime.pinned(old.identity().key()).version());
            assertNull(runtime.pinned("unmounted-history"));
            publication.clock().advance(Duration.ofMinutes(10));
            assertFalse(runtime.ready()); assertNull(runtime.active()); assertNull(runtime.pinned(old.identity().key()));
        } finally {
            runtime.stop();
        }
        assertFalse(runtime.isRunning()); assertFalse(runtime.ready());
    }

    @Test
    void invalidatedBindingsCannotRemainReadyOrRestoreAnEarlierActivation() throws Exception {
        var publication = McpLkgRecoveryIT.publication(directory, false);
        var runtime = publication.runtime(); runtime.start();
        var identity = runtime.active().identity().key();
        try {
            Files.writeString(publication.mounts().bindings().file(), "{}");
            assertThrows(top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingRejectedException.class, publication.bindings()::reload);
            assertFalse(runtime.ready()); assertNull(runtime.active()); assertNull(runtime.pinned(identity));
            runtime.stop();
            assertThrows(top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingRejectedException.class, runtime::start);
            assertFalse(runtime.running());
        } finally { runtime.stop(); }
    }

    @Test
    void stopAndRestartRevalidateTheSameMountWithoutOwningAnHttpListener() throws Exception {
        var publication = McpLkgRecoveryIT.publication(directory, false);
        var runtime = publication.runtime(); runtime.start();
        var original = runtime.active();
        runtime.start(); assertSame(original, runtime.active());
        runtime.stop(); assertNull(runtime.pinned(original.identity().key()));
        runtime.start();
        try {
            assertEquals(original.identity(), runtime.active().identity());
            assertNotSame(original, runtime.active());
            assertTrue(runtime.ready());
        } finally { runtime.stop(); }
    }
}
