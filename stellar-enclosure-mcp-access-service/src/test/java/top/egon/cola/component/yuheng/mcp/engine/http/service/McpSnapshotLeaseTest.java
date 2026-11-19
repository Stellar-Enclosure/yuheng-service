package top.egon.cola.component.yuheng.mcp.engine.http.service;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class McpSnapshotLeaseTest {

    @Test
    void expiredHistoricalSessionIdentityNeverResolvesToAvailableActiveCatalog(@TempDir Path directory) throws Exception {
        var publication = McpLkgRecoveryIT.publication(directory, true);
        var original = publication.mounts();
        Path historicalFile = original.historicalCatalogs().getFirst().file();
        ObjectNode historical = (ObjectNode) publication.json().readTree(historicalFile.toFile());
        historical.put("validUntil", publication.clock().instant().plusSeconds(1).toString());
        Files.writeString(historicalFile, publication.json().writeValueAsString(historical));
        var historicalPin = McpLkgRecoveryIT.pin(historicalFile);
        var mounts = new McpPublicationMountProperties(original.bindings(), original.activeCatalog(),
                List.of(historicalPin), original.callerAppCode());
        var runtime = new McpGatewayEngineRuntime(publication.bindings(), publication.compiler(), mounts, publication.clock());
        runtime.start();
        try {
            var active = runtime.active();
            assertNotNull(active);
            assertEquals("2", active.version());
            String retained = publication.compiler().loadHistorical(historicalFile, historicalPin.sha256()).identity().key();
            var leased = runtime.pinned(retained);
            assertNotNull(leased);
            assertEquals("1", leased.version());
            publication.clock().advance(Duration.ofSeconds(1));
            assertNull(runtime.pinned(retained));
            assertSame(active, runtime.pinned(active.identity().key()));
            assertSame(active, runtime.active());
        } finally {
            runtime.stop();
        }
    }
}
