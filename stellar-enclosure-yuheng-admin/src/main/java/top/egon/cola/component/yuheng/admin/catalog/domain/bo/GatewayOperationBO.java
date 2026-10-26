package top.egon.cola.component.yuheng.admin.catalog.domain.bo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.time.Instant;
import java.util.Map;
import lombok.experimental.Accessors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 中文说明：{@code GatewayOperationBO} 是业务载体，保留原持久化载体的业务字段与投影语义，不继承 {@code EgonModel}，也不承载持久化列注解。
 * English summary: {@code GatewayOperationBO} is the business carrier holding the fields and projection semantics of the legacy persistence carrier; it neither extends {@code EgonModel} nor carries persistence column annotations.
 *
 * 用法 / Usage: 仅在应用与领域层之间传递业务事实；持久边界由 RecordPO 与 Converter 负责。/ Use it between application and domain layers only; the persistence boundary stays on the RecordPO and its converter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
public class GatewayOperationBO {

    @PermissionResource(key = "yuheng.field.GatewayOperationBO.id", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.id",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String id;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.applicationId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.applicationId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String applicationId;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.interfaceGroupId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.interfaceGroupId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String interfaceGroupId;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.operationKey", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.operationKey",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String operationKey;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.protocol", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.protocol",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String protocol;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.methodIdentity", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.methodIdentity",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String methodIdentity;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.externalAccessible", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.externalAccessible",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private boolean externalAccessible;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.providerServiceIdentity", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.providerServiceIdentity",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private Map<String, Object> providerServiceIdentity;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.sourceType", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.sourceType",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String sourceType;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.lifecycleStatus", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.lifecycleStatus",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String lifecycleStatus;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.currentDefinitionId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.currentDefinitionId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String currentDefinitionId;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.revision", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.revision",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private long revision;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.createdAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.createdAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant createdAt;
    @PermissionResource(key = "yuheng.field.GatewayOperationBO.updatedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.updatedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant updatedAt;
}
