package top.egon.cola.component.yuheng.admin.mcp.domain.dto;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.*;

/** 原生multipart输入字段，读取文件前由控制器显式校验。 / Explicit multipart binding checked before reading file bytes. */
public record McpArtifactUploadCommandDTO(
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.gatewayGroupId",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.gatewayGroupId",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    String gatewayGroupId,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.appCode",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.appCode",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    String appCode,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.version",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.version",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    String version,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.displayName",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.displayName",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    String displayName,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.resourceUri",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.resourceUri",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    String resourceUri,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.mimeType",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.mimeType",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    String mimeType,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.contentSecurityPolicy",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.contentSecurityPolicy",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    String contentSecurityPolicy,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.permissions",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.permissions",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.ARRAY,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    java.util.Set<String> permissions,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.allowedOrigins",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.allowedOrigins",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.ARRAY,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    java.util.Set<String> allowedOrigins,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.expectedRevision",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.expectedRevision",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.NUMBER,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    long expectedRevision,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.expectedDraftRevision",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.expectedDraftRevision",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.NUMBER,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    long expectedDraftRevision,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.changeReason",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.changeReason",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    String changeReason,
    @PermissionResource(key="yuheng.field.McpArtifactUploadCommandDTO.artifact",type=ResourceType.FIELD,
            labelPage="gateway.permissions",labelKey="field.artifact",
            allowedPrincipals={PermissionManifest.PrincipalType.USER,PermissionManifest.PrincipalType.SERVICE},
            oauthScopes=GatewayPermissionCodes.MCP_WRITE)
    @FieldPermissions(valueType=PermissionManifest.JsonFieldType.STRING,write=@PermissionGroup(codes=GatewayPermissionCodes.MCP_WRITE))
    byte[] artifact
) { }
