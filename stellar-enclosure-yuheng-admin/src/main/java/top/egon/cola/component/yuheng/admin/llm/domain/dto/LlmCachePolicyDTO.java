package top.egon.cola.component.yuheng.admin.llm.domain.dto;

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
 * 中文说明：{@code LlmCachePolicyDTO} 承载管理端配置的模型响应缓存与前缀优化策略。
 * English summary: {@code LlmCachePolicyDTO} carries the admin-configured model response cache and prompt optimization policy.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
@Schema(description = "模型缓存与前缀优化策略")
public class LlmCachePolicyDTO {

    @PermissionResource(key = "yuheng.field.LlmCachePolicyDTO.mode", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.mode",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            write = @PermissionGroup(codes = GatewayPermissionCodes.LLM_WRITE))
    @Schema(description = "缓存模式：NONE/EXACT/SEMANTIC", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private LlmCacheModeEnum mode;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyDTO.ttlSeconds", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.ttlSeconds",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            write = @PermissionGroup(codes = GatewayPermissionCodes.LLM_WRITE))
    @Schema(description = "缓存过期秒数，范围 [10, 2592000]", defaultValue = "3600")
    @Min(10)
    @Max(2_592_000)
    private Integer ttlSeconds;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyDTO.maxCacheTemperature", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.maxCacheTemperature",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            write = @PermissionGroup(codes = GatewayPermissionCodes.LLM_WRITE))
    @Schema(description = "允许命中缓存的最大采样温度，范围 [0.0, 1.0]", defaultValue = "0.0")
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double maxCacheTemperature;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyDTO.semanticThreshold", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.semanticThreshold",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
            write = @PermissionGroup(codes = GatewayPermissionCodes.LLM_WRITE))
    @Schema(description = "语义缓存余弦相似度阈值，范围 [0.80, 0.99]", defaultValue = "0.95")
    @DecimalMin("0.80")
    @DecimalMax("0.99")
    private Double semanticThreshold;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyDTO.embeddingModelKey", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.embeddingModelKey",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            write = @PermissionGroup(codes = GatewayPermissionCodes.LLM_WRITE))
    @Schema(description = "语义缓存绑定的本地 EMBEDDING 模型别名", nullable = true)
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$")
    private String embeddingModelKey;

    @PermissionResource(key = "yuheng.field.LlmCachePolicyDTO.promptCacheOptimizationEnabled", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.promptCacheOptimizationEnabled",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.LLM_WRITE)
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            write = @PermissionGroup(codes = GatewayPermissionCodes.LLM_WRITE))
    @Schema(description = "是否启用 tools 确定性排序与 Anthropic cache_control 断点注入", defaultValue = "false")
    private Boolean promptCacheOptimizationEnabled;
}
