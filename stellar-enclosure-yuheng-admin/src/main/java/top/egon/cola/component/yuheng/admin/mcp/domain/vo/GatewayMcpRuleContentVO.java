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
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;

/** 管理面自有协议预览投影。 / Admin-owned projection of the runtime protocol document. */
public record GatewayMcpRuleContentVO(
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.servers", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.servers",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimeServerVO> servers,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.tools", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.tools",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimeToolVO> tools,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.resources", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.resources",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimeResourceVO> resources,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.resourceTemplates", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.resourceTemplates",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimeResourceTemplateVO> resourceTemplates,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.prompts", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.prompts",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimePromptVO> prompts,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.taskPolicies", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.taskPolicies",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimeTaskPolicyVO> taskPolicies,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.apps", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.apps",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimeAppVO> apps,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.remoteProviders", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.remoteProviders",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimeRemoteProviderVO> remoteProviders,
        @PermissionResource(key = "yuheng.field.GatewayMcpRuleContentVO.remoteMounts", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.remoteMounts",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        List<GatewayMcpRuntimeRemoteMountVO> remoteMounts
) {
    public static GatewayMcpRuleContentVO from(McpRuleContent value) {
        if (value == null) { return null; }
        return new GatewayMcpRuleContentVO(
                value.servers() == null ? null : value.servers().stream().map(GatewayMcpRuntimeServerVO::from).toList(),
                value.tools() == null ? null : value.tools().stream().map(GatewayMcpRuntimeToolVO::from).toList(),
                value.resources() == null ? null : value.resources().stream().map(GatewayMcpRuntimeResourceVO::from).toList(),
                value.resourceTemplates() == null ? null : value.resourceTemplates().stream().map(GatewayMcpRuntimeResourceTemplateVO::from).toList(),
                value.prompts() == null ? null : value.prompts().stream().map(GatewayMcpRuntimePromptVO::from).toList(),
                value.taskPolicies() == null ? null : value.taskPolicies().stream().map(GatewayMcpRuntimeTaskPolicyVO::from).toList(),
                value.apps() == null ? null : value.apps().stream().map(GatewayMcpRuntimeAppVO::from).toList(),
                value.remoteProviders() == null ? null : value.remoteProviders().stream().map(GatewayMcpRuntimeRemoteProviderVO::from).toList(),
                value.remoteMounts() == null ? null : value.remoteMounts().stream().map(GatewayMcpRuntimeRemoteMountVO::from).toList()
        );
    }
}
