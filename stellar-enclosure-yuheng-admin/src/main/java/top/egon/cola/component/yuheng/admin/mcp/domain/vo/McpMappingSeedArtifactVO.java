package top.egon.cola.component.yuheng.admin.mcp.domain.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Immutable, versioned evidence for composition; it conveys no SI execution version or Route authority. */
public record McpMappingSeedArtifactVO(@NotNull JsonNode document) {
    public static final String SCHEMA_VERSION = "yuheng-mcp-mapping-seed-v1";
    public static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> ROOT = Set.of("schemaVersion", "publicationId", "ownerTenantId", "gatewayGroupId",
        "sourceDraftRevision", "frozenAt", "registeredSi", "mappings", "contentSha256", "artifactSha256");
    private static final Set<String> IDENTITY = Set.of("registrationId", "resourceId", "bizCode", "namespaceCode",
        "envCode", "ownerAppCode", "resourceKey", "kind", "registrationRevision", "registrationDescriptorSha256");
    private static final Set<String> TOOL = Set.of("toolId", "serverCode", "name", "description", "sourceType",
        "operationId", "operationProtocol", "remoteMountId", "inputSchema", "outputSchema", "annotations",
        "requiredPermissions", "riskLevel", "idempotent", "enabled");
    public McpMappingSeedArtifactVO {
        document = canonical(document);
        fields(document, ROOT);
        equal(document, "schemaVersion", SCHEMA_VERSION);
        positive(document, "publicationId");
        positive(document, "ownerTenantId");
        positive(document, "gatewayGroupId");
        decimal(document, "sourceDraftRevision", true);
        String at = text(document, "frozenAt");
        try {
            if (!at.endsWith("Z"))
                throw invalid();
            Instant.parse(at);
        } catch (RuntimeException e) {
            throw invalid();
        }
        JsonNode identity = document.path("registeredSi");
        fields(identity, IDENTITY);
        positive(identity, "resourceId");
        decimal(identity, "registrationRevision", true);
        equal(identity, "kind", "MCP");
        for (String key : List.of("registrationId", "bizCode", "namespaceCode", "envCode", "resourceKey"))
            text(identity, key);
        if (!identity.path("ownerAppCode").isNull())
            text(identity, "ownerAppCode");
        digest(identity, "registrationDescriptorSha256");
        JsonNode entries = document.path("mappings");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > 200)
            throw invalid();
        String previous = null;
        Set<String> operations = new HashSet<>();
        for (JsonNode entry : entries) {
            fields(entry, Set.of("toolId", "sourceOperation", "tool"));
            String id = digest(entry, "toolId");
            if (previous != null && previous.compareTo(id) >= 0)
                throw invalid();
            previous = id;
            JsonNode source = entry.path("sourceOperation");
            fields(source, Set.of("operationId", "operationKey", "definitionDigest"));
            String operation = positive(source, "operationId");
            String key = text(source, "operationKey");
            digest(source, "definitionDigest");
            if (!operations.add(operation))
                throw invalid();
            JsonNode tool = entry.path("tool");
            if (!tool.isObject())
                throw invalid();
            tool.fieldNames().forEachRemaining(k -> {
                if (!TOOL.contains(k))
                    throw invalid();
            });
            equal(tool, "toolId", id);
            equal(tool, "operationId", operation);
            equal(tool, "serverCode", text(identity, "resourceKey"));
            equal(tool, "sourceType", "LOCAL_OPERATION");
            text(tool, "name");
            if (!sha256((text(identity, "resourceKey") + '\0' + key).getBytes(StandardCharsets.UTF_8)).equals(id))
                throw invalid();
            if (!Set.of("HTTP", "RPC").contains(text(tool, "operationProtocol"))
                || !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(text(tool, "riskLevel"))
                || !tool.path("idempotent").isBoolean() || !tool.path("enabled").isBoolean()
                || tool.hasNonNull("remoteMountId"))
                throw invalid();
            for (String k : List.of("description", "inputSchema", "outputSchema")) {
                if (tool.hasNonNull(k) && !tool.path(k).isTextual())
                    throw invalid();
            }
            if (!tool.path("annotations").isObject() || !tool.path("annotations").isEmpty())
                throw invalid();
            JsonNode permissions = tool.path("requiredPermissions");
            if (!permissions.isArray() || permissions.size() > 200)
                throw invalid();
            String prev = null;
            for (JsonNode permission : permissions) {
                if (!permission.isTextual()
                    || !permission.textValue().matches("^[a-z][a-z0-9._-]*(?::[A-Za-z0-9._*-]+)+$")
                    || prev != null && prev.compareTo(permission.textValue()) >= 0)
                    throw invalid();
                prev = permission.textValue();
            }
        }
        ObjectNode content = JsonNodeFactory.instance.objectNode().put("schemaVersion", SCHEMA_VERSION);
        content.set("registeredSi", identity);
        content.set("mappings", entries);
        if (!digest(document, "contentSha256").equals(sha256(bytes(content))))
            throw invalid();
        ObjectNode body = ((ObjectNode) document).deepCopy();
        body.remove("artifactSha256");
        if (!digest(document, "artifactSha256").equals(sha256(bytes(body))) || bytes(document).length > MAX_BYTES)
            throw invalid();
    }
    @Override
    public JsonNode document() {
        return document.deepCopy();
    }
    public byte[] canonicalBytes() {
        return bytes(document);
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
    public static McpMappingSeedArtifactVO create(String id, long owner, String group, long revision, Instant frozenAt,
        RegisteredIdentityVO registered, JsonNode mappings) {
        ObjectNode body = JsonNodeFactory.instance.objectNode()
                              .put("schemaVersion", SCHEMA_VERSION)
                              .put("publicationId", id)
                              .put("ownerTenantId", Long.toString(owner))
                              .put("gatewayGroupId", group)
                              .put("sourceDraftRevision", Long.toString(revision))
                              .put("frozenAt", frozenAt.toString());
        body.set("registeredSi", registered.evidence());
        body.set("mappings", canonical(mappings));
        ObjectNode content = JsonNodeFactory.instance.objectNode().put("schemaVersion", SCHEMA_VERSION);
        content.set("registeredSi", body.path("registeredSi"));
        content.set("mappings", body.path("mappings"));
        body.put("contentSha256", sha256(bytes(content)));
        body.put("artifactSha256", sha256(bytes(body)));
        return new McpMappingSeedArtifactVO(body);
    }
    /** A locally pinned identity receipt, never a versioned executable resource. */
    public record RegisteredIdentityVO(@NotBlank String registrationId, @Positive long ownerTenantId,
        @NotBlank String gatewayGroupId, String resourceId, String bizCode, String namespaceCode, String envCode,
        String ownerAppCode, String resourceKey, @NotBlank String registrationRevision,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String registrationDescriptorSha256) {
        public RegisteredIdentityVO {
            if (ownerTenantId <= 0)
                throw invalid();
            for (String s : List.of(registrationId, gatewayGroupId, resourceId, bizCode, namespaceCode, envCode,
                     resourceKey, registrationRevision, registrationDescriptorSha256)) {
                if (s == null || s.isBlank() || s.length() > 1024 || !s.equals(s.strip())
                    || s.codePoints().anyMatch(Character::isISOControl))
                    throw invalid();
            }
            if (ownerAppCode != null
                && (ownerAppCode.isBlank() || ownerAppCode.length() > 128 || !ownerAppCode.equals(ownerAppCode.strip())
                    || ownerAppCode.codePoints().anyMatch(Character::isISOControl)))
                throw invalid();
        }
        public JsonNode evidence() {
            ObjectNode n = JsonNodeFactory.instance.objectNode()
                               .put("registrationId", registrationId)
                               .put("resourceId", resourceId)
                               .put("bizCode", bizCode)
                               .put("namespaceCode", namespaceCode)
                               .put("envCode", envCode)
                               .put("resourceKey", resourceKey)
                               .put("kind", "MCP")
                               .put("registrationRevision", registrationRevision)
                               .put("registrationDescriptorSha256", registrationDescriptorSha256);
            if (ownerAppCode == null)
                n.putNull("ownerAppCode");
            else
                n.put("ownerAppCode", ownerAppCode);
            return n;
        }
    }
    public static JsonNode canonical(JsonNode n) {
        return normalize(n, 0, new int[] {0});
    }
    public static byte[] bytes(JsonNode n) {
        return canonical(n).toString().getBytes(StandardCharsets.UTF_8);
    }
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }
    private static JsonNode normalize(JsonNode n, int depth, int[] count) {
        if (n == null || n.isMissingNode() || n.isPojo() || n.isBinary() || depth > 32 || ++count[0] > 100000)
            throw invalid();
        if (n.isObject()) {
            ObjectNode r = JsonNodeFactory.instance.objectNode();
            TreeSet<String> keys = new TreeSet<>();
            n.fieldNames().forEachRemaining(keys::add);
            keys.forEach(k -> r.set(k, normalize(n.path(k), depth + 1, count)));
            return r;
        }
        if (n.isArray()) {
            ArrayNode r = JsonNodeFactory.instance.arrayNode();
            n.forEach(v -> r.add(normalize(v, depth + 1, count)));
            return r;
        }
        if (n.isNumber()) {
            if (n.isFloatingPointNumber() && !Double.isFinite(n.doubleValue()))
                throw invalid();
            return JsonNodeFactory.instance.numberNode(n.decimalValue().stripTrailingZeros());
        }
        return n.deepCopy();
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
    private static String decimal(JsonNode n, String k, boolean zero) {
        String s = text(n, k);
        if (!s.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}"))
            throw invalid();
        try {
            Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw invalid();
        }
        return s;
    }
    private static String positive(JsonNode n, String k) {
        return decimal(n, k, false);
    }
    private static String digest(JsonNode n, String k) {
        String s = text(n, k);
        if (!s.matches("[0-9a-f]{64}"))
            throw invalid();
        return s;
    }
    private static void equal(JsonNode n, String k, String v) {
        if (!v.equals(text(n, k)))
            throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("YUHENG_MCP_MAPPING_SEED_INVALID");
    }
}
