package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.DescriptorProtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;
import top.egon.cola.component.tianshu.model.si.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class McpPublishedBindingContractTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    private final FakeSi si = new FakeSi();

    @Test
    void publishedBindingsHaveAnIndependentFixedExecutionBoundary() throws Exception {
        assertNotNull(Class.forName("top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader"));
    }

    @Test
    void chronologicalSeedThenSiThenBoundPublicationDoesNotNeedAnImpossibleSelfReference() throws Exception {
        Fixture fixture = fixture("orders", false);
        var loader = loader(fixture.binding());
        assertTrue(loader.isReady());
        assertEquals(0, si.reads);
        var context = loader.require(fixture.selection());
        assertEquals("yuheng-mcp-mapping-seed-v1", context.sourceMapping().schemaVersion());
        assertNotEquals(context.publication().artifactSha256(), context.sourceMapping().artifactSha256());
        assertEquals("http://orders-api.apps-prod.svc.cluster.local:8080", context.transport().httpOrigin().toString());
        assertEquals("https://identity.example.test/resources/orders", context.transport().credential().audience().toString());
        assertEquals("business-orders", context.transport().credential().registrationId());
        assertEquals(1, si.reads);
        assertEquals("mcp-access", si.caller);
        assertEquals("owner-app", si.owner);
        assertEquals(1L, si.version);
        assertEquals(context.pin(), loader.requirePinned(fixture.selection(), context.pin()).pin());
        ((ObjectNode) context.operation().definition()).put("summary", "mutated");
        assertEquals("lookup", context.operation().definition().path("summary").textValue());
        assertThrows(UnsupportedOperationException.class, () -> context.transport().credential().scopes().add("other"));
    }

    @Test
    void aRevokedSelectionDoesNotFlushAHealthyBindingOrPretendStructuralReadinessIsLiveAuthorization() throws Exception {
        Fixture a = fixture("orders-a", false), b = fixture("orders-b", false);
        var loader = loader(a.binding(), b.binding());
        si.denied.add("orders-a");
        rejected("MCP_SI_AUTHORITY_UNAVAILABLE", () -> loader.require(a.selection()));
        assertTrue(loader.isReady());
        assertEquals(b.selection(), loader.require(b.selection()).selection());
        si.denied.remove("orders-a");
        assertEquals(a.selection(), loader.require(a.selection()).selection());
    }

    @Test
    void disabledToolAIsDeniedWhileBRemainsUsable() throws Exception {
        Fixture a = fixture("orders-a", false), b = fixture("orders-b", false);
        ObjectNode tool = (ObjectNode) a.binding().path("publicationEvidence").path("mappings").get(0).path("tool");
        tool.put("enabled", false); resignPublication((ObjectNode) a.binding().path("publicationEvidence"));
        var loader = loader(a.binding(), b.binding());
        rejected("MCP_TOOL_DISABLED", () -> loader.require(a.selection()));
        assertEquals(b.selection(), loader.require(b.selection()).selection());
    }

    @Test
    void exactHistoryMissingNeverFallsBackToTheActiveVersion() throws Exception {
        Fixture fixture = fixture("orders", false);
        var loader = loader(fixture.binding());
        si.missing.add("orders");
        rejected("MCP_SI_UNAVAILABLE", () -> loader.require(fixture.selection()));
        assertEquals(1L, si.version);
        assertEquals(1, si.reads);
        si.missing.clear();
        var pin = loader.require(fixture.selection()).pin();
        var changed = new McpPublishedBindingLoader.BindingPinVO(pin.bindingId(), 2, pin.resourceId(), pin.versionId(),
                pin.versionNo(), pin.siDigestSha256(), pin.publicationId(), pin.publicationArtifactSha256(), pin.dependencyClosureDigest());
        int before = si.reads;
        rejected("MCP_BINDING_PIN_UNAVAILABLE", () -> loader.requirePinned(fixture.selection(), changed));
        assertEquals(before, si.reads);
    }

    @Test
    void crossOwnerNamespaceVersionAndClosureResponsesAreRejected() throws Exception {
        Fixture fixture = fixture("orders", false);
        var loader = loader(fixture.binding());
        DdcSiResourceDescriptor original = si.values.get("orders");
        original.setOwnerAppCode("different-owner");
        rejected("MCP_SI_MISMATCH", () -> loader.require(fixture.selection()));
        original.setOwnerAppCode("owner-app").setNamespaceCode("other");
        rejected("MCP_SI_MISMATCH", () -> loader.require(fixture.selection()));
        original.setNamespaceCode("default").setResolvedVersionNo(2L);
        rejected("MCP_SI_MISMATCH", () -> loader.require(fixture.selection()));
        original.setResolvedVersionNo(1L).setStatus(DdcSiLifecycleStatusEnum.ARCHIVED);
        rejected("MCP_SI_MISMATCH", () -> loader.require(fixture.selection()));
        original.setStatus(DdcSiLifecycleStatusEnum.PUBLISHED).setDigestSha256("c".repeat(64));
        rejected("MCP_SI_MISMATCH", () -> loader.require(fixture.selection()));
    }

    @Test
    void transportCannotSupplyAnArbitraryUrlOrDifferentOwnerProofEvenWhenTheMountShaIsApproved() throws Exception {
        Fixture fixture = fixture("orders", false);
        ((ObjectNode) fixture.binding().path("transport")).put("endpoint", "http://attacker.test");
        Fixture first = fixture;
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(first.binding()));
        fixture = fixture("orders", false);
        ((ObjectNode) fixture.binding().path("publicationEvidence")).put("ownerTenantId", "8");
        resignPublication((ObjectNode) fixture.binding().path("publicationEvidence"));
        Fixture rejectedFixture = fixture;
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(rejectedFixture.binding()));
    }

    @Test
    void sourceDefinitionBytesAndEveryPublicationDigestAreVerifiedSeparately() throws Exception {
        Fixture fixture = fixture("orders", false);
        ((ObjectNode) fixture.binding().path("operation")).put("definitionJson", "{}");
        Fixture first = fixture;
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(first.binding()));
        fixture = fixture("orders", false);
        ((ObjectNode) fixture.binding().path("publicationEvidence")).put("artifactSha256", "d".repeat(64));
        Fixture badArtifact = fixture;
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(badArtifact.binding()));
        fixture = fixture("orders", false);
        ((ObjectNode) fixture.binding().path("sourceMappingEvidence")).put("contentSha256", "d".repeat(64));
        Fixture badSource = fixture;
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(badSource.binding()));
    }

    @Test
    void seedIsNotAnExecutablePublishedProofAndWideningToolPermissionsIsNotAccepted() throws Exception {
        Fixture fixture = fixture("orders", false);
        fixture.binding().set("publicationEvidence", fixture.binding().get("sourceMappingEvidence"));
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(fixture.binding()));
        Fixture weakened = fixture("orders", false);
        ((ObjectNode) weakened.binding().path("publicationEvidence").path("mappings").get(0).path("tool"))
                .putArray("requiredPermissions");
        resignPublication((ObjectNode) weakened.binding().path("publicationEvidence"));
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(weakened.binding()));
    }

    @Test
    void invalidReloadClearsTheOldBundleAndRestoringTheExactPinnedBytesAllowsRecovery() throws Exception {
        Fixture fixture = fixture("orders", false);
        Path path = directory.toRealPath().resolve("bindings.json");
        byte[] bytes = bundle(fixture.binding()).toString().getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes);
        var loader = new McpPublishedBindingLoader(path, hash(bytes), si, "mcp-access", clock);
        loader.reload(); loader.require(fixture.selection());
        Files.writeString(path, "{}");
        rejected("MCP_BINDINGS_FILE_INVALID", loader::reload);
        assertFalse(loader.isReady());
        rejected("MCP_BINDINGS_NOT_READY", () -> loader.require(fixture.selection()));
        Files.write(path, bytes); loader.reload();
        assertEquals(fixture.selection(), loader.require(fixture.selection()).selection());
    }

    @Test
    void duplicateJsonKeysSymlinksAndExpiredBundlesFailClosed() throws Exception {
        Fixture fixture = fixture("orders", false);
        Path path = directory.toRealPath().resolve("bindings.json");
        byte[] duplicate = "{\"schemaVersion\":\"mcp-execution-binding-v1\",\"schemaVersion\":\"other\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(path, duplicate);
        var loader = new McpPublishedBindingLoader(path, hash(duplicate), si, "mcp-access", clock);
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, loader::reload);
        byte[] bytes = bundle(fixture.binding()).toString().getBytes(StandardCharsets.UTF_8); Files.write(path, bytes);
        Path link = directory.toRealPath().resolve("link.json"); Files.createSymbolicLink(link, path);
        var symlink = new McpPublishedBindingLoader(link, hash(bytes), si, "mcp-access", clock);
        rejected("MCP_BINDINGS_FILE_INVALID", symlink::reload);
        loader = new McpPublishedBindingLoader(path, hash(bytes), si, "mcp-access", clock); loader.reload();
        clock.now = clock.now.plusSeconds(600);
        assertFalse(loader.isReady());
        assertEquals("MCP_BINDINGS_EXPIRED", loader.lastErrorCode());
        var expired = loader;
        rejected("MCP_BINDINGS_EXPIRED", () -> expired.require(fixture.selection()));
    }

    @Test
    void anAuthorityReadThatCrossesTheExpiryCannotYieldAnExecutionContext() throws Exception {
        Fixture fixture = fixture("orders", false); var loader = loader(fixture.binding());
        si.onRead = () -> clock.now = clock.now.plusSeconds(601);
        rejected("MCP_BINDINGS_NOT_READY", () -> loader.require(fixture.selection()));
    }

    @Test
    void rpcDescriptorPinsTheExactUnaryMethodAndUsesAReferenceToTheTrustedComponentRpcTarget() throws Exception {
        Fixture fixture = fixture("orders", true); var loader = loader(fixture.binding());
        var descriptor = loader.require(fixture.selection()).operation().rpcDescriptor();
        assertEquals("fixture.Orders/Lookup", descriptor.fullMethodName());
        assertEquals("fixture.Request", descriptor.requestType());
        byte[] first = descriptor.descriptorSet(); first[0] = 0;
        assertNotEquals(0, descriptor.descriptorSet()[0]);
        assertEquals("orders-rpc", loader.require(fixture.selection()).transport().rpcBindingId());
        ((ObjectNode) fixture.binding().path("operation").path("rpcDescriptor")).put("fullMethodName", "fixture.Orders/Write");
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(fixture.binding()));
    }

    @Test
    void streamingOrMissingDescriptorDependenciesAreNotSilentlyTreatedAsUnary() throws Exception {
        Fixture fixture = fixture("orders", true);
        ObjectNode rpc = (ObjectNode) fixture.binding().path("operation").path("rpcDescriptor");
        byte[] streaming = descriptor(true); rpc.put("base64DescriptorSet", Base64.getEncoder().encodeToString(streaming));
        rpc.put("sha256", hash(streaming));
        // The exact definition digest deliberately still pins the original descriptor; either check must deny.
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loader(fixture.binding()));
    }

    @Test
    void readsTheCommittedStep32EmitterVectorWithoutChangingItsCanonicalDigests() {
        // Produced by the actual emitter, synthetic scope only; this is not a deployment approval.
        String wire = "{\"artifactSha256\":\"2c54d55f3ae4d4067774c46c4378cd7ff2d05f9306a81d5cf0242da92a2acc63\",\"contentSha256\":\"6123dcde4b13145840a7f5699c07f4f6a63db853859cde457fb5f90db88d293e\",\"gatewayGroupId\":\"10\",\"mappings\":[{\"dependencyClosureDigest\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\"siServer\":{\"bizCode\":\"commerce\",\"digestSha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"envCode\":\"test\",\"namespaceCode\":\"default\",\"resourceId\":\"201\",\"resourceKey\":\"orders\",\"versionId\":\"501\",\"versionNo\":\"1\"},\"sourceOperation\":{\"definitionDigest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"operationId\":\"101\",\"operationKey\":\"http:orders:GET:/orders/101\"},\"tool\":{\"annotations\":{},\"description\":null,\"enabled\":true,\"idempotent\":true,\"inputSchema\":\"{\\\"type\\\":\\\"object\\\"}\",\"name\":\"orders.get\",\"operationId\":\"101\",\"operationProtocol\":\"HTTP\",\"outputSchema\":\"{\\\"type\\\":\\\"object\\\"}\",\"remoteMountId\":null,\"requiredPermissions\":[\"mcp:orders:read\"],\"riskLevel\":\"LOW\",\"serverCode\":\"orders\",\"sourceType\":\"LOCAL_OPERATION\",\"toolId\":\"da2c81693a40d61a481a91fccd5d6ee91dc6ad71508eeaa8c4c30a3267c58b7b\"},\"toolId\":\"da2c81693a40d61a481a91fccd5d6ee91dc6ad71508eeaa8c4c30a3267c58b7b\"}],\"ownerTenantId\":\"7\",\"publicationId\":\"301\",\"publishedSi\":{\"bizCode\":\"commerce\",\"digestSha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"envCode\":\"test\",\"namespaceCode\":\"default\",\"ownerAppCode\":null,\"resourceId\":\"201\",\"resourceKey\":\"orders\",\"versionId\":\"501\",\"versionNo\":\"1\"},\"schemaVersion\":\"yuheng-mcp-mapping-publication-v1\",\"sourceRelease\":{\"artifactSha256\":\"34a2c3a6c1333f1f9b35c447b46ed81bf78b1bb91f3fa6d3ddff0cf5450438ba\",\"mappingContentSha256\":\"0734a534012825e8414c480e33b011368cbcf02cc090610ae2be166fa1b705bd\",\"releaseId\":\"301\"}}";
        assertEquals("f16225ee00b53be57b226cf83ac7ed121a6c69642d0716085e1cc0e4bfe61436", hash(wire.getBytes(StandardCharsets.UTF_8)));
        JsonNode proof = DdcConfigCanonicalJson.parse(wire.getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> McpPublishedBindingLoader.verifyPublicationEvidence(proof));
        ((ObjectNode) proof.path("publishedSi")).put("versionNo", "2");
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> McpPublishedBindingLoader.verifyPublicationEvidence(proof));
    }

    @Test
    void activeV2AndExplicitHistoricalV1CoexistAndOldTasksStillRevalidateTheExactV1() throws Exception {
        Fixture original = fixture("orders", false);
        var oldLoader = loader(original.binding());
        var oldPin = oldLoader.require(original.selection()).pin();
        ObjectNode next = original.binding().deepCopy(); next.put("version", "2");
        ObjectNode nextSi = (ObjectNode) next.path("si"); nextSi.put("versionId", "502").put("versionNo", "2");
        ObjectNode proof = (ObjectNode) next.path("publicationEvidence"); proof.put("publicationId", "302");
        ((ObjectNode) proof.path("sourceRelease")).put("releaseId", "302"); proof.set("publishedSi", nextSi.deepCopy());
        var identity = new DdcSiDependencyManifestVO.VersionIdentityVO("7", "201", "502", "2", DdcSiKindEnum.MCP, "test", nextSi.path("digestSha256").textValue());
        var manifest = DdcSiDependencyManifestVO.create(identity, null, List.of(), List.of());
        ObjectNode mapping = (ObjectNode) proof.path("mappings").get(0); mapping.set("siServer", nextSi.deepCopy());
        mapping.put("dependencyClosureDigest", manifest.closureDigest()); resignPublication(proof);
        var version2 = new DdcSiResourceDescriptor().setTenantId(7L).setResourceId(201L).setBizCode("commerce").setNamespaceCode("default")
                .setEnvCode("test").setOwnerAppCode("owner-app").setKind(DdcSiKindEnum.MCP).setResourceKey("orders")
                .setResolvedVersionId(502L).setResolvedVersionNo(2L).setDigestSha256(nextSi.path("digestSha256").textValue())
                .setSpecJson(si.values.get("orders").getSpecJson()).setDependencyManifest(manifest).setStatus(DdcSiLifecycleStatusEnum.PUBLISHED);
        si.exactValues.put("orders@2", version2);
        ObjectNode combined = bundle(next); combined.putArray("historicalBindings").add(original.binding());
        var loader = loaderDocument(combined);
        assertEquals(2L, loader.require(original.selection()).si().versionNo());
        assertEquals(2L, si.version);
        assertEquals(oldPin, loader.requirePinned(original.selection(), oldPin).pin());
        assertEquals(1L, si.version);
        si.missingVersions.add("orders@1");
        rejected("MCP_SI_UNAVAILABLE", () -> loader.requirePinned(original.selection(), oldPin));
        assertEquals(2L, loader.require(original.selection()).si().versionNo());
        var activeOnly = loader(next);
        rejected("MCP_BINDING_PIN_UNAVAILABLE", () -> activeOnly.requirePinned(original.selection(), oldPin));
        combined.withArray("historicalBindings").add(original.binding());
        assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, () -> loaderDocument(combined));
    }

    @Test
    void structuralCatalogueEnumerationIsImmutableAndNeverAdmitsOrCallsSi() throws Exception {
        Fixture fixture = fixture("orders", false); var loader = loader(fixture.binding());
        si.denied.add("orders");
        assertEquals(1, loader.fixedActiveBindings().size()); assertEquals(1, loader.fixedPinnedBindings().size());
        assertEquals(0, si.reads);
        assertThrows(UnsupportedOperationException.class, () -> loader.fixedActiveBindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> loader.fixedPinnedBindings().clear());
        rejected("MCP_SI_AUTHORITY_UNAVAILABLE", () -> loader.require(fixture.selection()));
        clock.now = clock.now.plusSeconds(601);
        rejected("MCP_BINDINGS_EXPIRED", loader::fixedActiveBindings);
        rejected("MCP_BINDINGS_EXPIRED", loader::fixedPinnedBindings);
    }

    @Test
    void aPinnedEmptyLocalInventoryIsValidWithoutInventingAnOperation() throws Exception {
        var loader = loader();
        assertTrue(loader.isReady()); assertTrue(loader.fixedActiveBindings().isEmpty()); assertTrue(loader.fixedPinnedBindings().isEmpty());
        rejected("MCP_BINDING_PIN_UNAVAILABLE", () -> loader.require(new McpPublishedBindingLoader.SelectionVO(7, "orders", "a".repeat(64))));
    }

    @Test
    void retainedOldPinCannotUndoTheCurrentSameToolDisableOrTighterPermissionFloor() throws Exception {
        Fixture original = fixture("orders", false); var oldPin = loader(original.binding()).require(original.selection()).pin();
        ObjectNode active = original.binding().deepCopy(); active.put("version", "2");
        ObjectNode activeTool = (ObjectNode) active.path("publicationEvidence").path("mappings").get(0).path("tool");
        activeTool.put("enabled", false); resignPublication((ObjectNode) active.path("publicationEvidence"));
        ObjectNode document = bundle(active); document.putArray("historicalBindings").add(original.binding());
        var disabled = loaderDocument(document);
        rejected("MCP_TOOL_DISABLED", () -> disabled.requirePinned(original.selection(), oldPin));
        activeTool.put("enabled", true); activeTool.withArray("requiredPermissions").add("orders:approved");
        resignPublication((ObjectNode) active.path("publicationEvidence"));
        var tightened = loaderDocument(document);
        rejected("MCP_BINDING_POLICY_REVOKED", () -> tightened.requirePinned(original.selection(), oldPin));
    }

    /** Shared test-only fixture: real pinned-file loading, not a production verification bypass. */
    public static StructuralFixtureVO structuralFixture(Path directory, boolean withHistory) throws Exception {
        var owner = new McpPublishedBindingContractTest(); owner.directory = directory;
        Fixture original = owner.fixture("orders", false);
        ObjectNode active = original.binding().deepCopy();
        if (withHistory) active.put("version", "2");
        ObjectNode document = owner.bundle(active);
        if (withHistory) document.putArray("historicalBindings").add(original.binding());
        return new StructuralFixtureVO(owner.loaderDocument(document), active, withHistory ? original.binding() : null, original.selection());
    }
    public record StructuralFixtureVO(McpPublishedBindingLoader loader, ObjectNode binding, ObjectNode historicalBinding,
                                      McpPublishedBindingLoader.SelectionVO selection) { }

    private McpPublishedBindingLoader loader(ObjectNode... bindings) throws Exception {
        return loaderDocument(bundle(bindings));
    }
    private McpPublishedBindingLoader loaderDocument(ObjectNode document) throws Exception {
        Path file = directory.toRealPath().resolve(UUID.randomUUID() + ".json");
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8); Files.write(file, bytes);
        var loader = new McpPublishedBindingLoader(file, hash(bytes), si, "mcp-access", clock); loader.reload(); return loader;
    }
    private ObjectNode bundle(ObjectNode... bindings) {
        ObjectNode root = object().put("schemaVersion", McpPublishedBindingLoader.SCHEMA_VERSION).put("validUntil", clock.now.plusSeconds(600).toString());
        ArrayNode entries = root.putArray("bindings"); for (ObjectNode binding : bindings) entries.add(binding); return root;
    }
    private Fixture fixture(String server, boolean rpc) {
        String operationId = "11", operationKey = rpc ? "RPC fixture.Orders/Lookup" : "GET /orders/{id}";
        String toolId = hash((server + '\0' + operationKey).getBytes(StandardCharsets.UTF_8));
        ObjectNode definition = object().put("summary", "lookup"); definition.putArray("tags");
        definition.set("requestSchema", object().put("type", "object")); definition.set("responseSchema", object().put("type", "object"));
        definition.putArray("errorSchema");
        byte[] descriptor = rpc ? descriptor(false) : null;
        if (rpc) definition.putObject("descriptorSnapshot").put("sha256", hash(descriptor)); else definition.putNull("descriptorSnapshot");
        ObjectNode exposure = definition.putObject("attributes").putObject("mcpExposure").put("registerMcp", true)
                .put("mcpServerCode", server).put("mcpName", "lookup").put("riskLevel", "LOW").put("idempotent", true);
        exposure.putArray("requiredPermissions").add("orders:read"); definition.put("externalAccessible", false);
        String definitionJson = definition.toString(), definitionDigest = hash(definitionJson.getBytes(StandardCharsets.UTF_8));
        ObjectNode source = object().put("operationId", operationId).put("operationKey", operationKey).put("definitionDigest", definitionDigest);
        ObjectNode tool = object().put("toolId", toolId).put("serverCode", server).put("name", "lookup").put("description", "lookup")
                .put("sourceType", "LOCAL_OPERATION").put("operationId", operationId).put("operationProtocol", rpc ? "RPC" : "HTTP")
                .putNull("remoteMountId").put("inputSchema", "{\"type\":\"object\"}").put("outputSchema", "{\"type\":\"object\"}")
                .put("riskLevel", "LOW").put("idempotent", true).put("enabled", true);
        if (!rpc) tool.put("inputSchema", "{\"type\":\"object\",\"properties\":{}}");
        tool.putObject("annotations"); tool.putArray("requiredPermissions").add("orders:read");
        ObjectNode registered = object().put("registrationId", "91").put("resourceId", "201").put("bizCode", "commerce")
                .put("namespaceCode", "default").put("envCode", "test").put("ownerAppCode", "owner-app").put("resourceKey", server)
                .put("kind", "MCP").put("registrationRevision", "0").put("registrationDescriptorSha256", "a".repeat(64));
        ObjectNode seed = object().put("schemaVersion", "yuheng-mcp-mapping-seed-v1").put("publicationId", "300").put("ownerTenantId", "7")
                .put("gatewayGroupId", "10").put("sourceDraftRevision", "1").put("frozenAt", clock.now.minusSeconds(60).toString());
        seed.set("registeredSi", registered);
        ObjectNode seedEntry = seed.putArray("mappings").addObject().put("toolId", toolId); seedEntry.set("sourceOperation", source); seedEntry.set("tool", tool);
        ObjectNode seedPayload = object(); for (String name : List.of("schemaVersion", "registeredSi", "mappings")) seedPayload.set(name, seed.get(name));
        seed.put("contentSha256", canonicalHash(seedPayload)); seed.put("artifactSha256", canonicalHash(seed));
        ObjectNode spec = object().put("schemaVersion", "mcp-server-v2").put("sourceType", "COMPOSED_API");
        ObjectNode pointer = spec.putObject("apiMapping").put("gatewayGroupId", "10").put("publicationId", "300")
                .put("schemaVersion", "yuheng-mcp-mapping-seed-v1").put("contentSha256", seed.path("contentSha256").textValue())
                .put("artifactSha256", seed.path("artifactSha256").textValue());
        ObjectNode siTool = spec.putArray("tools").addObject().put("toolId", toolId).put("name", "lookup").put("description", "lookup")
                .put("riskLevel", "LOW").put("idempotent", true).put("enabled", true);
        ObjectNode liveInput = siTool.putObject("inputSchema").put("type", "object"); if (!rpc) liveInput.putObject("properties");
        siTool.putObject("outputSchema").put("type", "object");
        siTool.putArray("requiredPermissions").add("orders:read");
        String specJson = spec.toString(), siDigest = hash(specJson.getBytes(StandardCharsets.UTF_8));
        var identity = new DdcSiDependencyManifestVO.VersionIdentityVO("7", "201", "501", "1", DdcSiKindEnum.MCP, "test", siDigest);
        var manifest = DdcSiDependencyManifestVO.create(identity, null, List.of(), List.of());
        var descriptorVo = new DdcSiResourceDescriptor().setTenantId(7L).setResourceId(201L).setBizCode("commerce").setNamespaceCode("default")
                .setEnvCode("test").setOwnerAppCode("owner-app").setKind(DdcSiKindEnum.MCP).setResourceKey(server)
                .setResolvedVersionId(501L).setResolvedVersionNo(1L).setDigestSha256(siDigest).setSpecJson(specJson)
                .setDependencyManifest(manifest).setStatus(DdcSiLifecycleStatusEnum.PUBLISHED);
        si.values.put(server, descriptorVo);
        ObjectNode siRef = object().put("resourceId", "201").put("versionId", "501").put("versionNo", "1").put("digestSha256", siDigest)
                .put("bizCode", "commerce").put("namespaceCode", "default").put("envCode", "test").put("ownerAppCode", "owner-app").put("resourceKey", server);
        ObjectNode proof = object().put("schemaVersion", McpPublishedBindingLoader.PUBLICATION_SCHEMA).put("publicationId", "301").put("ownerTenantId", "7").put("gatewayGroupId", "10");
        proof.putObject("sourceRelease").put("releaseId", "301").put("artifactSha256", "b".repeat(64)).put("mappingContentSha256", "c".repeat(64));
        proof.set("publishedSi", siRef);
        ObjectNode mapping = proof.putArray("mappings").addObject().put("toolId", toolId).put("dependencyClosureDigest", manifest.closureDigest());
        mapping.set("sourceOperation", source); mapping.set("siServer", siRef); mapping.set("tool", tool); resignPublication(proof);
        ObjectNode operation = object().put("operationId", operationId).put("operationKey", operationKey).put("protocol", rpc ? "RPC" : "HTTP")
                .put("methodIdentity", rpc ? "fixture.Orders/Lookup" : "GET /orders/{id}").put("definitionDigest", definitionDigest).put("definitionJson", definitionJson);
        if (rpc) operation.putObject("rpcDescriptor").put("sha256", hash(descriptor)).put("base64DescriptorSet", Base64.getEncoder().encodeToString(descriptor))
                .put("serviceName", "fixture.Orders").put("group", "orders").put("version", "1.0.0").put("fullMethodName", "fixture.Orders/Lookup")
                .put("requestType", "fixture.Request").put("responseType", "fixture.Response");
        else operation.putNull("rpcDescriptor");
        ObjectNode transport = object().put("namespace", "apps-prod").put("service", "orders-api").put("port", "8080");
        if (rpc) transport.put("rpcBindingId", "orders-rpc").put("rpcBindingVersion", "1"); else transport.putNull("rpcBindingId").putNull("rpcBindingVersion");
        transport.putObject("credential").put("issuer", "https://identity.example.test/tenants/90001").put("registrationId", "business-orders")
                .put("appId", "mcp-access").put("identityTenantId", "90001").put("audience", "https://identity.example.test/resources/orders")
                .putArray("scopes").add("orders:read");
        ObjectNode binding = object().put("bindingId", server + "-execution").put("version", "1").put("ownerTenantId", "7").put("serverCode", server).put("toolId", toolId);
        binding.set("si", siRef.deepCopy()); binding.set("publicationEvidence", proof.deepCopy()); binding.set("sourceMappingEvidence", seed.deepCopy());
        binding.set("operation", operation.deepCopy()); binding.set("transport", transport.deepCopy());
        return new Fixture(binding, new McpPublishedBindingLoader.SelectionVO(7, server, toolId));
    }
    private static byte[] descriptor(boolean streaming) {
        var request = DescriptorProtos.DescriptorProto.newBuilder().setName("Request");
        var response = DescriptorProtos.DescriptorProto.newBuilder().setName("Response");
        var method = DescriptorProtos.MethodDescriptorProto.newBuilder().setName("Lookup").setInputType(".fixture.Request")
                .setOutputType(".fixture.Response").setServerStreaming(streaming);
        var file = DescriptorProtos.FileDescriptorProto.newBuilder().setName("orders.proto").setPackage("fixture").setSyntax("proto3")
                .addMessageType(request).addMessageType(response).addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("Orders").addMethod(method));
        return DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build().toByteArray();
    }
    private static void resignPublication(ObjectNode proof) {
        ObjectNode content = object(); for (String field : List.of("schemaVersion", "ownerTenantId", "gatewayGroupId", "publishedSi", "mappings")) content.set(field, proof.get(field));
        proof.put("contentSha256", canonicalHash(content)); proof.remove("artifactSha256"); proof.put("artifactSha256", canonicalHash(proof));
    }
    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static String canonicalHash(JsonNode n) { return hash(normalize(n).toString().getBytes(StandardCharsets.UTF_8)); }
    private static JsonNode normalize(JsonNode n) {
        if (n.isObject()) { ObjectNode result = object(); var keys = new ArrayList<String>(); n.fieldNames().forEachRemaining(keys::add); keys.stream().sorted().forEach(key -> result.set(key, normalize(n.get(key)))); return result; }
        if (n.isArray()) { ArrayNode result = JsonNodeFactory.instance.arrayNode(); n.forEach(value -> result.add(normalize(value))); return result; }
        if (n.isNumber()) return JsonNodeFactory.instance.numberNode(n.decimalValue().stripTrailingZeros()); return n.deepCopy();
    }
    private static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception impossible) { throw new AssertionError(impossible); } }
    private static void rejected(String code, Runnable action) {
        assertEquals(code, assertThrows(McpPublishedBindingLoader.BindingRejectedException.class, action::run).errorCode());
    }
    private record Fixture(ObjectNode binding, McpPublishedBindingLoader.SelectionVO selection) { }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-19T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private static final class FakeSi implements DdcSiCatalogClient {
        private final Map<String,DdcSiResourceDescriptor> values = new HashMap<>(), exactValues = new HashMap<>();
        private final Set<String> missingVersions = new HashSet<>();
        private final Set<String> denied = new HashSet<>(), missing = new HashSet<>();
        private int reads; private String caller, owner; private Long version; private Runnable onRead = () -> { };
        @Override public Optional<DdcSiResourceDescriptor> resolveExecutionResource(long tenant, String biz, String namespace, String env,
                String owner, String caller, DdcSiKindEnum kind, String key, Long version) {
            reads++; this.caller = caller; this.owner = owner; this.version = version; onRead.run();
            if (denied.contains(key)) throw new IllegalStateException("synthetic authority rejection");
            return missing.contains(key) || missingVersions.contains(key + "@" + version) ? Optional.empty()
                    : Optional.ofNullable(exactValues.getOrDefault(key + "@" + version, values.get(key)));
        }
        @Override public List<DdcSiResourceDescriptor> listResources(DdcSiCatalogQuery query) { throw new AssertionError("no active catalog lookup"); }
        @Override public Optional<DdcSiResourceDescriptor> resolveResource(long tenant, String biz, String ns, String env, String app, DdcSiKindEnum kind, String key, Integer version) { throw new AssertionError("no active fallback"); }
        @Override public List<DdcSiSearchHit> semanticSearch(DdcSiSemanticSearchQuery query) { throw new AssertionError("no search"); }
        @Override public DdcSiResourceDescriptor registerManagedResource(DdcSiSelfRegistrationRequest request) { throw new AssertionError("no writes"); }
    }
}
