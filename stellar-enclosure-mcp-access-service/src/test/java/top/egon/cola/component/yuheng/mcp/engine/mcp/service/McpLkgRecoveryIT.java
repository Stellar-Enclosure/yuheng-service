package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimePrompt;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeResource;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.lifecycle.McpGatewayEngineRuntime;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPublicationMountProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingContextVO;
import top.egon.cola.component.yuheng.mcp.engine.rule.service.McpGatewayRuleCompilerStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Restart recovery is from deployment-pinned files; the removed writable gateway LKG is not a fallback. */
public class McpLkgRecoveryIT {
    @TempDir Path dataDirectory;

    @Test
    void restartRevalidatesTheSamePinnedCatalogueAndExplicitHistoryWithoutAConfigurationCentre() throws Exception {
        TestPublicationVO fixture = publication(dataDirectory, true);
        var original = fixture.runtime(); original.start();
        String activeKey = original.active().identity().key();
        String historyKey = fixture.compiler().loadHistorical(fixture.mounts().historicalCatalogs().getFirst().file(),
                fixture.mounts().historicalCatalogs().getFirst().sha256()).identity().key();
        original.stop(); assertFalse(original.ready()); assertNull(original.active());
        var restarted = fixture.coldRuntime(); restarted.start();
        try {
            assertEquals(activeKey, restarted.active().identity().key());
            assertEquals("2", restarted.active().version());
            assertEquals("1", restarted.pinned(historyKey).version());
            assertNull(restarted.pinned("unmounted-version"));
        } finally { restarted.stop(); }
    }

    @Test
    void corruptPinnedFilesCannotRestoreAnOldUnverifiedLkgAndExactSourceRestorationAllowsRetry() throws Exception {
        TestPublicationVO fixture = publication(dataDirectory, false);
        var running = fixture.runtime(); running.start(); String before = running.active().identity().key(); running.stop();
        Path active = fixture.mounts().activeCatalog().file(); byte[] trusted = Files.readAllBytes(active);
        Files.writeString(dataDirectory.resolve("legacy-lkg.json"), "{\"releaseId\":\"old\",\"trusted\":true}");
        Files.writeString(active, "{}");
        var restarted = fixture.coldRuntime();
        assertThrows(IllegalArgumentException.class, restarted::start);
        assertFalse(restarted.running()); assertFalse(restarted.ready()); assertNull(restarted.active());
        Files.write(active, trusted); restarted.start();
        try { assertEquals(before, restarted.active().identity().key()); }
        finally { restarted.stop(); }
    }

    @Test
    void coldRecoveryDoesNotPretendTheSiAuthorityOrCredentialsWereRestoredFromDisk() throws Exception {
        TestPublicationVO fixture = publication(dataDirectory, false);
        McpPublishedBindingLoader cold = fixture.coldBindings();
        cold.reload(); assertTrue(cold.isReady());
        var selected = cold.fixedActiveBindings().values().iterator().next();
        var rejected = assertThrows(McpPublishedBindingLoader.BindingRejectedException.class,
                () -> cold.requirePinned(selected.selection(), selected.pin()));
        assertEquals("MCP_SI_AUTHORITY_UNAVAILABLE", rejected.errorCode());
    }

    /** Shared regression fixture uses the real bound-file loader and role-local compiler. No production capability is fabricated. */
    public static TestPublicationVO publication(Path directory, boolean withHistory) throws Exception {
        ObjectMapper json = new ObjectMapper(); TestClock clock = new TestClock();
        var bound = McpPublishedBindingContractTest.structuralFixture(directory, withHistory);
        Path bindingFile;
        try (var paths = Files.list(directory)) { bindingFile = paths.filter(Files::isRegularFile).findFirst().orElseThrow(); }
        var bindingPin = pin(bindingFile);
        var compiler = new McpGatewayRuleCompilerStrategy(json, bound.loader(), 7, clock);
        BindingContextVO active = bound.loader().fixedActiveBindings().get(bound.selection());
        Path activeFile = directory.resolve("catalog-active.json"); Files.writeString(activeFile, catalog(json, active, withHistory ? "2" : "1").toString());
        List<McpPublicationMountProperties.PinnedFileVO> historical = new ArrayList<>();
        if (withHistory) {
            BindingContextVO old = bound.loader().fixedPinnedBindings().stream().filter(value -> value.bindingVersion() == 1).findFirst().orElseThrow();
            Path oldFile = directory.resolve("catalog-history.json"); Files.writeString(oldFile, catalog(json, old, "1").toString()); historical.add(pin(oldFile));
        }
        var mounts = new McpPublicationMountProperties(bindingPin, pin(activeFile), historical, "mcp-access");
        return new TestPublicationVO(bound.loader(), compiler, mounts, json, clock);
    }

