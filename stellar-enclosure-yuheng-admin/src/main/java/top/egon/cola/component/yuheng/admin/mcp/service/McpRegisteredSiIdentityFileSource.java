package top.egon.cola.component.yuheng.admin.mcp.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpMappingSeedArtifactVO.RegisteredIdentityVO;

/** Deployment-owned registration attestations. A digest protects bytes; operator approval supplies provenance. */
@Slf4j
public final class McpRegisteredSiIdentityFileSource {
    public static final String SCHEMA_VERSION = "tianshu-si-registration-descriptors-v1";
    private final Map<String, RegisteredIdentityVO> identities;
    public McpRegisteredSiIdentityFileSource(
        Path file, String expectedSha256, long installationOwner, ObjectMapper mapper) {
        try {
            if (file == null || !file.isAbsolute() || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                || installationOwner <= 0 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > McpMappingSeedArtifactVO.MAX_BYTES)
                throw invalid();
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(McpMappingSeedArtifactVO.MAX_BYTES + 1);
            }
            if (bytes.length > McpMappingSeedArtifactVO.MAX_BYTES
                || !McpMappingSeedArtifactVO.sha256(bytes).equals(expectedSha256))
                throw invalid();
            ObjectReader reader = mapper.reader()
                                      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                      .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = reader.readTree(bytes);
            fields(root, Set.of("schemaVersion", "authority", "registrations"));
            if (!SCHEMA_VERSION.equals(string(root, "schemaVersion"))
                || !"OPERATOR_VERIFIED_TIANSHU_ADMIN".equals(string(root, "authority"))
                || !root.path("registrations").isArray() || root.path("registrations").isEmpty()
                || root.path("registrations").size() > 100)
                throw invalid();
            Map<String, RegisteredIdentityVO> verified = new HashMap<>();
            for (JsonNode n : root.path("registrations")) {
                fields(n,
                    Set.of("registrationId", "ownerTenantId", "gatewayGroupId", "resourceId", "bizCode",
                        "namespaceCode", "envCode", "ownerAppCode", "resourceKey", "kind", "registrationRevision",
                        "lifecycleStatus"));
                if (!"MCP".equals(string(n, "kind"))
                    || !Set.of("DRAFT", "PUBLISHED", "DEPRECATED").contains(string(n, "lifecycleStatus"))
                    || number(n, "ownerTenantId", false) != installationOwner)
                    throw invalid();
                number(n, "gatewayGroupId", false);
                number(n, "resourceId", false);
                number(n, "registrationRevision", true);
                String app = n.path("ownerAppCode").isNull() ? null : string(n, "ownerAppCode");
                var identity = new RegisteredIdentityVO(string(n, "registrationId"), installationOwner,
                    string(n, "gatewayGroupId"), string(n, "resourceId"), string(n, "bizCode"),
                    string(n, "namespaceCode"), string(n, "envCode"), app, string(n, "resourceKey"),
                    string(n, "registrationRevision"), expectedSha256);
                if (verified.putIfAbsent(identity.registrationId(), identity) != null)
                    throw invalid();
            }
            identities = Map.copyOf(verified);
        } catch (Exception failure) {
            throw invalid();
        }
    }
    public RegisteredIdentityVO require(long owner, String group, String registrationId) {
        RegisteredIdentityVO found = registrationId == null ? null : identities.get(registrationId);
        if (found == null || found.ownerTenantId() != owner || !found.gatewayGroupId().equals(group))
            throw new CommonException(403, "YUHENG_MCP_MAPPING_SEED_REGISTRATION_DENIED",
                "The registration is outside this publication scope");
        return found;
    }
    private static void fields(JsonNode n, Set<String> expected) {
        if (!n.isObject())
            throw invalid();
        Set<String> actual = new HashSet<>();
        n.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected))
            throw invalid();
    }
    private static String string(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (!v.isTextual() || v.textValue().isBlank() || v.textValue().length() > 128
            || !v.textValue().equals(v.textValue().strip())
            || v.textValue().codePoints().anyMatch(Character::isISOControl))
            throw invalid();
        return v.textValue();
    }
    private static long number(JsonNode n, String key, boolean zero) {
        String s = string(n, key);
        if (!s.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}"))
            throw invalid();
        return Long.parseLong(s);
    }
    private static IllegalStateException invalid() {
        return new IllegalStateException("YUHENG_MCP_MAPPING_SEED_REGISTRATION_UNAVAILABLE");
    }
}
