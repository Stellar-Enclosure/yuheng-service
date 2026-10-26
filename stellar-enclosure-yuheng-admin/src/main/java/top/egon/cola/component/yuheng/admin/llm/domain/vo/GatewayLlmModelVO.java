package top.egon.cola.component.yuheng.admin.llm.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmModelKindEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmProtocolEnum;

/** 只读模型配置投影，保留原 JSON 结构。 / Read projection preserving the existing model JSON shape. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class GatewayLlmModelVO {
    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.key", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.key",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private String key;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.name", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.name",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private String name;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.kind", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.kind",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private LlmModelKindEnum kind;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.protocols", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.protocols",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private List<LlmProtocolEnum> protocols;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.LLM_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private Boolean enabled;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.dimensions", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.dimensions",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private Integer dimensions;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.embeddingSpaceId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.embeddingSpaceId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private String embeddingSpaceId;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.allowedSubjects", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.allowedSubjects",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private List<String> allowedSubjects;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.routes", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.routes",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.LLM_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private List<GatewayLlmRouteBindingVO> routes;

    @PermissionResource(key = "yuheng.field.GatewayLlmModelVO.revision", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.revision",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private Long revision;

    public static GatewayLlmModelVO from(LlmModelVO value) {
        return new GatewayLlmModelVO(value.getKey(), value.getName(), value.getKind(), value.getProtocols(),
                value.getEnabled(), value.getDimensions(), value.getEmbeddingSpaceId(), value.getAllowedSubjects(),
                value.getRoutes().stream().map(GatewayLlmRouteBindingVO::from).toList(), value.getRevision());
    }
}
