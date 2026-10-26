package top.egon.cola.component.yuheng.admin.release.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.util.Map;

/** 发布差异的固定 JSON 字段。 / Fixed JSON fields of the native release diff. */
public record GatewayReleaseDiffVO(@PermissionResource(key = "yuheng.field.GatewayReleaseDiffVO.basedOnReleaseId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.basedOnReleaseId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String basedOnReleaseId, @PermissionResource(key = "yuheng.field.GatewayReleaseDiffVO.rollbackOfReleaseId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.rollbackOfReleaseId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String rollbackOfReleaseId, @PermissionResource(key = "yuheng.field.GatewayReleaseDiffVO.routeCount", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.routeCount",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    Integer routeCount,
        @PermissionResource(key = "yuheng.field.GatewayReleaseDiffVO.operationCount", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationCount",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        Integer operationCount, @PermissionResource(key = "yuheng.field.GatewayReleaseDiffVO.policyCount", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.policyCount",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    Integer policyCount, @PermissionResource(key = "yuheng.field.GatewayReleaseDiffVO.mcpServerCount", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.mcpServerCount",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    Integer mcpServerCount, @PermissionResource(key = "yuheng.field.GatewayReleaseDiffVO.mcpToolCount", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.mcpToolCount",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    Integer mcpToolCount, @PermissionResource(key = "yuheng.field.GatewayReleaseDiffVO.ruleContentSha256", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.ruleContentSha256",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String ruleContentSha256) {
    public static GatewayReleaseDiffVO from(Map<String, Object> value) {
        return new GatewayReleaseDiffVO((String) value.get("basedOnReleaseId"), (String) value.get("rollbackOfReleaseId"),
                number(value, "routeCount"), number(value, "operationCount"), number(value, "policyCount"),
                number(value, "mcpServerCount"), number(value, "mcpToolCount"), (String) value.get("ruleContentSha256"));
    }

    private static Integer number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        return value == null ? null : Math.toIntExact(((Number) value).longValue());
    }
}
