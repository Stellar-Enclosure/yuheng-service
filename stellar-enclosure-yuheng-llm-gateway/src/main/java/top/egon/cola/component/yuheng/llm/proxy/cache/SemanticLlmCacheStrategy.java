package top.egon.cola.component.yuheng.llm.proxy.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RList;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 中文说明：{@code SemanticLlmCacheStrategy} 基于本地 Embedding 模型计算的向量与余弦相似度实现语义响应缓存。
 * English summary: {@code SemanticLlmCacheStrategy} implements semantic response caching using local embedding vectors and cosine similarity.
 */
@Slf4j
@Component("semanticLlmCacheStrategy")
public class SemanticLlmCacheStrategy implements LlmCacheStrategy {

    private static final String LIST_PREFIX = "yuheng:llm:cache:semantic:";

    @FunctionalInterface
    public interface EmbeddingVectorResolver {
        double[] resolve(String embeddingModelKey, String queryText);
    }

    private final LlmGatewayProperties gatewayProperties;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final EmbeddingVectorResolver embeddingVectorResolver;

    @Autowired
    public SemanticLlmCacheStrategy(
            @Qualifier(LlmGatewayProperties.BEAN_NAME) LlmGatewayProperties gatewayProperties,
            @Qualifier("jacksonObjectMapper") ObjectMapper objectMapper,
            ObjectProvider<RedissonClient> redissonClientProvider,
            ObjectProvider<EmbeddingVectorResolver> embeddingVectorResolverProvider) {
        this(
                gatewayProperties,
                objectMapper,
                redissonClientProvider == null ? null : redissonClientProvider.getIfAvailable(),
                embeddingVectorResolverProvider == null ? null : embeddingVectorResolverProvider.getIfAvailable()
        );
    }

    public SemanticLlmCacheStrategy(
            LlmGatewayProperties gatewayProperties,
            ObjectMapper objectMapper,
            RedissonClient redissonClient,
            EmbeddingVectorResolver embeddingVectorResolver) {
        this.gatewayProperties = gatewayProperties;
        this.objectMapper = objectMapper;
        this.redissonClient = redissonClient;
        this.embeddingVectorResolver = embeddingVectorResolver;
    }

    @Override
    public boolean supports(LlmCacheModeEnum mode) {
        return mode == LlmCacheModeEnum.SEMANTIC;
    }

    @Override
    public LlmCacheEntryBO lookup(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
        if (redissonClient == null || embeddingVectorResolver == null) {
            return null;
        }
        LlmCachePolicyBO policy = snapshot.getCachePolicy();
        if (policy == null || StringUtils.isBlank(policy.getEmbeddingModelKey())) {
            return null;
        }
        String queryText = extractLastUserQuery(command.getPayload());
        if (StringUtils.isBlank(queryText)) {
            return null;
        }
        double[] queryVector = embeddingVectorResolver.resolve(policy.getEmbeddingModelKey(), queryText);
        if (queryVector == null || queryVector.length == 0) {
            return null;
        }
        double threshold = policy.getSemanticThreshold() == null ? 0.95 : policy.getSemanticThreshold();
        RList<String> list = redissonClient.getList(LIST_PREFIX + snapshot.getModelKey());
        List<String> entries = list.readAll();
        LlmCacheEntryBO best = null;
        double bestScore = -1.0;
        for (String raw : entries) {
            try {
                LlmCacheEntryBO candidate = objectMapper.readValue(raw, LlmCacheEntryBO.class);
                if (candidate.getVector() == null || candidate.getVector().length != queryVector.length) {
                    continue;
                }
                double score = cosineSimilarity(queryVector, candidate.getVector());
                if (score >= threshold && score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            } catch (JsonProcessingException ignored) {
                log.debug("Skipping malformed semantic cache entry alias={}", snapshot.getModelKey());
            }
        }
        return best;
    }

    @Override
    public void store(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot, ObjectNode response) {
        if (redissonClient == null || embeddingVectorResolver == null || response == null) {
            return;
        }
        LlmCachePolicyBO policy = snapshot.getCachePolicy();
        if (policy == null || StringUtils.isBlank(policy.getEmbeddingModelKey())) {
            return;
        }
        String queryText = extractLastUserQuery(command.getPayload());
        if (StringUtils.isBlank(queryText)) {
            return;
        }
        double[] queryVector = embeddingVectorResolver.resolve(policy.getEmbeddingModelKey(), queryText);
        if (queryVector == null || queryVector.length == 0) {
            return;
        }
        int ttl = policy.getTtlSeconds() != null ? policy.getTtlSeconds() : 3600;
        int maxEntries = gatewayProperties.getCache() != null
                ? gatewayProperties.getCache().getSemanticMaxEntries() : 2000;
        LlmCacheEntryBO entry = LlmCacheEntryBO.builder()
                .createdAt(Instant.now(Clock.systemUTC()))
                .responseJson(response.toString())
                .vector(queryVector)
                .build();
        try {
            String raw = objectMapper.writeValueAsString(entry);
            RList<String> list = redissonClient.getList(LIST_PREFIX + snapshot.getModelKey());
            list.add(0, raw);
            if (list.size() > maxEntries) {
                list.trim(0, maxEntries - 1);
            }
            list.expire(Duration.ofSeconds(ttl));
        } catch (JsonProcessingException ex) {
            log.warn("Semantic cache entry serialization failed alias={}", snapshot.getModelKey());
        }
    }

    @Override
    public void evictModel(String modelKey) {
        if (redissonClient == null || StringUtils.isBlank(modelKey)) {
            return;
        }
        redissonClient.getList(LIST_PREFIX + modelKey).delete();
    }

    public static double cosineSimilarity(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return -1.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            normA += left[i] * left[i];
            normB += right[i] * right[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? -1.0 : dot / denom;
    }

    private static String extractLastUserQuery(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        JsonNode messages = payload.get("messages");
        if (messages != null && messages.isArray()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode msg = messages.get(i);
                if ("user".equals(msg.path("role").asText(""))) {
                    JsonNode content = msg.get("content");
                    if (content != null && content.isTextual()) {
                        return content.asText();
                    }
                }
            }
        }
        JsonNode input = payload.get("input");
        return input != null && input.isTextual() ? input.asText() : null;
    }
}
