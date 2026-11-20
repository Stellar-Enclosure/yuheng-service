package top.egon.cola.component.yuheng.test.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules;
import top.egon.cola.component.yuheng.mcp.engine.rule.service.McpGatewayRuleCompilerStrategy;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingContextVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingPinVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.OperationVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.SelectionVO;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Single release artifact covering every supported MCP capability family.
 */
class McpCompleteReleaseIT {

    @Test
    void mountedCatalogueCompilesApprovedLocalMappingsAndAllNativeCapabilityFamilies(@TempDir Path directory)
            throws Exception {
        var json = new ObjectMapper();
        ObjectNode content;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/mcp/complete-release.json"))) {
            content = (ObjectNode) json.readTree(input);
        }
        var typed = json.treeToValue(content, McpRuleContent.class);
        var clock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
        var validUntil = clock.instant().plusSeconds(300);
        long owner = 1001;
        var projection = json.createObjectNode().put("schemaVersion", McpGatewayRuleCompilerStrategy.SCHEMA_VERSION)
                .put("ownerTenantId", Long.toString(owner)).put("version", "2").put("validUntil", validUntil.toString())
                .put("contentSha256", sha256(DdcConfigCanonicalJson.canonicalBytes(content)));
        projection.set("content", content);
        projection.putObject("serverScopes").putArray("commerce").add("mcp:invoke");

        var pins = projection.putArray("bindingPins");
        var admitted = new LinkedHashMap<SelectionVO, BindingContextVO>();
        var expectedPins = new LinkedHashMap<SelectionVO, BindingPinVO>();
        for (var tool : typed.tools()) {
            if ("LOCAL_OPERATION".equals(tool.sourceType())) {
                int index = admitted.size() + 1;
                var selection = new SelectionVO(owner, tool.serverCode(), tool.toolId());
                var pin = new BindingPinVO("operation-binding-" + index, 1, Integer.toString(100 + index),
                        Integer.toString(200 + index), 1, "a".repeat(64), "301", "b".repeat(64), "c".repeat(64));
                var protocol = GatewayProtocol.valueOf(tool.operationProtocol());
                var binding = mock(BindingContextVO.class);
                when(binding.selection()).thenReturn(selection);
                when(binding.pin()).thenReturn(pin);
                when(binding.tool()).thenReturn(tool);
                when(binding.operation()).thenReturn(new OperationVO(tool.operationId(), "operation-" + index,
                        "d".repeat(64), protocol, protocol == GatewayProtocol.HTTP ? "POST /orders" : "example.Orders/Export",
                        json.createObjectNode(), null));
                when(binding.validUntil()).thenReturn(validUntil);
                admitted.put(selection, binding);
                expectedPins.put(selection, pin);

                var entry = pins.addObject();
                entry.putObject("selection").put("ownerTenantId", Long.toString(owner))
                        .put("serverCode", tool.serverCode()).put("toolId", tool.toolId());
                entry.putObject("pin").put("bindingId", pin.bindingId()).put("bindingVersion", Long.toString(pin.bindingVersion()))
                        .put("resourceId", pin.resourceId()).put("versionId", pin.versionId()).put("versionNo", Long.toString(pin.versionNo()))
                        .put("siDigestSha256", pin.siDigestSha256()).put("publicationId", pin.publicationId())
                        .put("publicationArtifactSha256", pin.publicationArtifactSha256())
                        .put("dependencyClosureDigest", pin.dependencyClosureDigest());
            }
        }
        var bindings = mock(McpPublishedBindingLoader.class);
        when(bindings.fixedActiveBindings()).thenReturn(Map.copyOf(admitted));
        var compiler = new McpGatewayRuleCompilerStrategy(json, bindings, owner, clock);
        Path file = directory.toRealPath().resolve("catalogue.json");
        byte[] reviewed = json.writeValueAsBytes(projection);
        Files.write(file, reviewed);
        var catalogue = compiler.load(file, sha256(reviewed));
        CompiledMcpRules rules = catalogue.mcpRules();

        assertEquals("2", catalogue.version());
        assertEquals(2, catalogue.bindingPins().size());
        assertEquals(expectedPins, catalogue.bindingPins());
        assertEquals(sha256(reviewed), catalogue.fileSha256());

        ((ObjectNode) pins.get(0).path("pin")).put("bindingVersion", "2");
        byte[] foreignPin = json.writeValueAsBytes(projection);
        Files.write(file, foreignPin);
        assertThrows(IllegalArgumentException.class, () -> compiler.load(file, sha256(reviewed)));
        assertThrows(IllegalArgumentException.class, () -> compiler.load(file, sha256(foreignPin)));

        assertAll(
                () -> assertEquals(1, rules.serversByCode().size()),
                () -> assertEquals(4, rules.toolsByQualifiedName().size()),
                () -> assertEquals(2, rules.resourcesByQualifiedName().size()),
                () -> assertEquals(2, rules.templatesByQualifiedName().size()),
                () -> assertEquals(2, rules.promptsByQualifiedName().size()),
                () -> assertEquals(
                        1,
                        rules.taskPoliciesByQualifiedTool().size()
                ),
                () -> assertEquals(1, rules.appsByQualifiedName().size()),
                () -> assertEquals(2, rules.remoteProvidersByCode().size()),
                () -> assertEquals(2, rules.remoteMountsById().size()),
                () -> assertEquals(
                        "operation-http",
                        rules.tool("commerce", "http_query")
                                .orElseThrow()
                                .operationId()
                ),
                () -> assertEquals(
                        "operation-rpc",
                        rules.tool("commerce", "rpc_export")
                                .orElseThrow()
                                .operationId()
                ),
                () -> assertTrue(rules.remoteAvailable(
                        "mount-stable",
                        "TOOL"
                )),
                () -> assertTrue(rules.remoteAvailable(
                        "mount-rc",
                        "COMPLETION"
                )),
                () -> assertEquals(
                        Set.of(
                                McpProtocolDialect.LEGACY_2024_SSE,
                                McpProtocolDialect.STABLE_2025_11_25,
                                McpProtocolDialect.RC_2026_07_28
                        ),
                        rules.server("commerce").orElseThrow().dialects()
                )
        );
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void releaseRemoteContractsExecuteAgainstStableAndRcFixtures()
            throws Exception {
        try (var fixture = RemoteMcpFixtureServer.start();
             var stable = new StableMcpTestClient(fixture.stableEndpoint(), null);
             var rc = new RcMcpTestClient(fixture.rcEndpoint(), null)) {
            assertEquals(
                    "2025-11-25",
                    McpStableConformanceIT.result(stable.initialize())
                            .get("protocolVersion")
            );
            assertEquals(
                    "stable-release",
                    McpStableConformanceIT.object(
                            McpStableConformanceIT.result(stable.call(
                                    "tools/call",
                                    Map.of(
                                            "name", "remote_echo",
                                            "arguments", Map.of(
                                                    "value",
                                                    "stable-release"
                                            )
                                    )
                            )).get("structuredContent")
                    ).get("value")
            );
            assertEquals(
                    "2026-07-28",
                    McpStableConformanceIT.result(rc.call(
                            "discover",
                            Map.of()
                    )).get("protocolVersion")
            );
            assertEquals(
                    "remote_dashboard",
                    ((Map<?, ?>) ((java.util.List<?>)
                            McpStableConformanceIT.result(rc.call(
                                    "apps/list",
                                    Map.of()
                            )).get("apps")).getFirst()).get("name")
            );
        }
    }
}
