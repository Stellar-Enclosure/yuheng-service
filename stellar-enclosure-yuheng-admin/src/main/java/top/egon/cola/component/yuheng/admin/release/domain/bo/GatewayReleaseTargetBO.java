package top.egon.cola.component.yuheng.admin.release.domain.bo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.time.Instant;
import top.egon.cola.component.yuheng.contract.runtime.GatewayEngineRoleEnum;
import lombok.experimental.Accessors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 中文说明：{@code GatewayReleaseTargetBO} 是业务载体，保留原持久化载体的业务字段与投影语义，不继承 {@code EgonModel}，也不承载持久化列注解。
 * English summary: {@code GatewayReleaseTargetBO} is the business carrier holding the fields and projection semantics of the legacy persistence carrier; it neither extends {@code EgonModel} nor carries persistence column annotations.
 *
 * 用法 / Usage: 仅在应用与领域层之间传递业务事实；持久边界由 RecordPO 与 Converter 负责。/ Use it between application and domain layers only; the persistence boundary stays on the RecordPO and its converter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
public class GatewayReleaseTargetBO {

    @PermissionResource(key = "yuheng.field.GatewayReleaseTargetBO.instanceId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.instanceId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String instanceId;
    @PermissionResource(key = "yuheng.field.GatewayReleaseTargetBO.leaseId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.leaseId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String leaseId;
    @PermissionResource(key = "yuheng.field.GatewayReleaseTargetBO.status", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.status",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String status;
    @PermissionResource(key = "yuheng.field.GatewayReleaseTargetBO.appliedVersion", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.appliedVersion",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private Long appliedVersion;
    @PermissionResource(key = "yuheng.field.GatewayReleaseTargetBO.appliedArtifactSha256", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.appliedArtifactSha256",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String appliedArtifactSha256;
    @PermissionResource(key = "yuheng.field.GatewayReleaseTargetBO.errorCode", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.errorCode",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String errorCode;
    @PermissionResource(key = "yuheng.field.GatewayReleaseTargetBO.observedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.observedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant observedAt;
    @PermissionResource(key = "yuheng.field.GatewayReleaseTargetBO.engineRole", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.engineRole",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private GatewayEngineRoleEnum engineRole;
}
