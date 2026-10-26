package top.egon.cola.component.yuheng.admin.catalog.domain.bo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.experimental.Accessors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 中文说明：{@code GatewayOperationDefinitionBO} 是业务载体，保留原持久化载体的业务字段与投影语义，不继承 {@code EgonModel}，也不承载持久化列注解。
 * English summary: {@code GatewayOperationDefinitionBO} is the business carrier holding the fields and projection semantics of the legacy persistence carrier; it neither extends {@code EgonModel} nor carries persistence column annotations.
 *
 * 用法 / Usage: 仅在应用与领域层之间传递业务事实；持久边界由 RecordPO 与 Converter 负责。/ Use it between application and domain layers only; the persistence boundary stays on the RecordPO and its converter.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
public class GatewayOperationDefinitionBO {

    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.id", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.id",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String id;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.operationId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.operationId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String operationId;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.definitionVersion", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.definitionVersion",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private long definitionVersion;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.definitionSha256", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.definitionSha256",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String definitionSha256;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.summary", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.summary",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String summary;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.tags", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.tags",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private List<String> tags;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.requestSchema", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.requestSchema",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private Map<String, Object> requestSchema;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.responseSchema", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.responseSchema",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private Map<String, Object> responseSchema;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.errorSchema", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.errorSchema",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private List<Map<String, Object>> errorSchema;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.descriptorSnapshot", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.descriptorSnapshot",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private Map<String, Object> descriptorSnapshot;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.attributes", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.attributes",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private Map<String, Object> attributes;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.externalAccessible", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.externalAccessible",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private boolean externalAccessible;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.createdAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.createdAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Instant createdAt;
    @PermissionResource(key = "yuheng.field.GatewayOperationDefinitionBO.createdBy", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.createdBy",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
    private String createdBy;
}