    public static ObjectNode catalog(ObjectMapper json, BindingContextVO binding, String version) {
        var server = new McpRuntimeServer("server-" + version, "orders", "Orders " + version, "Published orders", "Reviewed operations only",
                Set.of(McpProtocolDialect.RC_2026_07_28), "https://mcp.example.test/orders/v" + version, 0, true);
        var resource = new McpRuntimeResource("resource-" + version, "orders", "report", "egon://orders/report", "Order report", "text/plain",
                "LOCAL_OPERATION", binding.operation().operationId(), null, Map.of(), Set.of("orders:read"), 4096, true);
        var prompt = new McpRuntimePrompt("prompt-" + version, "orders", "review", "Order review", "LOCAL_OPERATION", null,
                binding.operation().operationId(), null, List.of(), Set.of("orders:read"), true);
        var content = new McpRuleContent(List.of(server), List.of(binding.tool()), List.of(resource), List.of(), List.of(prompt),
                List.of(), List.of(), List.of(), List.of());
        ObjectNode root = json.createObjectNode().put("schemaVersion", McpGatewayRuleCompilerStrategy.SCHEMA_VERSION).put("ownerTenantId", "7")
                .put("version", version).put("validUntil", "2026-11-19T12:10:00Z");
        root.set("content", json.valueToTree(content)); root.putObject("serverScopes").putArray("orders").add("mcp:invoke");
        ObjectNode item = root.putArray("bindingPins").addObject();
        item.putObject("selection").put("ownerTenantId", "7").put("serverCode", "orders").put("toolId", binding.tool().toolId());
        var b = binding.pin(); item.putObject("pin").put("bindingId", b.bindingId()).put("bindingVersion", Long.toString(b.bindingVersion()))
                .put("resourceId", b.resourceId()).put("versionId", b.versionId()).put("versionNo", Long.toString(b.versionNo()))
                .put("siDigestSha256", b.siDigestSha256()).put("publicationId", b.publicationId())
                .put("publicationArtifactSha256", b.publicationArtifactSha256()).put("dependencyClosureDigest", b.dependencyClosureDigest());
        resign(root); return root;
    }
    public static void resign(ObjectNode root) { root.put("contentSha256", hash(DdcConfigCanonicalJson.canonicalBytes(root.path("content")))); }
    public static McpPublicationMountProperties.PinnedFileVO pin(Path file) throws Exception {
        return new McpPublicationMountProperties.PinnedFileVO(file.toRealPath(), hash(Files.readAllBytes(file)));
    }
    public static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception impossible) { throw new AssertionError(impossible); } }
    public record TestPublicationVO(McpPublishedBindingLoader bindings, McpGatewayRuleCompilerStrategy compiler,
            McpPublicationMountProperties mounts, ObjectMapper json, TestClock clock) {
        public McpGatewayEngineRuntime runtime() { return new McpGatewayEngineRuntime(bindings, compiler, mounts, clock); }
        public McpPublishedBindingLoader coldBindings() {
            // Cold fixture authority deliberately denies every read. Structural startup must not cache an authorization grant.
            DdcSiCatalogClient denied = (DdcSiCatalogClient) java.lang.reflect.Proxy.newProxyInstance(DdcSiCatalogClient.class.getClassLoader(),
                    new Class<?>[]{DdcSiCatalogClient.class}, (proxy, method, arguments) -> { throw new IllegalStateException("synthetic authority unavailable"); });
            return new McpPublishedBindingLoader(mounts.bindings().file(), mounts.bindings().sha256(), denied, mounts.callerAppCode(), clock);
        }
        public McpGatewayEngineRuntime coldRuntime() {
            var cold = coldBindings(); return new McpGatewayEngineRuntime(cold, new McpGatewayRuleCompilerStrategy(json, cold, 7, clock), mounts, clock);
        }
    }
    public static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-19T12:00:00Z");
        public void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
