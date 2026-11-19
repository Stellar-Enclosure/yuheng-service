package top.egon.cola.component.yuheng.admin.mcp.domain.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.*;

/** Immutable operator intent; neither tenant nor SI version can be supplied by the request. */
@JsonDeserialize(using = McpMappingSeedFreezeCommand.StrictDeserializer.class)
public record McpMappingSeedFreezeCommand(
    @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.expectedDraftRevision",
        type = ResourceType.FIELD, labelPage = "gateway.permissions",
        labelKey = "field.mappingSeed.expectedDraftRevision",
        allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
        oauthScopes = GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
        write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) @NotBlank
    @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedDraftRevision,
    @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.registrationId", type = ResourceType.FIELD,
        labelPage = "gateway.permissions", labelKey = "field.mappingSeed.registrationId",
        allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
        oauthScopes = GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
        write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) @NotBlank @Size(max = 128)
    String registrationId,
    @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.mappings", type = ResourceType.FIELD,
        labelPage = "gateway.permissions", labelKey = "field.mappingSeed.mappings",
        allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
        oauthScopes = GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
        write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) @NotNull @Size(min = 1,
        max = 200) List<@Valid MappingInputVO> mappings,
    @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.changeReason", type = ResourceType.FIELD,
        labelPage = "gateway.permissions", labelKey = "field.mappingSeed.changeReason",
        allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
        oauthScopes = GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
        write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) @NotBlank @Size(max = 1024)
    String changeReason) {
    public static final String INVALID = "YUHENG_MCP_MAPPING_SEED_INVALID";
    public McpMappingSeedFreezeCommand {
        decimal(expectedDraftRevision, true);
        text(registrationId, 128);
        text(changeReason, 1024);
        if (mappings == null || mappings.isEmpty() || mappings.size() > 200
            || mappings.stream().anyMatch(Objects::isNull))
            throw invalid();
        mappings = mappings.stream().sorted(Comparator.comparing(MappingInputVO::operationId)).toList();
    }
    public record MappingInputVO(
        @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.MappingInputVO.operationId",
            type = ResourceType.FIELD, labelPage = "gateway.permissions", labelKey = "field.mappingSeed.operationId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes =
                GatewayPermissionCodes.MCP_WRITE) @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}") String operationId,
        @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.MappingInputVO.operationKey",
            type = ResourceType.FIELD, labelPage = "gateway.permissions", labelKey = "field.mappingSeed.operationKey",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes =
                GatewayPermissionCodes.MCP_WRITE) @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) @NotBlank @Size(max = 1024)
        String operationKey,
        @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.MappingInputVO.definitionDigest",
            type = ResourceType.FIELD, labelPage = "gateway.permissions",
            labelKey = "field.mappingSeed.definitionDigest",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes =
                GatewayPermissionCodes.MCP_WRITE) @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) @NotBlank
        @Pattern(regexp = "[0-9a-f]{64}") String definitionDigest,
        @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.MappingInputVO.additionalPermissions",
            type = ResourceType.FIELD, labelPage = "gateway.permissions",
            labelKey = "field.mappingSeed.additionalPermissions",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes =
                GatewayPermissionCodes.MCP_WRITE) @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) @NotNull @Size(max = 100)
        Set<@NotBlank String> additionalPermissions,
        @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.MappingInputVO.minimumRiskLevel",
            type = ResourceType.FIELD, labelPage = "gateway.permissions",
            labelKey = "field.mappingSeed.minimumRiskLevel",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes =
                GatewayPermissionCodes.MCP_WRITE) @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE))
        @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String minimumRiskLevel,
        @PermissionResource(key = "yuheng.field.McpMappingSeedFreezeCommand.MappingInputVO.disabled",
            type = ResourceType.FIELD, labelPage = "gateway.permissions", labelKey = "field.mappingSeed.disabled",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.MCP_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            write = @PermissionGroup(codes = GatewayPermissionCodes.MCP_WRITE)) boolean disabled) {
        public MappingInputVO {
            new McpApiMappingContentBO.SourceOperationVO(operationId, operationKey, definitionDigest);
            if (additionalPermissions == null || additionalPermissions.size() > 100)
                throw invalid();
            additionalPermissions.forEach(value -> {
                text(value, 256);
                if (!value.matches("^[a-z][a-z0-9._-]*(?::[A-Za-z0-9._*-]+)+$"))
                    throw invalid();
            });
            additionalPermissions =
                Collections.unmodifiableSet(new LinkedHashSet<>(additionalPermissions.stream().sorted().toList()));
            if (minimumRiskLevel != null && !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(minimumRiskLevel))
                throw invalid();
        }
        public McpApiMappingContentBO.SourceOperationVO source() {
            return new McpApiMappingContentBO.SourceOperationVO(operationId, operationKey, definitionDigest);
        }
    }
    private static void text(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max || !s.equals(s.strip())
            || s.codePoints().anyMatch(Character::isISOControl))
            throw invalid();
    }
    private static void decimal(String s, boolean zero) {
        text(s, 19);
        if (!s.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}"))
            throw invalid();
        try {
            Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
    /** Denies coercion, duplicate keys and unknown properties before the command reaches the service. */
    public static final class StrictDeserializer extends JsonDeserializer<McpMappingSeedFreezeCommand> {
        @Override
        public McpMappingSeedFreezeCommand deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode n = parser.getCodec().readTree(parser);
            try {
                fields(n, Set.of("expectedDraftRevision", "registrationId", "mappings", "changeReason"));
                if (!n.path("mappings").isArray() || n.path("mappings").isEmpty() || n.path("mappings").size() > 200)
                    throw invalid();
                List<MappingInputVO> list = new ArrayList<>();
                for (JsonNode m : n.path("mappings")) {
                    fields(m,
                        Set.of("operationId", "operationKey", "definitionDigest", "additionalPermissions",
                            "minimumRiskLevel", "disabled"));
                    if (!m.path("additionalPermissions").isArray() || !m.path("disabled").isBoolean())
                        throw invalid();
                    Set<String> permissions = new HashSet<>();
                    for (JsonNode v : m.path("additionalPermissions")) {
                        if (!v.isTextual() || !permissions.add(v.textValue()))
                            throw invalid();
                    }
                    String risk = m.hasNonNull("minimumRiskLevel") ? string(m, "minimumRiskLevel") : null;
                    list.add(new MappingInputVO(string(m, "operationId"), string(m, "operationKey"),
                        string(m, "definitionDigest"), permissions, risk, m.path("disabled").booleanValue()));
                }
                return new McpMappingSeedFreezeCommand(
                    string(n, "expectedDraftRevision"), string(n, "registrationId"), list, string(n, "changeReason"));
            } catch (IllegalArgumentException e) {
                throw context.weirdStringException("", McpMappingSeedFreezeCommand.class, INVALID);
            }
        }
        private static void fields(JsonNode n, Set<String> allowed) {
            if (!n.isObject())
                throw invalid();
            n.fieldNames().forEachRemaining(k -> {
                if (!allowed.contains(k))
                    throw invalid();
            });
        }
        private static String string(JsonNode n, String key) {
            if (!n.path(key).isTextual())
                throw invalid();
            return n.path(key).textValue();
        }
    }
}
