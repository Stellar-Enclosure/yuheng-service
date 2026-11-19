package top.egon.cola.component.yuheng.mcp.engine.rule.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.lifecycle.McpGatewayEngineRuntime;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPublicationMountProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpLkgRecoveryIT;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatewayMcpActivationTest {
    @TempDir Path dataDirectory;

    @Test
    void invalidHistoricalCataloguePreventsPartialActivePublicationAndRetryPublishesTheCompleteSet() throws Exception {
        var fixture = McpLkgRecoveryIT.publication(dataDirectory, true);
        Path historical = fixture.mounts().historicalCatalogs().getFirst().file(); byte[] original = Files.readAllBytes(historical);
        ObjectNode bad = (ObjectNode) fixture.json().readTree(original);
        ((ObjectNode) bad.path("content").path("servers").get(0)).put("serverCode", "missing-server"); McpLkgRecoveryIT.resign(bad);
        Files.writeString(historical, bad.toString());
        var changed = new McpPublicationMountProperties(fixture.mounts().bindings(), fixture.mounts().activeCatalog(),
                List.of(McpLkgRecoveryIT.pin(historical)), fixture.mounts().callerAppCode());
        var runtime = new McpGatewayEngineRuntime(fixture.bindings(), fixture.compiler(), changed, fixture.clock());
        assertThrows(IllegalArgumentException.class, runtime::start);
        assertFalse(runtime.isRunning()); assertFalse(runtime.ready()); assertNull(runtime.active());
        Files.write(historical, original);
        var recovered = fixture.runtime(); recovered.start();
        try {
            assertTrue(recovered.ready()); assertEquals("2", recovered.active().version());
            var oldFile = fixture.mounts().historicalCatalogs().getFirst();
            var old = fixture.compiler().loadHistorical(oldFile.file(), oldFile.sha256());
            assertEquals(old.identity(), recovered.pinned(old.identity().key()).identity());
        } finally { recovered.stop(); }
    }

    @Test
    void duplicateCompoundIdentitiesAreRejectedAndStopRemovesAllPublishedPointers() throws Exception {
        var fixture = McpLkgRecoveryIT.publication(dataDirectory, true);
        var old = fixture.mounts().historicalCatalogs().getFirst();
        var duplicate = new McpPublicationMountProperties(fixture.mounts().bindings(), fixture.mounts().activeCatalog(),
                List.of(old, old), fixture.mounts().callerAppCode());
        var runtime = new McpGatewayEngineRuntime(fixture.bindings(), fixture.compiler(), duplicate, fixture.clock());
        assertThrows(IllegalArgumentException.class, runtime::start); assertNull(runtime.active());
        runtime = fixture.runtime(); runtime.start(); String active = runtime.active().identity().key();
        runtime.stop(); assertNull(runtime.active()); assertNull(runtime.pinned(active)); assertFalse(runtime.ready());
    }

    @Test
    void aLoadedCatalogueExpiresWithoutAcceptingNewCallsOrResumingItsPinnedIdentity() throws Exception {
        var fixture = McpLkgRecoveryIT.publication(dataDirectory, false); var runtime = fixture.runtime(); runtime.start();
        try {
            String identity = runtime.active().identity().key(); fixture.clock().advance(Duration.ofMinutes(11));
            assertTrue(runtime.isRunning()); assertFalse(runtime.ready()); assertNull(runtime.active()); assertNull(runtime.pinned(identity));
        } finally { runtime.stop(); }
    }
}
