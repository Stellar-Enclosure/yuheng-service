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
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeRemoteProvider;

/** 管理面自有协议预览投影。 / Admin-owned projection of the runtime protocol document. */
public record GatewayMcpRuntimeRemoteProviderVO(
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.providerId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.providerId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String providerId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.providerCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.providerCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String providerCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.displayName", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.displayName",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String displayName,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.dialect", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.dialect",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        McpProtocolDialect dialect,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.transportType", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.transportType",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String transportType,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.endpointReference", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.endpointReference",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String endpointReference,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.authProfileReference", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.authProfileReference",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String authProfileReference,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.tlsProfileReference", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.tlsProfileReference",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String tlsProfileReference,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.capabilityFingerprint", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.capabilityFingerprint",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String capabilityFingerprint,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeRemoteProviderVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled
) {
    public static GatewayMcpRuntimeRemoteProviderVO from(McpRuntimeRemoteProvider value) {
        if (value == null) { return null; }
        return new GatewayMcpRuntimeRemoteProviderVO(
                value.providerId(),
                value.providerCode(),
                value.displayName(),
                value.dialect(),
                value.transportType(),
                value.endpointReference(),
                value.authProfileReference(),
                value.tlsProfileReference(),
                value.capabilityFingerprint(),
                value.enabled()
        );
    }
}
