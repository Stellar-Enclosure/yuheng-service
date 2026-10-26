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

import java.util.Set;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmRouteBindingDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmCapabilityEnum;

/** 独立的 HTTP 读取字段权限。 / HTTP read permission independent from the native write carrier. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class GatewayLlmRouteBindingVO {
    @PermissionResource(key = "yuheng.field.GatewayLlmRouteBindingVO.channelKey", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.channelKey",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private String channelKey;

    @PermissionResource(key = "yuheng.field.GatewayLlmRouteBindingVO.upstreamModel", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.upstreamModel",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private String upstreamModel;

    @PermissionResource(key = "yuheng.field.GatewayLlmRouteBindingVO.capabilities", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.capabilities",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private Set<LlmCapabilityEnum> capabilities;

    @PermissionResource(key = "yuheng.field.GatewayLlmRouteBindingVO.priority", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.priority",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.LLM_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private Integer priority;

    @PermissionResource(key = "yuheng.field.GatewayLlmRouteBindingVO.weight", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.weight",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    private Integer weight;

    public static GatewayLlmRouteBindingVO from(LlmRouteBindingDTO value) {
        return new GatewayLlmRouteBindingVO(value.getChannelKey(), value.getUpstreamModel(), value.getCapabilities(),
                value.getPriority(), value.getWeight());
    }
}
