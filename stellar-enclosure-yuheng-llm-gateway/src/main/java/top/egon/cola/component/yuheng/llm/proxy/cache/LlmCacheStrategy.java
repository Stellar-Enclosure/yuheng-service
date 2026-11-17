package top.egon.cola.component.yuheng.llm.proxy.cache;

import com.fasterxml.jackson.databind.node.ObjectNode;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmCacheEntryBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.dto.LlmInvocationCommandDTO;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmCacheModeEnum;

/**
 * 中文说明：{@code LlmCacheStrategy} 是网关层响应缓存策略 SPI。
 * English summary: {@code LlmCacheStrategy} is the SPI for gateway-level response cache strategies.
 */
public interface LlmCacheStrategy {

    boolean supports(LlmCacheModeEnum mode);

    LlmCacheEntryBO lookup(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot);

    void store(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot, ObjectNode response);

    void evictModel(String modelKey);
}
