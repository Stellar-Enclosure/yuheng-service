package top.egon.cola.component.yuheng.llm.proxy.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 中文说明：{@code LlmCacheEntryBO} 是网关响应缓存条目载体，使用 UTC {@link Instant} 记录写入时刻。
 * English summary: {@code LlmCacheEntryBO} is the gateway response cache entry carrier using UTC {@link Instant}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
public class LlmCacheEntryBO {

    @NotNull
    private Instant createdAt;

    @NotBlank
    private String responseJson;

    @NotNull
    private Instant expiresAt;

    @NotBlank
    private String partitionSha256;

    @NotBlank
    private String contextSha256;

    private double[] vector;

    public double[] getVector() { return vector == null ? null : vector.clone(); }
    public LlmCacheEntryBO setVector(double[] value) { vector = value == null ? null : value.clone(); return this; }
}
