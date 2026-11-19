package top.egon.cola.component.yuheng.llm.proxy.cache;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.egon.cola.component.yuheng.llm.proxy.repository.LlmConfigurationSnapshotCoordinator;

/**
 * 中文说明：{@code LlmCacheInvalidationListener} 订阅 Redis Pub/Sub 失效事件，在提交后唤醒后台完整配置对账；不依赖响应缓存启用。
 * English summary: {@code LlmCacheInvalidationListener} subscribes to Redis Pub/Sub invalidation events to wake background reconciliation independently of response-cache enablement.
 */
@Slf4j
@Component("llmCacheInvalidationListener")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class LlmCacheInvalidationListener {

    private static final String CACHE_INVALIDATION_TOPIC = "yuheng:llm:cache:invalidate";

    @Qualifier("mpLlmConfigurationRepository")
    private final LlmConfigurationSnapshotCoordinator coordinator;

    @Qualifier("redissonClient")
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    @PostConstruct
    public void subscribe() {
        RedissonClient redissonClient = redissonClientProvider == null ? null : redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            return;
        }
        try {
            redissonClient.getTopic(CACHE_INVALIDATION_TOPIC)
                    .addListener(String.class, (channel, modelKey) -> {
                        // Untrusted delivery is only a wake signal, never configuration or freshness authority.
                        coordinator.hint();
                    });
        } catch (RuntimeException ex) {
            log.warn("Failed to subscribe to LLM cache invalidation topic errorType={}",
                    ex.getClass().getSimpleName());
        }
    }
}
