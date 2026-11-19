package top.egon.cola.component.yuheng.admin.mcp.domain.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.*;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;

/** Target-scoped projection of one reviewed successful native release, not a new release or SI version. */
public record McpMappingPublicationEvidenceVO(@NotNull JsonNode document) {
    public static final String SCHEMA_VERSION = "yuheng-mcp-mapping-publication-v1";
    private static final Set<String> FIELDS = Set.of("schemaVersion", "publicationId", "ownerTenantId",
        "gatewayGroupId", "sourceRelease", "publishedSi", "mappings", "contentSha256", "artifactSha256");
    private static final Set<String> SERVER = Set.of("resourceId", "versionId", "versionNo", "digestSha256", "bizCode",
        "namespaceCode", "envCode", "ownerAppCode", "resourceKey");
    public McpMappingPublicationEvidenceVO {
        document = McpMappingSeedArtifactVO.canonical(document);
        fields(document, FIELDS);
        equal(document, "schemaVersion", SCHEMA_VERSION);
        String release = positive(document, "publicationId");
        positive(document, "ownerTenantId");
        positive(document, "gatewayGroupId");
        JsonNode source = document.path("sourceRelease");
        fields(source, Set.of("releaseId", "artifactSha256", "mappingContentSha256"));
        equal(source, "releaseId", release);
        digest(source, "artifactSha256");
        digest(source, "mappingContentSha256");
        JsonNode server = document.path("publishedSi");
        fields(server, SERVER);
        validateServer(server);
        JsonNode mappings = document.path("mappings");
        if (!mappings.isArray() || mappings.isEmpty() || mappings.size() > 1000)
            throw invalid();
        JsonNode checked = GatewayCiliumReleaseArtifact.mcpPublication(mappings);
        if (!checked.path("mappings").equals(mappings))
            throw invalid();
        for (JsonNode mapping : mappings) {
            if (!canonicalServer(mapping.path("siServer")).equals(server))
                throw invalid();
            if (!text(server, "resourceKey").equals(text(mapping.path("tool"), "serverCode")))
                throw invalid();
            String stable = McpMappingSeedArtifactVO.sha256(
                (text(server, "resourceKey") + '\0' + text(mapping.path("sourceOperation"), "operationKey"))
                    .getBytes(StandardCharsets.UTF_8));
            if (!stable.equals(text(mapping, "toolId")))
                throw invalid();
        }
        if (!digest(document, "contentSha256").equals(hash(content(document))))
            throw invalid();
        ObjectNode payload = ((ObjectNode) document).deepCopy();
        payload.remove("artifactSha256");
        if (!digest(document, "artifactSha256").equals(hash(payload))
            || McpMappingSeedArtifactVO.bytes(document).length > McpMappingSeedArtifactVO.MAX_BYTES)
            throw invalid();
    }
    @Override
    public JsonNode document() {
        return document.deepCopy();
    }
    public byte[] canonicalBytes() {
        return McpMappingSeedArtifactVO.bytes(document);
    }
    public String publicationId() {
        return document.path("publicationId").textValue();
    }
    public String contentSha256() {
        return document.path("contentSha256").textValue();
    }
    public String artifactSha256() {
        return document.path("artifactSha256").textValue();
    }
    public static McpMappingPublicationEvidenceVO create(
        GatewayCiliumReleaseArtifact source, JsonNode selectedMappings) {
        if (source == null || !source.hasMcpPublication() || selectedMappings == null || !selectedMappings.isArray()
            || selectedMappings.isEmpty())
            throw invalid();
        ObjectNode out = JsonNodeFactory.instance.objectNode()
                             .put("schemaVersion", SCHEMA_VERSION)
                             .put("publicationId", source.releaseId())
                             .put("ownerTenantId", source.ownerTenantId())
                             .put("gatewayGroupId", source.gatewayGroupId());
        out.putObject("sourceRelease")
            .put("releaseId", source.releaseId())
            .put("artifactSha256", source.artifactSha256())
            .put("mappingContentSha256", source.mcpPublication().path("contentSha256").textValue());
        out.set("publishedSi", canonicalServer(selectedMappings.get(0).path("siServer")));
        out.set("mappings", McpMappingSeedArtifactVO.canonical(selectedMappings));
        out.put("contentSha256", hash(content(out)));
        out.put("artifactSha256", hash(out));
        return new McpMappingPublicationEvidenceVO(out);
    }
    public static JsonNode canonicalServer(JsonNode server) {
        if (!server.isObject())
            throw invalid();
        ObjectNode copy = ((ObjectNode) server).deepCopy();
        if (!copy.has("ownerAppCode"))
            copy.putNull("ownerAppCode");
        fields(copy, SERVER);
        validateServer(copy);
        return McpMappingSeedArtifactVO.canonical(copy);
    }
    private static void validateServer(JsonNode n) {
        new McpApiMappingContentBO.SiServerVersionVO(positive(n, "resourceId"), positive(n, "versionId"),
            positive(n, "versionNo"), digest(n, "digestSha256"), text(n, "bizCode"), text(n, "namespaceCode"),
            text(n, "envCode"), n.path("ownerAppCode").isNull() ? null : text(n, "ownerAppCode"),
            text(n, "resourceKey"));
    }
    private static JsonNode content(JsonNode n) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        for (String name : List.of("schemaVersion", "ownerTenantId", "gatewayGroupId", "publishedSi", "mappings"))
            payload.set(name, n.path(name));
        return payload;
    }
    private static String hash(JsonNode n) {
        return McpMappingSeedArtifactVO.sha256(McpMappingSeedArtifactVO.bytes(n));
    }
    private static void fields(JsonNode n, Set<String> expected) {
        if (!n.isObject())
            throw invalid();
        Set<String> actual = new HashSet<>();
        n.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected))
            throw invalid();
    }
    private static String text(JsonNode n, String k) {
        JsonNode v = n.path(k);
        if (!v.isTextual() || v.textValue().isBlank() || v.textValue().length() > 1024
            || !v.textValue().equals(v.textValue().strip())
            || v.textValue().codePoints().anyMatch(Character::isISOControl))
            throw invalid();
        return v.textValue();
    }
    private static String positive(JsonNode n, String k) {
        String value = text(n, k);
        if (!value.matches("[1-9][0-9]{0,18}"))
            throw invalid();
        try {
            Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw invalid();
        }
        return value;
    }
    private static String digest(JsonNode n, String k) {
        String value = text(n, k);
        if (!value.matches("[0-9a-f]{64}"))
            throw invalid();
        return value;
    }
    private static void equal(JsonNode n, String k, String expected) {
        if (!expected.equals(text(n, k)))
            throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("YUHENG_MCP_MAPPING_PUBLICATION_INVALID");
    }
}
