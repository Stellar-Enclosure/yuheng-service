package top.egon.cola.component.yuheng.llm.proxy.domain.bo;

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
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmCacheModeEnum;

/**
 * 中文说明：{@code LlmCachePolicyBO} 承载网关只读模型快照中的响应缓存与前缀优化策略。
 * English summary: {@code LlmCachePolicyBO} carries the response cache and prompt optimization policy in the gateway read-only snapshot.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
public class LlmCachePolicyBO {

    @NotNull
    @Builder.Default
    private LlmCacheModeEnum mode = LlmCacheModeEnum.NONE;

    @NotNull
    @Min(10)
    @Max(2_592_000)
    @Builder.Default
    private Integer ttlSeconds = 3600;

    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    @Builder.Default
    private Double maxCacheTemperature = 0.0;

    @NotNull
    @DecimalMin("0.80")
    @DecimalMax("0.99")
    @Builder.Default
    private Double semanticThreshold = 0.95;

    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$")
    private String embeddingModelKey;

    @NotNull
    @Builder.Default
    private Boolean promptCacheOptimizationEnabled = Boolean.FALSE;

    public static LlmCachePolicyBO disabledDefault() {
        return LlmCachePolicyBO.builder().build();
    }
}
