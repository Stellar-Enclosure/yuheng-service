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
import top.egon.cola.component.tianshu.model.management.DdcManagementConfigClientInstance;

/** 网关管理面拥有的注册信息视图。 / Gateway-owned view of the observed registration. */
public record GatewayConfigClientInstanceVO(
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.bizCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.bizCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String bizCode,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.env", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.env",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String env,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.appCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.appCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String appCode,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.instanceId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.instanceId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String instanceId,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.leaseId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.leaseId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String leaseId,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.host", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.host",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String host,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.port", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.port",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        Integer port,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.leaseRole", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.leaseRole",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String leaseRole,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.status", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.status",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String status,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.registeredAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.registeredAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant registeredAt,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.lastHeartbeatAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.lastHeartbeatAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant lastHeartbeatAt,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.expireAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.expireAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant expireAt,
        @PermissionResource(key = "yuheng.field.GatewayConfigClientInstanceVO.metadata", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.metadata",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        Map<String, String> metadata
) {
    public static GatewayConfigClientInstanceVO from(DdcManagementConfigClientInstance value) {
        if (value == null) { return null; }
        return new GatewayConfigClientInstanceVO(value.bizCode(), value.env(), value.appCode(), value.instanceId(), value.leaseId(), value.host(), value.port(), value.leaseRole(), value.status(), value.registeredAt(), value.lastHeartbeatAt(), value.expireAt(), value.metadata());
    }
}
