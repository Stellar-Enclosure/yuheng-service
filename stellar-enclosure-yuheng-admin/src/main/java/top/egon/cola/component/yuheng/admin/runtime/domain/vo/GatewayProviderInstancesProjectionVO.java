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
public record GatewayProviderInstancesProjectionVO(@PermissionResource(key = "yuheng.field.GatewayProviderInstancesProjectionVO.value", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.value",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    List<GatewayProviderInstanceVO> value, @PermissionResource(key = "yuheng.field.GatewayProviderInstancesProjectionVO.observedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.observedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    Instant observedAt, @PermissionResource(key = "yuheng.field.GatewayProviderInstancesProjectionVO.source", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.source",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String source, @PermissionResource(key = "yuheng.field.GatewayProviderInstancesProjectionVO.stale", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.stale",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    boolean stale, @PermissionResource(key = "yuheng.field.GatewayProviderInstancesProjectionVO.refreshError", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.refreshError",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String refreshError) {
    public static GatewayProviderInstancesProjectionVO from(GatewayProjectionEnvelopeVO<List<GatewayProviderInstanceVO>> value) {
        return new GatewayProviderInstancesProjectionVO(value.value(),
                value.observedAt(), value.source(), value.stale(), value.refreshError());
    }
}
