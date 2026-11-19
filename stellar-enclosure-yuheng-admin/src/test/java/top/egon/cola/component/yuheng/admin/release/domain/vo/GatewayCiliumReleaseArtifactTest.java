package top.egon.cola.component.yuheng.admin.release.domain.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayCiliumReleaseArtifactTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String MCP_SCHEMA = "mcp-api-mapping-v1";
    private static final String TOOL = "b".repeat(64);

    @Test
    void mappingOnlyNeedsNoGatewayOrPublicExposure() {
        var snapshot = snapshot();
        snapshot.set("mcpPublication", section(JSON.createArrayNode().add(mapping(TOOL))));

        var artifact = new GatewayCiliumReleaseArtifact(snapshot, activation(false), manifest(false));

        assertThat(artifact.canonicalSnapshot().has("exposurePublication")).isFalse();
        assertThat(artifact.canonicalSnapshot().path("mcpPublication").path("mappings")).hasSize(1);
        assertThat(artifact.activationContent().path("exposures")).isEmpty();
        assertThat(artifact.chunkManifest().path("resources")).isEmpty();
        assertThat(artifact.hasExposurePublication()).isFalse();
        assertThat(artifact.hasMcpPublication()).isTrue();
        assertThat(artifact.ruleContentSha256()).isEqualTo(digest(artifact.mcpPublication()));
    }

    @Test
    void mixedArtifactRejectsAMappingChecksumMismatch() {
        var snapshot = httpSnapshot();
        var publication = section(JSON.createArrayNode().add(mapping(TOOL)));
        publication.put("contentSha256", "0".repeat(64));
        snapshot.set("mcpPublication", publication);

        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest(true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("YUHENG_CILIUM_ARTIFACT_INVALID");
    }

    @Test
    void existingHttpOnlyDocumentsAndRuleDigestRemainUnchanged() {
        var snapshot = httpSnapshot();
        var activation = activation(true);
        var manifest = manifest(true);
        var artifact = new GatewayCiliumReleaseArtifact(snapshot, activation, manifest);

        assertThat(artifact.canonicalSnapshot()).isEqualTo(sorted(snapshot));
        assertThat(artifact.activationContent()).isEqualTo(sorted(activation));
        assertThat(artifact.chunkManifest()).isEqualTo(sorted(manifest));
        assertThat(artifact.ruleContentSha256()).isEqualTo(digest(snapshot.path("exposurePublication")));
        var envelope = JSON.createObjectNode();
        envelope.set("canonicalSnapshot", snapshot);
        envelope.set("activationContent", activation);
        envelope.set("chunkManifest", manifest);
        assertThat(artifact.artifactSha256()).isEqualTo(digest(envelope));
        assertThat(artifact.hasExposurePublication()).isTrue();
        assertThat(artifact.hasMcpPublication()).isFalse();
        assertThat(artifact.mcpPublication()).isNull();
    }

    @Test
    void rejectsNeitherPublicationAndAnEmptyHttpOnlyPublication() {
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot(), activation(false), manifest(false)))
                .isInstanceOf(IllegalArgumentException.class);
        var empty = httpSnapshot();
        ((ObjectNode) empty.path("exposurePublication")).putArray("exposures");
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(empty, activation(false), manifest(false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factoryOrdersMappingsAndSetPermissionsWithoutRetainingMutableInput() {
        var first = mapping("a".repeat(64));
        var permissions = ((ObjectNode) first.path("tool")).putArray("requiredPermissions");
        permissions.add("orders:write").add("orders:read");
        var second = mapping(TOOL);
        var input = JSON.createArrayNode().add(second).add(first);
        JsonNode publication = GatewayCiliumReleaseArtifact.mcpPublication(input);
        JsonNode reversed = GatewayCiliumReleaseArtifact.mcpPublication(JSON.createArrayNode().add(first).add(second));

        assertThat(publication).isEqualTo(reversed);
        assertThat(publication.path("schemaVersion").textValue()).isEqualTo(GatewayCiliumReleaseArtifact.MCP_SCHEMA_VERSION);
        assertThat(publication.path("mappings").get(0).path("toolId").textValue()).isEqualTo("a".repeat(64));
        assertThat(publication.path("mappings").get(0).path("tool").path("requiredPermissions").get(0).textValue())
                .isEqualTo("orders:read");
        var payload = publication.deepCopy();
        ((ObjectNode) payload).remove("contentSha256");
        assertThat(publication.path("contentSha256").textValue()).isEqualTo(digest(payload));
        first.put("dependencyClosureDigest", "f".repeat(64));
        assertThat(publication.path("mappings").get(0).path("dependencyClosureDigest").textValue()).isEqualTo("c".repeat(64));
    }

    @Test
    void constructorAndEveryAccessorKeepMappingOwnershipImmutable() {
        var input = section(JSON.createArrayNode().add(mapping(TOOL)));
        var snapshot = snapshot();
        snapshot.set("mcpPublication", input);
        var artifact = new GatewayCiliumReleaseArtifact(snapshot, activation(false), manifest(false));
        String digest = artifact.artifactSha256();

        ((ObjectNode) input.path("mappings").get(0).path("tool")).put("enabled", false);
        ((ObjectNode) artifact.mcpPublication().path("mappings").get(0).path("tool")).put("enabled", false);
        ((ObjectNode) artifact.canonicalSnapshot()).remove("mcpPublication");

        assertThat(artifact.mcpPublication().path("mappings").get(0).path("tool").path("enabled").booleanValue()).isTrue();
        assertThat(artifact.artifactSha256()).isEqualTo(digest);
    }

    @Test
    void mixedBehaviorDigestIncludesBothPublicationsAndSurvivesJsonRoundTrip() throws Exception {
        var snapshot = httpSnapshot();
        snapshot.set("mcpPublication", GatewayCiliumReleaseArtifact.mcpPublication(JSON.createArrayNode().add(mapping(TOOL))));
        var artifact = new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest(true));
        var content = JSON.createObjectNode();
        content.set("exposurePublication", artifact.canonicalSnapshot().path("exposurePublication"));
        content.set("mcpPublication", artifact.mcpPublication());
        assertThat(artifact.ruleContentSha256()).isEqualTo(digest(content));

        var roundTrip = new GatewayCiliumReleaseArtifact(JSON.readTree(artifact.canonicalSnapshot().toString()),
                JSON.readTree(artifact.activationContent().toString()), JSON.readTree(artifact.chunkManifest().toString()));
        assertThat(roundTrip.ruleContentSha256()).isEqualTo(artifact.ruleContentSha256());
        assertThat(roundTrip.artifactSha256()).isEqualTo(artifact.artifactSha256());
        var changed = mapping(TOOL);
        ((ObjectNode) changed.path("tool")).put("enabled", false);
        snapshot.set("mcpPublication", GatewayCiliumReleaseArtifact.mcpPublication(JSON.createArrayNode().add(changed)));
        var next = new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest(true));
        assertThat(next.ruleContentSha256()).isNotEqualTo(artifact.ruleContentSha256());
        assertThat(next.artifactSha256()).isNotEqualTo(artifact.artifactSha256());
        assertThat(next.canonicalSnapshot().path("exposurePublication"))
                .isEqualTo(artifact.canonicalSnapshot().path("exposurePublication"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMappings")
    void rejectsMalformedOrRuntimeBearingMappingsEvenWithARecomputedChecksum(String label, Consumer<ObjectNode> change) {
        var mapping = mapping(TOOL);
        change.accept(mapping);
        var snapshot = snapshot();
        snapshot.set("mcpPublication", section(JSON.createArrayNode().add(mapping)));

        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(false), manifest(false)))
                .as(label).isInstanceOf(IllegalArgumentException.class).hasMessage("YUHENG_CILIUM_ARTIFACT_INVALID");
        assertThatThrownBy(() -> GatewayCiliumReleaseArtifact.mcpPublication(JSON.createArrayNode().add(mapping)))
                .as(label).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> invalidMappings() {
        return Stream.of(
                invalid("uppercase tool ID", value -> value.put("toolId", "B".repeat(64))),
                invalid("missing source", value -> value.remove("sourceOperation")),
                invalid("coerced operation ID", value -> ((ObjectNode) value.path("sourceOperation")).put("operationId", 701)),
                invalid("unbounded SI version", value -> ((ObjectNode) value.path("siServer")).put("versionNo", "9223372036854775808")),
                invalid("source digest malformed", value -> ((ObjectNode) value.path("sourceOperation")).put("definitionDigest", "wrong")),
                invalid("closure missing", value -> value.remove("dependencyClosureDigest")),
                invalid("closure invalid", value -> value.put("dependencyClosureDigest", "C".repeat(64))),
                invalid("tool identity mismatch", value -> ((ObjectNode) value.path("tool")).put("toolId", "a".repeat(64))),
                invalid("operation identity mismatch", value -> ((ObjectNode) value.path("tool")).put("operationId", "702")),
                invalid("unknown entry", value -> value.put("mappingRuntime", "unexpected")),
                invalid("raw SI body", value -> ((ObjectNode) value.path("siServer")).put("specJson", "{}")),
                invalid("credential material", value -> ((ObjectNode) value.path("tool")).put("credentials", "forbidden")),
                invalid("remote execution", value -> ((ObjectNode) value.path("tool")).put("remoteMountId", "mount-1")),
                invalid("runtime endpoint", value -> ((ObjectNode) value.path("tool")).put("endpoint", "http://unexpected")),
                invalid("metadata credential", value -> ((ObjectNode) value.path("tool").path("annotations")).put("Authorization", "forbidden")),
                invalid("coerced enabled flag", value -> ((ObjectNode) value.path("tool")).put("enabled", "true")),
                invalid("duplicate permissions", value -> ((ArrayNode) value.path("tool").path("requiredPermissions")).add("orders:read")));
    }

    private static Arguments invalid(String label, Consumer<ObjectNode> change) { return Arguments.of(label, change); }

    @Test
    void rejectsUnknownSectionVersionOrFieldsAndEmptyOrRepeatedToolSets() {
        var snapshot = httpSnapshot();
        var publication = section(JSON.createArrayNode().add(mapping(TOOL)));
        publication.put("schemaVersion", "mcp-api-mapping-v2");
        snapshot.set("mcpPublication", publication);
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest(true)))
                .isInstanceOf(IllegalArgumentException.class);
        publication.put("schemaVersion", MCP_SCHEMA).put("runtimeEndpoint", "forbidden");
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest(true)))
                .isInstanceOf(IllegalArgumentException.class);
        snapshot.set("mcpPublication", section(JSON.createArrayNode()));
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest(true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayCiliumReleaseArtifact.mcpPublication(JSON.createArrayNode())).isInstanceOf(IllegalArgumentException.class);
        var duplicate = JSON.createArrayNode().add(mapping(TOOL)).add(mapping(TOOL));
        assertThatThrownBy(() -> GatewayCiliumReleaseArtifact.mcpPublication(duplicate)).isInstanceOf(IllegalArgumentException.class);
        snapshot.set("mcpPublication", section(duplicate));
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest(true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mappingOnlyCannotSmuggleCiliumActivationOrResources() {
        var snapshot = snapshot();
        snapshot.set("mcpPublication", GatewayCiliumReleaseArtifact.mcpPublication(JSON.createArrayNode().add(mapping(TOOL))));
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest(false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(false), manifest(true)))
                .isInstanceOf(IllegalArgumentException.class);
        var dependencies = manifest(false);
        dependencies.withArray("dependencies").addObject().put("service", "unexpected");
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(false), dependencies))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mixedMappingDoesNotWeakenTheExistingInertHttpRouteFence() {
        var snapshot = httpSnapshot();
        snapshot.set("mcpPublication", GatewayCiliumReleaseArtifact.mcpPublication(JSON.createArrayNode().add(mapping(TOOL))));
        var manifest = manifest(true);
        ((ArrayNode) manifest.path("resources").get(0).path("object").path("spec").path("parentRefs"))
                .addObject().put("name", "unexpected-attached-gateway");
        assertThatThrownBy(() -> new GatewayCiliumReleaseArtifact(snapshot, activation(true), manifest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode snapshot() {
        var result = JSON.createObjectNode().put("releaseId", "820001").put("ownerTenantId", "2001")
                .put("gatewayGroupId", "800001").put("generatedAt", "2026-11-19T01:00:00Z");
        result.putObject("sourceContent");
        return result;
    }

    private static ObjectNode httpSnapshot() {
        var result = snapshot();
        var publication = result.putObject("exposurePublication")
                .put("schemaVersion", GatewayCiliumReleaseArtifact.SCHEMA_VERSION).put("target", "CILIUM_GATEWAY_API")
                .put("gatewayClassName", "cilium").put("routeControllerName", "io.cilium/gateway-controller")
                .put("compilerVersion", "2").put("capabilityProfileId", GatewayCiliumReleaseArtifact.CAPABILITY_PROFILE_ID)
                .put("publicationDomainId", "public-apps").put("backendAuthorizationProfile", "oauth2-resource-server-v1");
        var entry = publication.putArray("exposures").addObject().put("exposureId", "810001")
                .put("routeId", "orders-read").put("contentSha256", "a".repeat(64))
                .put("expectedDesiredGeneration", "1").put("requestedState", "OPEN");
        entry.putArray("resourceRefs");
        entry.putArray("backendRefs");
        entry.putObject("routeObject").put("kind", "HTTPRoute").put("namespace", "yuheng-routes")
                .put("name", "yuheng-exposure-810001");
        return result;
    }

    private static ObjectNode activation(boolean http) {
        var result = JSON.createObjectNode().put("schemaVersion", GatewayCiliumReleaseArtifact.SCHEMA_VERSION)
                .put("releaseId", "820001");
        var entries = result.putArray("exposures");
        if (http) entries.addObject().put("exposureId", "810001").put("desiredGeneration", "1")
                .put("effectiveState", "OPEN").put("contentSha256", "a".repeat(64)).putNull("precondition");
        return result;
    }

    private static ObjectNode manifest(boolean http) {
        var result = JSON.createObjectNode().put("schemaVersion", GatewayCiliumReleaseArtifact.SCHEMA_VERSION)
                .put("compilerVersion", "2").put("capabilityProfileId", GatewayCiliumReleaseArtifact.CAPABILITY_PROFILE_ID);
        result.putArray("dependencies");
        var entries = result.putArray("resources");
        if (http) {
            var entry = entries.addObject().put("exposureId", "810001").put("contentSha256", "a".repeat(64));
            var route = entry.putObject("object").put("apiVersion", "gateway.networking.k8s.io/v1").put("kind", "HTTPRoute");
            route.putObject("metadata").put("namespace", "yuheng-routes").put("name", "yuheng-exposure-810001");
            route.putObject("spec").putArray("parentRefs");
            entry.putObject("approvedParent").put("group", "gateway.networking.k8s.io").put("kind", "Gateway")
                    .put("namespace", "gateway-system").put("name", "public-gateway").put("sectionName", "https-public");
        }
        return result;
    }

    private static ObjectNode mapping(String toolId) {
        var result = JSON.createObjectNode().put("toolId", toolId).put("dependencyClosureDigest", "c".repeat(64));
        result.putObject("sourceOperation").put("operationId", "701").put("operationKey", "orders.read")
                .put("definitionDigest", "a".repeat(64));
        result.putObject("siServer").put("resourceId", "901").put("versionId", "902").put("versionNo", "3")
                .put("digestSha256", "d".repeat(64)).put("bizCode", "commerce").put("namespaceCode", "orders")
                .put("envCode", "prod").put("ownerAppCode", "orders").put("resourceKey", "order-tools");
        var tool = result.putObject("tool").put("toolId", toolId).put("operationId", "701")
                .put("serverCode", "legacy-orders").put("name", "readOrders").put("description", "Read an order")
                .put("sourceType", "LOCAL_OPERATION").put("operationProtocol", "HTTP").putNull("remoteMountId")
                .put("inputSchema", "{\"type\":\"object\"}").put("outputSchema", "{\"type\":\"object\"}")
                .put("riskLevel", "LOW").put("idempotent", true).put("enabled", true);
        tool.putObject("annotations");
        tool.putArray("requiredPermissions").add("orders:read");
        return result;
    }

    private static ObjectNode section(ArrayNode mappings) {
        var payload = JSON.createObjectNode().put("schemaVersion", MCP_SCHEMA);
        payload.set("mappings", mappings);
        var result = payload.deepCopy();
        result.put("contentSha256", digest(payload));
        return result;
    }

    private static String digest(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sorted(value).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            var result = JSON.createObjectNode();
            fields.forEach((key, child) -> result.set(key, sorted(child)));
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode();
            value.forEach(child -> result.add(sorted(child)));
            return result;
        }
        return value.deepCopy();
    }
}
