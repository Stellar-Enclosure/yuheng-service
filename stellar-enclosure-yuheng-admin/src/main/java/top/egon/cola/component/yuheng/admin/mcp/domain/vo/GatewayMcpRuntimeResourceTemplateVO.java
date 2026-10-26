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
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeResourceTemplate;

/** 管理面自有协议预览投影。 / Admin-owned projection of the runtime protocol document. */
public record GatewayMcpRuntimeResourceTemplateVO(
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.templateId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.templateId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String templateId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.serverCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.name", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.name",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String name,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.uriTemplate", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.uriTemplate",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String uriTemplate,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.description", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.description",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String description,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.mimeType", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.mimeType",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String mimeType,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.driverType", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.driverType",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String driverType,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.operationId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String operationId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.remoteMountId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.remoteMountId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String remoteMountId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.configuration", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.configuration",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Map<String, String> configuration,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.requiredPermissions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.requiredPermissions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> requiredPermissions,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.maxBytes", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.maxBytes",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        long maxBytes,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeResourceTemplateVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled
) {
    public static GatewayMcpRuntimeResourceTemplateVO from(McpRuntimeResourceTemplate value) {
        if (value == null) { return null; }
        return new GatewayMcpRuntimeResourceTemplateVO(
                value.templateId(),
                value.serverCode(),
                value.name(),
                value.uriTemplate(),
                value.description(),
                value.mimeType(),
                value.driverType(),
                value.operationId(),
                value.remoteMountId(),
                value.configuration(),
                value.requiredPermissions(),
                value.maxBytes(),
                value.enabled()
        );
    }
}
