package top.egon.cola.component.yuheng.mcp.engine.rule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingContractTest;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingContractTest.StructuralFixtureVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingContextVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingPinVO;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class McpGatewayRuleCompilerStrategyTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void rulesLoadOnlyFromThePinnedRoleLocalProjection() throws Exception {
        assertNotNull(McpGatewayRuleCompilerStrategy.class.getMethod("load", Path.class, String.class));
        assertNotNull(McpGatewayRuleCompilerStrategy.class.getMethod("loadHistorical", Path.class, String.class));
    }

    @Test
    void localCatalogueBindsExactOwnerToolOperationAndScopesWithoutGenericGatewayViews() throws Exception {
        var fixture = fixture(false); var envelope = envelope(fixture, fixture.loader().fixedActiveBindings().get(fixture.selection()));
        var compiled = load(fixture, envelope, false);
        assertTrue(compiled.mcpRules().tool("orders", "lookup").isPresent());
        assertEquals(Set.of("mcp:invoke"), compiled.serverScopes().get("orders"));
        assertEquals(1, compiled.bindingPins().size());
        assertEquals("1", compiled.releaseId());
        assertEquals(7, compiled.identity().ownerTenantId());
        assertEquals(compiled.fileSha256(), compiled.ruleChecksum());
        assertEquals("7:1:" + compiled.contentSha256() + ":" + compiled.fileSha256(), compiled.identity().key());
        assertThrows(UnsupportedOperationException.class, () -> compiled.serverScopes().get("orders").clear());
        assertThrows(UnsupportedOperationException.class, () -> compiled.bindingPins().clear());
        assertFalse(Arrays.stream(McpGatewayCompiledRulesDTO.class.getRecordComponents())
                .anyMatch(component -> Set.of("snapshot", "providerServices", "providerPolicies", "trafficPolicies").contains(component.getName())));
        assertFalse(compiled.expiredAt(clock.instant())); assertTrue(compiled.expiredAt(compiled.validUntil()));
    }

    @Test
    void explicitRetainedV1CatalogueMatchesHistoricalPinsRatherThanSubstitutingActiveV2() throws Exception {
        var fixture = fixture(true);
        var old = fixture.loader().fixedPinnedBindings().stream().filter(value -> value.bindingVersion() == 1).findFirst().orElseThrow();
        var current = fixture.loader().fixedActiveBindings().get(fixture.selection());
        ObjectNode oldCatalog = envelope(fixture, old), newCatalog = envelope(fixture, current);
        newCatalog.put("version", "2");
        assertThrows(IllegalArgumentException.class, () -> load(fixture, oldCatalog, false));
        assertEquals(old.pin(), load(fixture, oldCatalog, true).bindingPins().get(fixture.selection()));
        assertEquals(current.pin(), load(fixture, newCatalog, false).bindingPins().get(fixture.selection()));
        ((ObjectNode) oldCatalog.path("bindingPins").get(0).path("pin")).put("bindingVersion", "3");
        assertThrows(IllegalArgumentException.class, () -> load(fixture, oldCatalog, true));
    }

    @Test
    void everyLocalToolRequiresExactlyOnePinWithNoExtraMissingDuplicateOrCrossOwnerEntries() throws Exception {
        var fixture = fixture(false); ObjectNode envelope = envelope(fixture, fixture.loader().fixedActiveBindings().get(fixture.selection()));
        ObjectNode missing = envelope.deepCopy(); missing.putArray("bindingPins");
        assertThrows(IllegalArgumentException.class, () -> load(fixture, missing, false));
        ObjectNode duplicate = envelope.deepCopy(); ((ArrayNode) duplicate.path("bindingPins")).add(duplicate.path("bindingPins").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> load(fixture, duplicate, false));
        ObjectNode owner = envelope.deepCopy(); owner.put("ownerTenantId", "8");
        assertThrows(IllegalArgumentException.class, () -> load(fixture, owner, false));
        ObjectNode selection = envelope.deepCopy(); ((ObjectNode) selection.path("bindingPins").get(0).path("selection")).put("ownerTenantId", "8");
        assertThrows(IllegalArgumentException.class, () -> load(fixture, selection, false));
        ObjectNode extra = envelope.deepCopy(); ((ObjectNode) extra.path("content")).putArray("tools"); resign(extra);
        assertThrows(IllegalArgumentException.class, () -> load(fixture, extra, false));
    }

    @Test
    void projectedToolCannotDropPermissionsLowerRiskChangeSchemaOrClaimMoreCapabilities() throws Exception {
        var fixture = fixture(false); ObjectNode envelope = envelope(fixture, fixture.loader().fixedActiveBindings().get(fixture.selection()));
        ObjectNode permissions = envelope.deepCopy(); tool(permissions).putArray("requiredPermissions"); resign(permissions);
        assertThrows(IllegalArgumentException.class, () -> load(fixture, permissions, false));
        ObjectNode schema = envelope.deepCopy(); tool(schema).put("inputSchema", "{}"); resign(schema);
        assertThrows(IllegalArgumentException.class, () -> load(fixture, schema, false));
        ObjectNode operation = envelope.deepCopy(); tool(operation).put("operationId", "99"); resign(operation);
        assertThrows(IllegalArgumentException.class, () -> load(fixture, operation, false));
        ObjectNode capabilities = envelope.deepCopy(); tool(capabilities).putObject("annotations").put("destructiveHint", "true"); resign(capabilities);
        assertThrows(IllegalArgumentException.class, () -> load(fixture, capabilities, false));
        ObjectNode tighter = envelope.deepCopy(); tool(tighter).put("riskLevel", "HIGH"); tool(tighter).withArray("requiredPermissions").add("orders:approved"); resign(tighter);
        assertEquals("HIGH", load(fixture, tighter, false).mcpRules().tool("orders", "lookup").orElseThrow().riskLevel());
    }

    @Test
    void scopesAreExactNonemptyAndScopeOnlyChangesChangeTheSessionIdentity() throws Exception {
        var fixture = fixture(false); ObjectNode first = envelope(fixture, fixture.loader().fixedActiveBindings().get(fixture.selection()));
        var before = load(fixture, first, false);
        ObjectNode changed = first.deepCopy(); ((ObjectNode) changed.path("serverScopes")).putArray("orders").add("mcp:admin");
        var after = load(fixture, changed, false);
        assertEquals(before.contentSha256(), after.contentSha256()); assertNotEquals(before.identity(), after.identity());
        ObjectNode empty = first.deepCopy(); ((ObjectNode) empty.path("serverScopes")).putArray("orders");
        assertThrows(IllegalArgumentException.class, () -> load(fixture, empty, false));
        ObjectNode extra = first.deepCopy(); ((ObjectNode) extra.path("serverScopes")).putArray("other").add("mcp:invoke");
        assertThrows(IllegalArgumentException.class, () -> load(fixture, extra, false));
    }

    @Test
    void preservesRemoteToolsStaticResourcesPromptsAndTaskMetadataUnderThePinnedProjection() throws Exception {
        var fixture = fixture(false); ObjectNode envelope = envelope(fixture, fixture.loader().fixedActiveBindings().get(fixture.selection()));
        McpRuleContent local = json.treeToValue(envelope.path("content"), McpRuleContent.class);
        var remote = new McpRuntimeTool("remote-tool", "orders", "remote.lookup", "Remote", "REMOTE_MCP", null, null,
                "mount-1", "{}", "{}", Map.of(), Set.of("remote:read"), "LOW", true, true);
        var provider = new McpRuntimeRemoteProvider("provider-1", "remote", "Remote", McpProtocolDialect.STABLE_2025_11_25,
                "STREAMABLE_HTTP", "https://remote.example.test/mcp", "remote-credential", null, "a".repeat(64), true);
        var mount = new McpRuntimeRemoteMount("mount-1", "orders", "remote", "remote", Set.of("TOOL"), Map.of(), "REJECT",
                Set.of("remote:read"), "a".repeat(64), true);
        var resource = new McpRuntimeResource("resource-1", "orders", "help", "mcp://orders/help", "Help", "text/plain", "STATIC",
                null, null, Map.of("text", "Approved metadata"), Set.of(), 1024, true);
        var prompt = new McpRuntimePrompt("prompt-1", "orders", "help-prompt", "Help", "STATIC", "Read {{name}}", null, null, List.of("name"), Set.of(), true);
        var task = new McpRuntimeTaskPolicy("task-1", "orders", "lookup", true, true, 30, 300, 1, true);
        var app = new McpRuntimeApp("app-1", "orders", "order-view", "order-view", "1", "mcp://orders/apps/view", "artifact-1",
                "approved-artifact", "b".repeat(64), 100, "text/html", "default-src 'none'", Set.of("orders:read"),
                Set.of("https://app.example.test"), Set.of("lookup"), true);
        var merged = new McpRuleContent(local.servers(), List.of(local.tools().getFirst(), remote), List.of(resource), List.of(), List.of(prompt),
                List.of(task), List.of(app), List.of(provider), List.of(mount));
        envelope.set("content", json.valueToTree(merged)); resign(envelope);
        var result = load(fixture, envelope, false);
        assertEquals(2, result.mcpRules().toolsByQualifiedName().size()); assertEquals(1, result.mcpRules().resourcesByQualifiedName().size());
        assertEquals(1, result.mcpRules().promptsByQualifiedName().size()); assertEquals(1, result.mcpRules().taskPoliciesByQualifiedTool().size());
        assertEquals(1, result.mcpRules().appsByQualifiedName().size());
        assertEquals("https://remote.example.test/mcp", result.mcpRules().remoteProvidersByCode().get("remote").endpointReference());
        ((ObjectNode) envelope.path("content").path("remoteProviders").get(0)).put("endpointReference", "http://169.254.169.254/secret"); resign(envelope);
        assertThrows(IllegalArgumentException.class, () -> load(fixture, envelope, false));
    }

    @Test
    void fileShaChecksumOldGatewayEnvelopeMalformedJsonAndUnknownFieldsAreRejected() throws Exception {
        var fixture = fixture(false); ObjectNode envelope = envelope(fixture, fixture.loader().fixedActiveBindings().get(fixture.selection()));
        var compiler = compiler(fixture); Path file = write(envelope);
        assertThrows(IllegalArgumentException.class, () -> compiler.load(file, "0".repeat(64)));
        ObjectNode digest = envelope.deepCopy(); digest.put("contentSha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> load(fixture, digest, false));
        ObjectNode old = envelope.deepCopy(); old.put("schemaVersion", "v1").put("releaseId", "legacy");
        assertThrows(IllegalArgumentException.class, () -> load(fixture, old, false));
        ObjectNode unknown = envelope.deepCopy(); ((ObjectNode) unknown.path("content").path("tools").get(0)).put("upstreamUrl", "https://attacker.test"); resign(unknown);
        assertThrows(IllegalArgumentException.class, () -> load(fixture, unknown, false));
        Path duplicate = directory.toRealPath().resolve("duplicate.json"); byte[] bytes = "{\"schemaVersion\":1,\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8); Files.write(duplicate, bytes);
        assertThrows(IllegalArgumentException.class, () -> compiler.load(duplicate, hash(bytes)));
    }

    @Test
    void expiryAndSymlinkCannotExtendOrReplaceThePinnedLocalProjection() throws Exception {
        var fixture = fixture(false); ObjectNode envelope = envelope(fixture, fixture.loader().fixedActiveBindings().get(fixture.selection()));
        envelope.put("validUntil", clock.instant().toString());
        assertThrows(IllegalArgumentException.class, () -> load(fixture, envelope, false));
        envelope.put("validUntil", clock.instant().plusSeconds(600).toString()); Path file = write(envelope);
        Path link = directory.toRealPath().resolve("link.json"); Files.createSymbolicLink(link, file);
        assertThrows(IllegalArgumentException.class, () -> compiler(fixture).load(link, hash(Files.readAllBytes(file))));
    }

    @Test
    void standaloneMcpContentDigestUsesTheDeclaredJcsShape() throws Exception {
        // Static nine-field contract vector, independent of the production mapper's insertion order.
        String wire = "{\"apps\":[],\"prompts\":[],\"remoteMounts\":[],\"remoteProviders\":[],\"resourceTemplates\":[],\"resources\":[],\"servers\":[],\"taskPolicies\":[],\"tools\":[]}";
        JsonNode content = DdcConfigCanonicalJson.parse(wire.getBytes(StandardCharsets.UTF_8));
        assertEquals("b9046fa4b46efc153182c6849214e042f75de05033b09fd2d9e17835a71e400f", hash(DdcConfigCanonicalJson.canonicalBytes(content)));
        var fixture = fixture(false); ObjectNode envelope = envelope(fixture, fixture.loader().fixedActiveBindings().get(fixture.selection()));
        envelope.set("content", content); envelope.putObject("serverScopes"); envelope.putArray("bindingPins"); resign(envelope);
        assertEquals("b9046fa4b46efc153182c6849214e042f75de05033b09fd2d9e17835a71e400f", load(fixture, envelope, false).contentSha256());
    }

    private StructuralFixtureVO fixture(boolean history) throws Exception { return McpPublishedBindingContractTest.structuralFixture(directory, history); }
    private McpGatewayRuleCompilerStrategy compiler(StructuralFixtureVO fixture) { return new McpGatewayRuleCompilerStrategy(json, fixture.loader(), 7, clock); }
    private McpGatewayCompiledRulesDTO load(StructuralFixtureVO fixture, ObjectNode envelope, boolean history) throws Exception {
        Path file = write(envelope); String sha = hash(Files.readAllBytes(file));
        return history ? compiler(fixture).loadHistorical(file, sha) : compiler(fixture).load(file, sha);
    }
    private Path write(ObjectNode value) throws Exception { Path file = directory.toRealPath().resolve(UUID.randomUUID() + ".json"); Files.writeString(file, value.toString()); return file; }
    private ObjectNode envelope(StructuralFixtureVO fixture, BindingContextVO binding) throws Exception {
        McpRuntimeTool tool = binding.tool();
        var server = new McpRuntimeServer("server-1", "orders", "Orders", "Order capabilities", "Use approved operations",
                Set.of(McpProtocolDialect.STABLE_2025_11_25), "https://mcp.example.test/orders", 30, true);
        McpRuleContent content = new McpRuleContent(List.of(server), List.of(tool), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ObjectNode out = json.createObjectNode().put("schemaVersion", McpGatewayRuleCompilerStrategy.SCHEMA_VERSION).put("ownerTenantId", "7")
                .put("version", "1").put("validUntil", clock.instant().plusSeconds(600).toString());
        out.set("content", json.valueToTree(content)); out.putObject("serverScopes").putArray("orders").add("mcp:invoke");
        ObjectNode pin = out.putArray("bindingPins").addObject();
        pin.putObject("selection").put("ownerTenantId", "7").put("serverCode", "orders").put("toolId", tool.toolId());
        BindingPinVO b = binding.pin(); pin.putObject("pin").put("bindingId", b.bindingId()).put("bindingVersion", Long.toString(b.bindingVersion()))
                .put("resourceId", b.resourceId()).put("versionId", b.versionId()).put("versionNo", Long.toString(b.versionNo())).put("siDigestSha256", b.siDigestSha256())
                .put("publicationId", b.publicationId()).put("publicationArtifactSha256", b.publicationArtifactSha256()).put("dependencyClosureDigest", b.dependencyClosureDigest());
        resign(out); return out;
    }
    private static ObjectNode tool(ObjectNode root) { return (ObjectNode) root.path("content").path("tools").get(0); }
    private static void resign(ObjectNode root) { root.put("contentSha256", hash(DdcConfigCanonicalJson.canonicalBytes(root.path("content")))); }
    private static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception impossible) { throw new AssertionError(impossible); } }
}
