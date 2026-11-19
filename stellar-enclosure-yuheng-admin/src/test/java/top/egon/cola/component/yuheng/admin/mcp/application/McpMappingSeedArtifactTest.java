package top.egon.cola.component.yuheng.admin.mcp.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpMappingSeedFreezeCommand;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO.RegisteredIdentityVO;
import top.egon.cola.component.yuheng.admin.mcp.service.McpReleaseContentFactory;

public class McpMappingSeedArtifactTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");
    static final String KEY = "http:orders:GET:/orders/{id}";
    static RegisteredIdentityVO identity() {
        return new RegisteredIdentityVO(
            "orders-registration", 7, "10", "201", "commerce", "default", "test", null, "orders", "0", "e".repeat(64));
    }
    public static McpMappingSeedFreezeCommand command() {
        return new McpMappingSeedFreezeCommand("3", "orders-registration",
            List.of(new McpMappingSeedFreezeCommand.MappingInputVO(
                "101", KEY, "a".repeat(64), Set.of("mcp:orders:audit"), "HIGH", false)),
            "Approve first composition");
    }
    static JsonNode mappings() {
        String id =
            McpMappingSeedArtifactVO.sha256(("orders" + '\0' + KEY).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ObjectNode entry = JSON.createObjectNode().put("toolId", id);
        entry.set("sourceOperation", JSON.valueToTree(command().mappings().getFirst().source()));
        ObjectNode tool = entry.putObject("tool")
                              .put("toolId", id)
                              .put("serverCode", "orders")
                              .put("name", "orders.get")
                              .put("sourceType", "LOCAL_OPERATION")
                              .put("operationId", "101")
                              .put("operationProtocol", "HTTP")
                              .put("riskLevel", "HIGH")
                              .put("idempotent", true)
                              .put("enabled", true);
        tool.putObject("annotations");
        tool.putArray("requiredPermissions").add("mcp:orders:audit").add("mcp:orders:read");
        tool.put("inputSchema", "{\"type\":\"object\"}");
        tool.put("outputSchema", "{\"type\":\"object\"}");
        return JSON.createArrayNode().add(entry);
    }
    public static McpMappingSeedArtifactVO artifact() {
        return McpMappingSeedArtifactVO.create("301", 7, "10", 3, NOW, identity(), mappings());
    }
    public static ObjectNode descriptor() {
        ObjectNode root = JSON.createObjectNode()
                              .put("schemaVersion", "tianshu-si-registration-descriptors-v1")
                              .put("authority", "OPERATOR_VERIFIED_TIANSHU_ADMIN");
        ObjectNode n = root.putArray("registrations").addObject();
        n.setAll((ObjectNode) identity().evidence());
        n.remove("registrationDescriptorSha256");
        n.put("ownerTenantId", "7").put("gatewayGroupId", "10").put("lifecycleStatus", "DRAFT");
        return root;
    }
    @Test
    void registeredIdentityEvidenceHasAnIndependentArtifactContract() {
        assertThat(McpMappingSeedArtifactVO.SCHEMA_VERSION).isEqualTo("yuheng-mcp-mapping-seed-v1");
        assertThat(artifact().document().path("registeredSi").has("versionNo")).isFalse();
        assertThat(artifact().document().has("exposurePublication")).isFalse();
    }
    @Test
    void compilerSupportsRegisteredIdentityWithoutRelaxingPublishedVersionInputs() {
        assertThat(Arrays.stream(McpReleaseContentFactory.class.getMethods())
                       .anyMatch(m -> m.getName().equals("compileRegisteredIdentityMappings")))
            .isTrue();
    }
    @Test
    void canonicalBytesHaveStableDigestsAndNoMutableSnapshotLeakage() throws Exception {
        var artifact = artifact();
        byte[] bytes = artifact.canonicalBytes();
        JsonNode copy = artifact.document();
        ((ObjectNode) copy).put("publicationId", "999");
        bytes[0] = '!';
        assertThat(artifact.publicationId()).isEqualTo("301");
        assertThat(new McpMappingSeedArtifactVO(JSON.readTree(artifact.canonicalBytes())).canonicalBytes())
            .isEqualTo(artifact.canonicalBytes());
        ObjectNode body = (ObjectNode) artifact.document();
        String embedded = body.remove("artifactSha256").textValue();
        assertThat(McpMappingSeedArtifactVO.sha256(McpMappingSeedArtifactVO.bytes(body))).isEqualTo(embedded);
    }
    @Test
    void routeOrFakeExecutionVersionCannotEnterASeedEvenWhenCallerRehashes() {
        for (String field :
            List.of("exposurePublication", "activationContent", "versionNo", "dependencyClosureDigest")) {
            ObjectNode n = (ObjectNode) artifact().document();
            n.put(field, "1");
            assertThatIllegalArgumentException().isThrownBy(() -> new McpMappingSeedArtifactVO(n));
        }
        ObjectNode n = (ObjectNode) artifact().document();
        ((ObjectNode) n.path("registeredSi")).put("versionNo", "1");
        assertThatIllegalArgumentException().isThrownBy(() -> new McpMappingSeedArtifactVO(n));
    }
    @Test
    void tamperWrongStableToolIdAndDuplicateMappingsAreRejected() {
        ObjectNode n = (ObjectNode) artifact().document();
        ((ObjectNode) n.path("mappings").get(0).path("sourceOperation")).put("definitionDigest", "b".repeat(64));
        assertThatIllegalArgumentException().isThrownBy(() -> new McpMappingSeedArtifactVO(n));
        assertThatIllegalArgumentException().isThrownBy(
            ()
                -> McpMappingSeedArtifactVO.create("301", 7, "10", 3, NOW, identity(),
                    JSON.createArrayNode().add(mappings().get(0)).add(mappings().get(0))));
    }
    @Test
    void permissionOrderIsNotAnIdempotencyOrArtifactDifference() throws Exception {
        var input = command().mappings().getFirst();
        var a = new McpMappingSeedFreezeCommand.MappingInputVO(input.operationId(), input.operationKey(),
            input.definitionDigest(), new LinkedHashSet<>(List.of("mcp:orders:read", "mcp:orders:audit")), null, false);
        var b = new McpMappingSeedFreezeCommand.MappingInputVO(input.operationId(), input.operationKey(),
            input.definitionDigest(), new LinkedHashSet<>(List.of("mcp:orders:audit", "mcp:orders:read")), null, false);
        assertThat(McpMappingSeedArtifactVO.bytes(JSON.valueToTree(a)))
            .isEqualTo(McpMappingSeedArtifactVO.bytes(JSON.valueToTree(b)));
    }

    @Test
    void commandRejectsCoercionDuplicatesUnknownFieldsAndFakeTenant() throws Exception {
        String good = JSON.writeValueAsString(command());
        assertThat(JSON.readValue(good, McpMappingSeedFreezeCommand.class)).isEqualTo(command());
        for (String bad : List.of(good.replace("\"3\"", "3"),
                 good.replace("\"changeReason\":", "\"tenantId\":\"7\",\"changeReason\":"),
                 good.replace("\"registrationId\":", "\"registrationId\":\"first\",\"registrationId\":"),
                 good.replace("\"disabled\":false", "\"disabled\":\"false\""))) {
            assertThatThrownBy(() -> JSON.readValue(bad, McpMappingSeedFreezeCommand.class))
                .isInstanceOf(Exception.class);
        }
    }
}
