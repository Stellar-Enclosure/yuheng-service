package top.egon.cola.component.yuheng.llm.proxy.cache;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 中文说明：{@code LlmCacheInvalidationListener} 订阅 Redis Pub/Sub 失效事件，在管理端修改模型配置时主动清理网关响应缓存。
 * English summary: {@code LlmCacheInvalidationListener} subscribes to Redis Pub/Sub invalidation events to evict gateway response caches on model updates.
 */
@Slf4j
@Component("llmCacheInvalidationListener")
public class LlmCacheInvalidationListener {

    private static final String CACHE_INVALIDATION_TOPIC = "yuheng:llm:cache:invalidate";

    private final LlmResponseCacheComponent cacheComponent;
    private final RedissonClient redissonClient;

    @Autowired
    public LlmCacheInvalidationListener(
            @Qualifier("llmResponseCacheComponent") LlmResponseCacheComponent cacheComponent,
            ObjectProvider<RedissonClient> redissonClientProvider) {
        this.cacheComponent = cacheComponent;
        this.redissonClient = redissonClientProvider == null ? null : redissonClientProvider.getIfAvailable();
    }

    @PostConstruct
    public void subscribe() {
        if (redissonClient == null) {
            return;
        }
        try {
            redissonClient.getTopic(CACHE_INVALIDATION_TOPIC)
                    .addListener(String.class, (channel, modelKey) -> {
                        log.info("YUHENG_LLM_CACHE_INVALIDATED modelKey={}", modelKey);
                        cacheComponent.evictModel(modelKey);
                    });
        } catch (RuntimeException ex) {
            log.warn("Failed to subscribe to LLM cache invalidation topic errorType={}",
                    ex.getClass().getSimpleName());
        }
    }
}
