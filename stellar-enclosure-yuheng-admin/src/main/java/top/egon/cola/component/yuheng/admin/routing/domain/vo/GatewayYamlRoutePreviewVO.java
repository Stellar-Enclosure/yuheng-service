package top.egon.cola.component.yuheng.admin.routing.domain.vo;

import java.util.List;
import java.util.Map;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

public record GatewayYamlRoutePreviewVO(
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutePreviewVO.revision", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.revision",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        long revision,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutePreviewVO.created", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.yamlCreated",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        List<String> created,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutePreviewVO.updated", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.yamlUpdated",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        List<String> updated,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutePreviewVO.deleted", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.yamlDeleted",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        List<String> deleted,
        @PermissionResource(key = "yuheng.field.GatewayYamlRoutePreviewVO.changes", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.yamlChanges",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.DRAFTS_WRITE)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.DRAFTS_WRITE))
        Map<String, Object> changes) {
    public GatewayYamlRoutePreviewVO {
        created = List.copyOf(created);
        updated = List.copyOf(updated);
        deleted = List.copyOf(deleted);
        changes = Map.copyOf(changes);
    }
}
