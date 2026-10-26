package top.egon.cola.component.yuheng.admin.runtime.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import top.egon.cola.component.tianshu.model.management.DdcManagementServiceCatalog;

/** 网关管理面拥有的注册信息视图。 / Gateway-owned view of the observed registration. */
public record GatewayServiceCatalogVO(
        @PermissionResource(key = "yuheng.field.GatewayServiceCatalogVO.generation", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.generation",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        long generation,
        @PermissionResource(key = "yuheng.field.GatewayServiceCatalogVO.observedAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.observedAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant observedAt,
        @PermissionResource(key = "yuheng.field.GatewayServiceCatalogVO.services", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.services",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        List<GatewayServiceKeyVO> services
) {
    public static GatewayServiceCatalogVO from(DdcManagementServiceCatalog value) {
        if (value == null) { return null; }
        return new GatewayServiceCatalogVO(value.generation(), value.observedAt(), value.services().stream().map(GatewayServiceKeyVO::from).toList());
    }
}
