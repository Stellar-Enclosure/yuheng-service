package top.egon.cola.component.yuheng.admin.release.domain.bo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.time.Instant;
import java.util.List;
import lombok.experimental.Accessors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 中文说明：{@code GatewayReleaseAttemptBO} 是业务载体，保留原持久化载体的业务字段与投影语义，不继承 {@code EgonModel}，也不承载持久化列注解。
 * English summary: {@code GatewayReleaseAttemptBO} is the business carrier holding the fields and projection semantics of the legacy persistence carrier; it neither extends {@code EgonModel} nor carries persistence column annotations.
 *
 * 用法 / Usage: 仅在应用与领域层之间传递业务事实；持久边界由 RecordPO 与 Converter 负责。/ Use it between application and domain layers only; the persistence boundary stays on the RecordPO and its converter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
public class GatewayReleaseAttemptBO {

    @PermissionResource(key = "yuheng.field.GatewayReleaseAttemptBO.attemptNo", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.attemptNo",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private int attemptNo;
    @PermissionResource(key = "yuheng.field.GatewayReleaseAttemptBO.status", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.status",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String status;
    @PermissionResource(key = "yuheng.field.GatewayReleaseAttemptBO.changeId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.changeId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String changeId;
    @PermissionResource(key = "yuheng.field.GatewayReleaseAttemptBO.startedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.startedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant startedAt;
    @PermissionResource(key = "yuheng.field.GatewayReleaseAttemptBO.completedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.completedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant completedAt;
    @PermissionResource(key = "yuheng.field.GatewayReleaseAttemptBO.errorCode", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.errorCode",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String errorCode;
    @PermissionResource(key = "yuheng.field.GatewayReleaseAttemptBO.errorMessage", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.errorMessage",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String errorMessage;
    @PermissionResource(key = "yuheng.field.GatewayReleaseAttemptBO.targets", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.targets",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private List<GatewayReleaseTargetBO> targets;
}
