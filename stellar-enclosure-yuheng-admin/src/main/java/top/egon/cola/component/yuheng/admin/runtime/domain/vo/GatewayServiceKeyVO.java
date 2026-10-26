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
import top.egon.cola.component.tianshu.model.management.DdcManagementServiceKey;

/** 网关管理面拥有的注册信息视图。 / Gateway-owned view of the observed registration. */
public record GatewayServiceKeyVO(
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.bizCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.bizCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String bizCode,
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.env", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.env",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String env,
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.appCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.appCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String appCode,
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.serviceId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serviceId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String serviceId,
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.serviceKind", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serviceKind",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String serviceKind,
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.serviceName", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serviceName",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String serviceName,
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.group", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.group",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String group,
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.version", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.version",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String version,
        @PermissionResource(key = "yuheng.field.GatewayServiceKeyVO.protocol", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.protocol",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String protocol
) {
    public static GatewayServiceKeyVO from(DdcManagementServiceKey value) {
        if (value == null) { return null; }
        return new GatewayServiceKeyVO(value.bizCode(), value.env(), value.appCode(), value.serviceId(), value.serviceKind(), value.serviceName(), value.group(), value.version(), value.protocol());
    }
}
