package top.egon.cola.component.yuheng.admin.mcp.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeRemoteMount;

/** 管理面自有协议预览投影。 / Admin-owned projection of the runtime protocol document. */
public record GatewayMcpRuntimeRemoteMountVO(
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.mountId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.mountId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String mountId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.serverCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.providerCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.providerCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String providerCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.namespace", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.namespace",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String namespace,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.primitiveTypes", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.primitiveTypes",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> primitiveTypes,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.renameRules", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.renameRules",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Map<String, String> renameRules,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.conflictPolicy", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.conflictPolicy",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String conflictPolicy,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.requiredPermissions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.requiredPermissions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> requiredPermissions,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.capabilityFingerprint", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.capabilityFingerprint",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String capabilityFingerprint,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteMountVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled
) {
    public static GatewayMcpRuntimeRemoteMountVO from(McpRuntimeRemoteMount value) {
        if (value == null) { return null; }
        return new GatewayMcpRuntimeRemoteMountVO(
                value.mountId(),
                value.serverCode(),
                value.providerCode(),
                value.namespace(),
                value.primitiveTypes(),
                value.renameRules(),
                value.conflictPolicy(),
                value.requiredPermissions(),
                value.capabilityFingerprint(),
                value.enabled()
        );
    }
}
