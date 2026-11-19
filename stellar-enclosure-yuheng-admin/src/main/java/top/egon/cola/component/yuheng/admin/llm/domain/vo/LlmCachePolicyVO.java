package top.egon.cola.component.yuheng.admin.llm.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmCacheModeEnum;

/**
 * 中文说明：{@code LlmCachePolicyVO} 是向管理端输出模型缓存与前缀优化策略的视图对象。
 * English summary: {@code LlmCachePolicyVO} is the view object exposing model cache and prompt optimization policy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
@Schema(description = "模型缓存与前缀优化策略视图")
public class LlmCachePolicyVO {

    @PermissionResource(key = "yuheng.field.LlmCachePolicyVO.mode", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.mode",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    @Schema(description = "缓存模式：NONE/EXACT/SEMANTIC")
    @NotNull
    private LlmCacheModeEnum mode;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyVO.ttlSeconds", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.ttlSeconds",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    @Schema(description = "缓存过期秒数")
    @NotNull
    @Min(10)
    @Max(2_592_000)
    private Integer ttlSeconds;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyVO.maxCacheTemperature", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.maxCacheTemperature",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    @Schema(description = "允许命中缓存的最大采样温度")
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double maxCacheTemperature;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyVO.semanticThreshold", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.semanticThreshold",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    @Schema(description = "语义缓存余弦相似度阈值")
    @NotNull
    @DecimalMin("0.80")
    @DecimalMax("0.99")
    private Double semanticThreshold;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyVO.embeddingModelKey", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.embeddingModelKey",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    @Schema(description = "语义缓存绑定的本地 EMBEDDING 模型别名", nullable = true)
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$")
    private String embeddingModelKey;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyVO.promptCacheOptimizationEnabled", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.promptCacheOptimizationEnabled",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_READ)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            plainRead = @PermissionGroup(codes = GatewayPermissionCodes.LLM_READ))
    @Schema(description = "是否启用前缀缓存优化")
    @NotNull
    private Boolean promptCacheOptimizationEnabled;
}
