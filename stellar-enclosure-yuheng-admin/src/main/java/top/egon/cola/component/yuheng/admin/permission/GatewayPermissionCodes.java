package top.egon.cola.component.yuheng.admin.permission;

import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionDefinition;

/** Stable management permissions owned by the Gateway Admin producer. */
@PermissionDefinition(code = GatewayPermissionCodes.APPLICATIONS_WRITE, meaning = "Applications write", labelPage = "gateway.permissions", labelKey = "permission.applications.write")
@PermissionDefinition(code = GatewayPermissionCodes.CATALOG_WRITE, meaning = "Catalog write", labelPage = "gateway.permissions", labelKey = "permission.catalog.write")
@PermissionDefinition(code = GatewayPermissionCodes.CREDENTIALS_WRITE, meaning = "Credentials write", labelPage = "gateway.permissions", labelKey = "permission.credentials.write")
@PermissionDefinition(code = GatewayPermissionCodes.DRAFTS_WRITE, meaning = "Drafts write", labelPage = "gateway.permissions", labelKey = "permission.drafts.write")
@PermissionDefinition(code = GatewayPermissionCodes.GROUPS_WRITE, meaning = "Groups write", labelPage = "gateway.permissions", labelKey = "permission.groups.write")
@PermissionDefinition(code = GatewayPermissionCodes.LLM_READ, meaning = "Llm read", labelPage = "gateway.permissions", labelKey = "permission.llm.read")
@PermissionDefinition(code = GatewayPermissionCodes.LLM_WRITE, meaning = "Llm write", labelPage = "gateway.permissions", labelKey = "permission.llm.write")
@PermissionDefinition(code = GatewayPermissionCodes.MCP_APPROVE, meaning = "Mcp approve", labelPage = "gateway.permissions", labelKey = "permission.mcp.approve")
@PermissionDefinition(code = GatewayPermissionCodes.MCP_READ, meaning = "Mcp read", labelPage = "gateway.permissions", labelKey = "permission.mcp.read")
@PermissionDefinition(code = GatewayPermissionCodes.MCP_RUNTIME_READ, meaning = "Mcp runtime read", labelPage = "gateway.permissions", labelKey = "permission.mcp.runtime.read")
@PermissionDefinition(code = GatewayPermissionCodes.MCP_TEST, meaning = "Mcp test", labelPage = "gateway.permissions", labelKey = "permission.mcp.test")
@PermissionDefinition(code = GatewayPermissionCodes.MCP_WRITE, meaning = "Mcp write", labelPage = "gateway.permissions", labelKey = "permission.mcp.write")
@PermissionDefinition(code = GatewayPermissionCodes.READ, meaning = "Read", labelPage = "gateway.permissions", labelKey = "permission.read")
@PermissionDefinition(code = GatewayPermissionCodes.RELEASES_WRITE, meaning = "Releases write", labelPage = "gateway.permissions", labelKey = "permission.releases.write")
public final class GatewayPermissionCodes {
    public static final String APPLICATIONS_WRITE = "yuheng:applications:write";
    public static final String CATALOG_WRITE = "yuheng:catalog:write";
    public static final String CREDENTIALS_WRITE = "yuheng:credentials:write";
    public static final String DRAFTS_WRITE = "yuheng:drafts:write";
    public static final String GROUPS_WRITE = "yuheng:groups:write";
    public static final String LLM_READ = "yuheng:llm:read";
    public static final String LLM_WRITE = "yuheng:llm:write";
    public static final String MCP_APPROVE = "yuheng:mcp:approve";
    public static final String MCP_READ = "yuheng:mcp:read";
    public static final String MCP_RUNTIME_READ = "yuheng:mcp:runtime:read";
    public static final String MCP_TEST = "yuheng:mcp:test";
    public static final String MCP_WRITE = "yuheng:mcp:write";
    public static final String READ = "yuheng:read";
    public static final String RELEASES_WRITE = "yuheng:releases:write";

    private GatewayPermissionCodes() { }
}
