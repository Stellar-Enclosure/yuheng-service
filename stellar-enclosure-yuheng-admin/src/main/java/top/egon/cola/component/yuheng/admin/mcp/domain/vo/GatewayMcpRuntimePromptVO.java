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
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimePrompt;

/** 管理面自有协议预览投影。 / Admin-owned projection of the runtime protocol document. */
public record GatewayMcpRuntimePromptVO(
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.promptId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.promptId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String promptId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.serverCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.name", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.name",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String name,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.description", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.description",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String description,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.sourceType", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.sourceType",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String sourceType,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.template", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.template",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String template,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.operationId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String operationId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.remoteMountId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.remoteMountId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String remoteMountId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.arguments", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.arguments",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<String> arguments,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.requiredPermissions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.requiredPermissions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> requiredPermissions,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimePromptVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled
) {
    public static GatewayMcpRuntimePromptVO from(McpRuntimePrompt value) {
        if (value == null) { return null; }
        return new GatewayMcpRuntimePromptVO(
                value.promptId(),
                value.serverCode(),
                value.name(),
                value.description(),
                value.sourceType(),
                value.template(),
                value.operationId(),
                value.remoteMountId(),
                value.arguments(),
                value.requiredPermissions(),
                value.enabled()
        );
    }
}
