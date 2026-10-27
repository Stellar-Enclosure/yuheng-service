package top.egon.cola.component.yuheng.admin.routing.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

public record GatewayYamlRoutesRequestDTO(
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutesRequestDTO.yaml", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.yaml",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                write = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        @NotBlank String yaml,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutesRequestDTO.expectedRevision", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.expectedRevision",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                write = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        @PositiveOrZero long expectedRevision,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutesRequestDTO.idempotencyKey", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.idempotencyKey",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                write = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        @NotBlank String idempotencyKey,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutesRequestDTO.changeReason", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.changeReason",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                write = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        @NotBlank String changeReason,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutesRequestDTO.adoptExisting", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.adoptExisting",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                write = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        boolean adoptExisting,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutesRequestDTO.replaceManaged", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.replaceManaged",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                write = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        boolean replaceManaged) { }
