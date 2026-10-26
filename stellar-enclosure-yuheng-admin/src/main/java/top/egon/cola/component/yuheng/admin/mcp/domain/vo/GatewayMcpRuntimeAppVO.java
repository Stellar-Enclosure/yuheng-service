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
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeApp;

/** 管理面自有协议预览投影。 / Admin-owned projection of the runtime protocol document. */
public record GatewayMcpRuntimeAppVO(
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.appId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.appId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String appId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.serverCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.name", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.name",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String name,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.appCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.appCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String appCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.version", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.version",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String version,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.resourceUri", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.resourceUri",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String resourceUri,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.artifactId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.artifactId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String artifactId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.artifactReference", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.artifactReference",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String artifactReference,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.artifactSha256", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.artifactSha256",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String artifactSha256,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.artifactSizeBytes", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.artifactSizeBytes",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        long artifactSizeBytes,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.mimeType", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.mimeType",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String mimeType,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.contentSecurityPolicy", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.contentSecurityPolicy",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String contentSecurityPolicy,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.permissions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.permissions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> permissions,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.allowedOrigins", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.allowedOrigins",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> allowedOrigins,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.allowedTools", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.allowedTools",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> allowedTools,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeAppVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled
) {
    public static GatewayMcpRuntimeAppVO from(McpRuntimeApp value) {
        if (value == null) { return null; }
        return new GatewayMcpRuntimeAppVO(
                value.appId(),
                value.serverCode(),
                value.name(),
                value.appCode(),
                value.version(),
                value.resourceUri(),
                value.artifactId(),
                value.artifactReference(),
                value.artifactSha256(),
                value.artifactSizeBytes(),
                value.mimeType(),
                value.contentSecurityPolicy(),
                value.permissions(),
                value.allowedOrigins(),
                value.allowedTools(),
                value.enabled()
        );
    }
}
