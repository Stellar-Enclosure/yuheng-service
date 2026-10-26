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
public record GatewayConfigClientsProjectionVO(@PermissionResource(key = "yuheng.field.GatewayConfigClientsProjectionVO.value", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.value",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    List<GatewayConfigClientInstanceVO> value, @PermissionResource(key = "yuheng.field.GatewayConfigClientsProjectionVO.observedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.observedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    Instant observedAt, @PermissionResource(key = "yuheng.field.GatewayConfigClientsProjectionVO.source", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.source",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String source, @PermissionResource(key = "yuheng.field.GatewayConfigClientsProjectionVO.stale", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.stale",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    boolean stale, @PermissionResource(key = "yuheng.field.GatewayConfigClientsProjectionVO.refreshError", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.refreshError",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    String refreshError) {
    public static GatewayConfigClientsProjectionVO from(GatewayProjectionEnvelopeVO<List<top.egon.cola.component.tianshu.model.management.DdcManagementConfigClientInstance>> value) {
        return new GatewayConfigClientsProjectionVO(value.value() == null ? null : value.value().stream().map(GatewayConfigClientInstanceVO::from).toList(),
                value.observedAt(), value.source(), value.stale(), value.refreshError());
    }
}
