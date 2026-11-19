package top.egon.cola.component.yuheng.llm.proxy.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.egon.cola.component.yuheng.llm.config.LlmGatewayProperties;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmCacheEntryBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmCachePolicyBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.dto.LlmInvocationCommandDTO;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmCacheModeEnum;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 中文说明：{@code ExactLlmCacheStrategy} 基于 SHA-256 请求摘要实现网关层精确响应缓存。
 * English summary: {@code ExactLlmCacheStrategy} implements exact gateway response caching using SHA-256 request digests.
 */
@Slf4j
@Component("exactLlmCacheStrategy")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ExactLlmCacheStrategy implements LlmCacheStrategy {

    private static final String MAP_PREFIX = "yuheng:llm:cache:exact:";

    @Qualifier(LlmGatewayProperties.BEAN_NAME)
    private final LlmGatewayProperties gatewayProperties;

    @Qualifier("jacksonObjectMapper")
    private final ObjectMapper objectMapper;

    @Qualifier("redissonClient")
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    public ExactLlmCacheStrategy(
            LlmGatewayProperties gatewayProperties,
            ObjectMapper objectMapper,
            RedissonClient redissonClient) {
        this(gatewayProperties, objectMapper, new ObjectProvider<>() {
            @Override
            public RedissonClient getObject(Object... args) {
                return redissonClient;
            }

            @Override
            public RedissonClient getIfAvailable() {
                return redissonClient;
            }

            @Override
            public RedissonClient getIfUnique() {
                return redissonClient;
            }

            @Override
            public RedissonClient getObject() {
                return redissonClient;
            }
        });
    }

    private RedissonClient redissonClient() {
        return redissonClientProvider == null ? null : redissonClientProvider.getIfAvailable();
    }

    @Override
    public boolean supports(LlmCacheModeEnum mode) {
        return mode == LlmCacheModeEnum.EXACT;
    }

    @Override
    public LlmCacheEntryBO lookup(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
        RedissonClient redisson = redissonClient();
        String partition = LlmCacheStrategy.partition(command, snapshot, gatewayProperties, objectMapper);
        if (redisson == null || partition == null) return null;
        String context = LlmCacheStrategy.digest(command.getPayload(), objectMapper);
        String raw = redisson.<String, String>getMapCache(MAP_PREFIX + "v2:" + snapshot.getModelKey()).get(partition + ":" + context);
        if (StringUtils.isBlank(raw) || raw.length() > responseLimit()) return null;
        try {
            LlmCacheEntryBO entry = objectMapper.readValue(raw, LlmCacheEntryBO.class);
            return LlmCacheStrategy.fresh(entry, partition, context, ttl(snapshot), Instant.now(Clock.systemUTC())) ? entry : null;
        } catch (JsonProcessingException invalid) {
            log.warn("Exact cache entry rejected alias={}", snapshot.getModelKey()); return null;
        }
    }

    @Override
    public void store(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot, ObjectNode response) {
        RedissonClient redisson = redissonClient();
        String partition = LlmCacheStrategy.partition(command, snapshot, gatewayProperties, objectMapper);
        if (redisson == null || partition == null || response == null) return;
        String context = LlmCacheStrategy.digest(command.getPayload(), objectMapper);
        int ttl = ttl(snapshot); if (ttl < 1) return;
        Instant now = Instant.now(Clock.systemUTC());
        LlmCacheEntryBO entry = LlmCacheEntryBO.builder().createdAt(now).expiresAt(now.plusSeconds(ttl))
                .partitionSha256(partition).contextSha256(context).responseJson(response.toString()).build();
        if (entry.getResponseJson().length() > responseLimit()) return;
        try {
            String raw = objectMapper.writeValueAsString(entry);
            if (raw.length() > responseLimit()) return;
            redisson.<String, String>getMapCache(MAP_PREFIX + "v2:" + snapshot.getModelKey())
                    .put(partition + ":" + context, raw, ttl, TimeUnit.SECONDS);
        } catch (JsonProcessingException invalid) {
            log.warn("Exact cache entry serialization failed alias={}", snapshot.getModelKey());
        }
    }

    @Override
    public void evictModel(String modelKey) {
        RedissonClient redisson = redissonClient();
        if (redisson != null && StringUtils.isNotBlank(modelKey)) redisson.getMapCache(MAP_PREFIX + "v2:" + modelKey).clear();
    }

    /** Programmatic hooks use the same trusted partition as the Redis strategy, never a body-only key. */
    public String buildCacheKey(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
        String partition = LlmCacheStrategy.partition(command, snapshot, gatewayProperties, objectMapper);
        return partition == null ? null : partition + ":" + LlmCacheStrategy.digest(command.getPayload(), objectMapper);
    }

    private int responseLimit() { return (int) Math.min(Math.max(gatewayProperties.getMaxRequestBytes(), 1_048_576L), 8_388_608L); }
    private static int ttl(LlmModelSnapshotBO snapshot) {
        LlmCachePolicyBO policy = snapshot.getCachePolicy();
        return policy != null && policy.getTtlSeconds() != null ? policy.getTtlSeconds() : 0;
    }
}
