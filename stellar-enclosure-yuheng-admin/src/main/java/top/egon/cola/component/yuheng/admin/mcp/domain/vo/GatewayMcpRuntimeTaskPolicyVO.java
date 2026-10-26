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
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTaskPolicy;

/** 管理面自有协议预览投影。 / Admin-owned projection of the runtime protocol document. */
public record GatewayMcpRuntimeTaskPolicyVO(
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.taskPolicyId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.taskPolicyId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String taskPolicyId,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.serverCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverCode,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.toolName", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.toolName",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String toolName,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.durable", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.durable",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean durable,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.inputAllowed", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.inputAllowed",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean inputAllowed,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.executionTimeoutSeconds", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.executionTimeoutSeconds",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        long executionTimeoutSeconds,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.resultTtlSeconds", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.resultTtlSeconds",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        long resultTtlSeconds,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.maxAttempts", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.maxAttempts",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        int maxAttempts,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuntimeTaskPolicyVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled
) {
    public static GatewayMcpRuntimeTaskPolicyVO from(McpRuntimeTaskPolicy value) {
        if (value == null) { return null; }
        return new GatewayMcpRuntimeTaskPolicyVO(
                value.taskPolicyId(),
                value.serverCode(),
                value.toolName(),
                value.durable(),
                value.inputAllowed(),
                value.executionTimeoutSeconds(),
                value.resultTtlSeconds(),
                value.maxAttempts(),
                value.enabled()
        );
    }
}
