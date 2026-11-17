package top.egon.cola.component.yuheng.admin.llm.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "缓存模式：NONE/EXACT/SEMANTIC")
    @NotNull
    private LlmCacheModeEnum mode;

    @Schema(description = "缓存过期秒数")
    @NotNull
    @Min(10)
    @Max(2_592_000)
    private Integer ttlSeconds;

    @Schema(description = "允许命中缓存的最大采样温度")
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double maxCacheTemperature;

    @Schema(description = "语义缓存余弦相似度阈值")
    @NotNull
    @DecimalMin("0.80")
    @DecimalMax("0.99")
    private Double semanticThreshold;

    @Schema(description = "语义缓存绑定的本地 EMBEDDING 模型别名", nullable = true)
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$")
    private String embeddingModelKey;

    @Schema(description = "是否启用前缀缓存优化")
    @NotNull
    private Boolean promptCacheOptimizationEnabled;
}
