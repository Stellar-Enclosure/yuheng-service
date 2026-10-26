package top.egon.cola.component.yuheng.admin.openapi.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable OpenAPI snapshot response used for viewing or downloading JSON.
 *
 * <p>中文：Raw document 保存在响应中但不进入 {@link #toString()}，避免操作日志
 * 意外记录完整文档或其中的敏感扩展字段。</p>
 */
public record GatewayOpenApiDocumentVO(
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.snapshotId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.snapshotId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String snapshotId,
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.applicationId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.applicationId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String applicationId,
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.buildId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.buildId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String buildId,
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.openapiVersion", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.openapiVersion",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String openapiVersion,
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.documentSha256", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.documentSha256",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String documentSha256,
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.canonicalSha256", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.canonicalSha256",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String canonicalSha256,
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.document", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.document",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        Map<String, Object> document,
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.fetchedAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.fetchedAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant fetchedAt,
        @PermissionResource(key = "yuheng.field.GatewayOpenApiDocumentVO.validatedAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.validatedAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant validatedAt
) {

    private static final Pattern SHA256 = Pattern.compile(
            "^[0-9a-f]{64}$");

    /** Validates hashes and recursively freezes the raw JSON value. */
    public GatewayOpenApiDocumentVO {
        snapshotId = required(snapshotId, "snapshotId", 64);
        applicationId = required(applicationId, "applicationId", 64);
        buildId = required(buildId, "buildId", 256);
        openapiVersion = required(openapiVersion, "openapiVersion", 32);
        documentSha256 = hash(documentSha256, "documentSha256");
        canonicalSha256 = hash(canonicalSha256, "canonicalSha256");
        document = immutableObject(document, "document");
        fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt");
        validatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
    }

    @Override
    public String toString() {
        return "GatewayOpenApiDocumentVO{" +
                "snapshotId='" + snapshotId + '\'' +
                ", applicationId='" + applicationId + '\'' +
                ", buildId='" + buildId + '\'' +
                ", openapiVersion='" + openapiVersion + '\'' +
                ", documentSha256='" + documentSha256 + '\'' +
                ", canonicalSha256='" + canonicalSha256 + '\'' +
                ", fetchedAt=" + fetchedAt +
                ", validatedAt=" + validatedAt +
                '}';
    }

    private static String required(String value, String field, int max) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > max) {
            throw new IllegalArgumentException(
                    field + " exceeds " + max + " characters");
        }
        return normalized;
    }

    private static String hash(String value, String field) {
        String normalized = required(value, field, 64);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be lowercase hexadecimal SHA-256");
        }
        return normalized;
    }

    private static Map<String, Object> immutableObject(
            Map<String, Object> value,
            String field) {
        Objects.requireNonNull(value, field);
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> copy.put(
                required(key, field + " key", 256), freeze(item)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(
                    Objects.requireNonNull(key, "document key").toString(),
                    freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(GatewayOpenApiDocumentVO::freeze).toList();
        }
        return value;
    }
}
