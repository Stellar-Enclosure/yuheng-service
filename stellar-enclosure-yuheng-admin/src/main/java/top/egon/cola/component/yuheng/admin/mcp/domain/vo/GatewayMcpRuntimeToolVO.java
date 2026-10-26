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
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;

/** 管理面自有协议预览投影。 / Admin-owned projection of the runtime protocol document. */
public record GatewayMcpRuntimeToolVO(
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.toolId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.toolId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String toolId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.serverCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.name", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.name",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String name,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.description", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.description",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String description,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.sourceType", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.sourceType",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String sourceType,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.operationId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String operationId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.operationProtocol", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationProtocol",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String operationProtocol,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.remoteMountId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.remoteMountId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String remoteMountId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.inputSchema", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.inputSchema",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String inputSchema,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.outputSchema", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.outputSchema",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String outputSchema,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.annotations", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.annotations",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Map<String, String> annotations,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.requiredPermissions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.requiredPermissions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> requiredPermissions,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.riskLevel", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.riskLevel",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String riskLevel,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.idempotent", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.idempotent",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean idempotent,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeToolVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled
) {
    public static GatewayMcpRuntimeToolVO from(McpRuntimeTool value) {
        if (value == null) { return null; }
        return new GatewayMcpRuntimeToolVO(
                value.toolId(),
                value.serverCode(),
                value.name(),
                value.description(),
                value.sourceType(),
                value.operationId(),
                value.operationProtocol(),
                value.remoteMountId(),
                value.inputSchema(),
                value.outputSchema(),
                value.annotations(),
                value.requiredPermissions(),
                value.riskLevel(),
                value.idempotent(),
                value.enabled()
        );
    }
}
