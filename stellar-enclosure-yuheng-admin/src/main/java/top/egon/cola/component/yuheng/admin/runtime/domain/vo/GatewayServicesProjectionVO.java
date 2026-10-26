package top.egon.cola.component.yuheng.admin.runtime.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.time.Instant;
import java.util.List;

/** 固定 HTTP 投影形状。 / Concrete HTTP projection shape with producer-owned fields. */
public record GatewayServicesProjectionVO(@PermissionResource(key = "yuheng.field.GatewayServicesProjectionVO.value", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.value",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    GatewayServiceCatalogVO value, @PermissionResource(key = "yuheng.field.GatewayServicesProjectionVO.observedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.observedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    Instant observedAt, @PermissionResource(key = "yuheng.field.GatewayServicesProjectionVO.source", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.source",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String source, @PermissionResource(key = "yuheng.field.GatewayServicesProjectionVO.stale", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.stale",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    boolean stale, @PermissionResource(key = "yuheng.field.GatewayServicesProjectionVO.refreshError", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.refreshError",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String refreshError) {
    public static GatewayServicesProjectionVO from(GatewayProjectionEnvelopeVO<top.egon.cola.component.tianshu.model.management.DdcManagementServiceCatalog> value) {
        return new GatewayServicesProjectionVO(GatewayServiceCatalogVO.from(value.value()),
                value.observedAt(), value.source(), value.stale(), value.refreshError());
    }
}
