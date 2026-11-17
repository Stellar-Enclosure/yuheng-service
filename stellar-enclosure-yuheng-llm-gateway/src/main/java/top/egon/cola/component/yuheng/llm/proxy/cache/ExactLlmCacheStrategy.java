package top.egon.cola.component.yuheng.llm.proxy.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RMapCache;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
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
        RedissonClient redissonClient = redissonClient();
        if (redissonClient == null) {
            return null;
        }
        String key = buildCacheKey(command);
        RMapCache<String, String> map = redissonClient.getMapCache(MAP_PREFIX + snapshot.getModelKey());
        String raw = map.get(key);
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, LlmCacheEntryBO.class);
        } catch (JsonProcessingException ex) {
            log.warn("Exact cache entry deserialization failed alias={}", snapshot.getModelKey());
            return null;
        }
    }

    @Override
    public void store(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot, ObjectNode response) {
        RedissonClient redissonClient = redissonClient();
        if (redissonClient == null || response == null) {
            return;
        }
        LlmCachePolicyBO policy = snapshot.getCachePolicy();
        int ttl = policy != null && policy.getTtlSeconds() != null ? policy.getTtlSeconds() : 3600;
        String key = buildCacheKey(command);
        LlmCacheEntryBO entry = LlmCacheEntryBO.builder()
                .createdAt(Instant.now(Clock.systemUTC()))
                .responseJson(response.toString())
                .build();
        try {
            String raw = objectMapper.writeValueAsString(entry);
            RMapCache<String, String> map = redissonClient.getMapCache(MAP_PREFIX + snapshot.getModelKey());
            map.put(key, raw, ttl, TimeUnit.SECONDS);
        } catch (JsonProcessingException ex) {
            log.warn("Exact cache entry serialization failed alias={}", snapshot.getModelKey());
        }
    }

    @Override
    public void evictModel(String modelKey) {
        RedissonClient redissonClient = redissonClient();
        if (redissonClient == null || StringUtils.isBlank(modelKey)) {
            return;
        }
        redissonClient.getMapCache(MAP_PREFIX + modelKey).clear();
    }

    public String buildCacheKey(LlmInvocationCommandDTO command) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("protocol", command.getProtocol().wireValue());
        canonical.put("model", command.getModel());
        JsonNode payload = command.getPayload();
        if (payload != null) {
            if (payload.has("messages")) {
                canonical.set("messages", payload.get("messages"));
            }
            if (payload.has("input")) {
                canonical.set("input", payload.get("input"));
            }
            if (payload.has("system")) {
                canonical.set("system", payload.get("system"));
            }
            if (payload.has("tools")) {
                canonical.set("tools", payload.get("tools"));
            }
            if (payload.has("temperature")) {
                canonical.set("temperature", payload.get("temperature"));
            }
            if (payload.has("seed")) {
                canonical.set("seed", payload.get("seed"));
            }
            if (payload.has("response_format")) {
                canonical.set("response_format", payload.get("response_format"));
            }
        }
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
