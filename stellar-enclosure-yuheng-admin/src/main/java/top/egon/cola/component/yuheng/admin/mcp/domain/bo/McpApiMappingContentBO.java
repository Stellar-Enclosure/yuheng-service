package top.egon.cola.component.yuheng.admin.mcp.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The editable intent for projecting one registered operation against one exact published SI version. */
public record McpApiMappingContentBO(
        @NotNull @Valid SourceOperationVO sourceOperation,
        @NotNull @Valid SiServerVersionVO siServer,
        @NotNull Set<@NotBlank String> additionalPermissions,
        String minimumRiskLevel,
        boolean disabled
) {
    public static final String POLICY_TYPE = "MCP_API_MAPPING";
    public static final String POLICY_SCOPE = "OPERATION";
    public static final String INVALID = "YUHENG_MCP_API_MAPPING_INVALID";

    private static final Set<String> CONTENT_FIELDS = Set.of(
            "sourceOperation", "siServer", "additionalPermissions", "minimumRiskLevel", "disabled");
    private static final Set<String> SOURCE_FIELDS = Set.of("operationId", "operationKey", "definitionDigest");
    private static final Set<String> SERVER_FIELDS = Set.of(
            "resourceId", "versionId", "versionNo", "digestSha256", "bizCode", "namespaceCode",
            "envCode", "ownerAppCode", "resourceKey");
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    public McpApiMappingContentBO {
        if (sourceOperation == null || siServer == null || additionalPermissions == null) {
            throw invalid();
        }
        for (String permission : additionalPermissions) {
            checkedText(permission);
        }
        additionalPermissions = Set.copyOf(additionalPermissions);
        if (minimumRiskLevel != null && !RISK_LEVELS.contains(minimumRiskLevel)) {
            throw invalid();
        }
    }

    /** Reads the persisted JSON shape without numeric, Boolean or unknown-field coercion. */
    public static McpApiMappingContentBO fromContent(Map<String, Object> content) {
        Map<?, ?> input = checkedFields(content, CONTENT_FIELDS);
        Map<?, ?> source = checkedFields(input.get("sourceOperation"), SOURCE_FIELDS);
        Map<?, ?> server = checkedFields(input.get("siServer"), SERVER_FIELDS);
        if (!(input.get("additionalPermissions") instanceof List<?> entries)
                || !(input.get("disabled") instanceof Boolean disabled)) {
            throw invalid();
        }
        Set<String> permissions = new HashSet<>();
        for (Object permission : entries) {
            if (!permissions.add(checkedText(permission))) {
                throw invalid();
            }
        }
        return new McpApiMappingContentBO(
                new SourceOperationVO(text(source, "operationId"), text(source, "operationKey"),
                        text(source, "definitionDigest")),
                new SiServerVersionVO(text(server, "resourceId"), text(server, "versionId"),
                        text(server, "versionNo"), text(server, "digestSha256"), text(server, "bizCode"),
                        text(server, "namespaceCode"), text(server, "envCode"), optionalText(server, "ownerAppCode"),
                        text(server, "resourceKey")),
                permissions, optionalText(input, "minimumRiskLevel"), disabled);
    }

    /** Uses string IDs and a stable permission ordering in the policy JSON. */
    public Map<String, Object> content() {
        var content = new java.util.LinkedHashMap<String, Object>();
        content.put("sourceOperation", Map.of("operationId", sourceOperation.operationId(),
                "operationKey", sourceOperation.operationKey(), "definitionDigest", sourceOperation.definitionDigest()));
        content.put("siServer", siServer.content());
        content.put("additionalPermissions", additionalPermissions.stream().sorted().toList());
        if (minimumRiskLevel != null) {
            content.put("minimumRiskLevel", minimumRiskLevel);
        }
        content.put("disabled", disabled);
        return Map.copyOf(content);
    }

    public record SourceOperationVO(@NotBlank String operationId, @NotBlank String operationKey,
            @NotBlank String definitionDigest) {
        public SourceOperationVO {
            positiveId(operationId);
            checkedText(operationKey);
            digest(definitionDigest);
        }
    }

    public record SiServerVersionVO(@NotBlank String resourceId, @NotBlank String versionId,
            @NotBlank String versionNo, @NotBlank String digestSha256, @NotBlank String bizCode,
            @NotBlank String namespaceCode, @NotBlank String envCode, String ownerAppCode,
            @NotBlank String resourceKey) {
        public SiServerVersionVO {
            positiveId(resourceId);
            positiveId(versionId);
            positiveId(versionNo);
            digest(digestSha256);
            checkedText(bizCode);
            checkedText(namespaceCode);
            checkedText(envCode);
            checkedText(resourceKey);
            if (ownerAppCode != null) {
                checkedText(ownerAppCode);
            }
        }

        private Map<String, Object> content() {
            var content = new java.util.LinkedHashMap<String, Object>();
            content.put("resourceId", resourceId);
            content.put("versionId", versionId);
            content.put("versionNo", versionNo);
            content.put("digestSha256", digestSha256);
            content.put("bizCode", bizCode);
            content.put("namespaceCode", namespaceCode);
            content.put("envCode", envCode);
            if (ownerAppCode != null) {
                content.put("ownerAppCode", ownerAppCode);
            }
            content.put("resourceKey", resourceKey);
            return Map.copyOf(content);
        }
    }

    private static Map<?, ?> checkedFields(Object candidate, Set<String> fields) {
        if (!(candidate instanceof Map<?, ?> map) || !fields.containsAll(map.keySet())) {
            throw invalid();
        }
        return map;
    }

    private static String text(Map<?, ?> map, String key) {
        return checkedText(map.get(key));
    }

    private static String optionalText(Map<?, ?> map, String key) {
        return map.containsKey(key) ? text(map, key) : null;
    }

    private static String checkedText(Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return text;
    }

    private static void positiveId(String value) {
        if (!checkedText(value).matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            Long.parseLong(value);
        } catch (NumberFormatException outOfRange) {
            throw invalid();
        }
    }

    private static void digest(String value) {
        if (!checkedText(value).matches("[a-f0-9]{64}")) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
