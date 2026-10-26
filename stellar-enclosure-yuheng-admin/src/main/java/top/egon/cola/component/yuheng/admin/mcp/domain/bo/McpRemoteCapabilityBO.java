package top.egon.cola.component.yuheng.admin.mcp.domain.bo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.time.Instant;
import java.util.Map;
import lombok.experimental.Accessors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 中文说明：{@code McpRemoteCapabilityBO} 是业务载体，保留原持久化载体的业务字段与投影语义，不继承 {@code EgonModel}，也不承载持久化列注解。
 * English summary: {@code McpRemoteCapabilityBO} is the business carrier holding the fields and projection semantics of the legacy persistence carrier; it neither extends {@code EgonModel} nor carries persistence column annotations.
 *
 * 用法 / Usage: 仅在应用与领域层之间传递业务事实；持久边界由 RecordPO 与 Converter 负责。/ Use it between application and domain layers only; the persistence boundary stays on the RecordPO and its converter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
public class McpRemoteCapabilityBO {

    @PermissionResource(key = "yuheng.field.McpRemoteCapabilityBO.id", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.id",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.MCP_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
    private String id;
    @PermissionResource(key = "yuheng.field.McpRemoteCapabilityBO.providerId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.providerId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.MCP_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
    private String providerId;
    @PermissionResource(key = "yuheng.field.McpRemoteCapabilityBO.primitiveType", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.primitiveType",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.MCP_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
    private String primitiveType;
    @PermissionResource(key = "yuheng.field.McpRemoteCapabilityBO.remoteName", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.remoteName",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.MCP_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
    private String remoteName;
    @PermissionResource(key = "yuheng.field.McpRemoteCapabilityBO.descriptor", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.descriptor",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.MCP_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
    private Map<String, Object> descriptor;
    @PermissionResource(key = "yuheng.field.McpRemoteCapabilityBO.capabilityFingerprint", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.capabilityFingerprint",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.MCP_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
    private String capabilityFingerprint;
    @PermissionResource(key = "yuheng.field.McpRemoteCapabilityBO.syncedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.syncedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.MCP_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant syncedAt;
}
