package top.egon.cola.component.yuheng.llm.proxy.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class SemanticLlmCacheStrategy implements LlmCacheStrategy {

    private static final String LIST_PREFIX = "yuheng:llm:cache:semantic:";

    @FunctionalInterface
    public interface EmbeddingVectorResolver {
        double[] resolve(String embeddingModelKey, String queryText);
    }

    @Qualifier(LlmGatewayProperties.BEAN_NAME)
    private final LlmGatewayProperties gatewayProperties;

    @Qualifier("jacksonObjectMapper")
    private final ObjectMapper objectMapper;

    @Qualifier("redissonClient")
    private final ObjectProvider<RedissonClient> redissonClientProvider;

    @Qualifier("semanticEmbeddingVectorResolver")
    private final ObjectProvider<EmbeddingVectorResolver> embeddingVectorResolverProvider;

    public SemanticLlmCacheStrategy(
            LlmGatewayProperties gatewayProperties,
            ObjectMapper objectMapper,
            RedissonClient redissonClient,
            EmbeddingVectorResolver embeddingVectorResolver) {
        this(
                gatewayProperties,
                objectMapper,
                staticProvider(redissonClient),
                staticProvider(embeddingVectorResolver)
        );
    }

    private static <T> ObjectProvider<T> staticProvider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private RedissonClient redissonClient() {
        return redissonClientProvider == null ? null : redissonClientProvider.getIfAvailable();
    }

    private EmbeddingVectorResolver embeddingVectorResolver() {
        return embeddingVectorResolverProvider == null ? null : embeddingVectorResolverProvider.getIfAvailable();
    }

    @Override
    public boolean supports(LlmCacheModeEnum mode) {
        return mode == LlmCacheModeEnum.SEMANTIC;
    }

    @Override
    public LlmCacheEntryBO lookup(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
        RedissonClient redisson = redissonClient();
        EmbeddingVectorResolver embedder = embeddingVectorResolver();
        String partition = LlmCacheStrategy.partition(command, snapshot, gatewayProperties, objectMapper);
        var request = LlmCacheStrategy.semanticRequest(command, objectMapper);
        LlmCachePolicyBO policy = snapshot == null ? null : snapshot.getCachePolicy();
        if (redisson == null || embedder == null || partition == null || request == null || policy == null
                || StringUtils.isBlank(policy.getEmbeddingModelKey()) || policy.getTtlSeconds() == null) return null;
        double[] vector = embedder.resolve(policy.getEmbeddingModelKey(), request.text());
        if (!validVector(vector)) return null;
        double threshold = policy.getSemanticThreshold() == null ? 0.95 : policy.getSemanticThreshold();
        if (!Double.isFinite(threshold) || threshold < 0.8 || threshold > 0.99) return null;
        List<String> entries = redisson.<String>getList(LIST_PREFIX + "v2:" + snapshot.getModelKey()).range(0, maximumEntries() - 1);
        LlmCacheEntryBO best = null; double bestScore = -1.0; Instant now = Instant.now(Clock.systemUTC());
        for (String raw : entries) {
            if (raw == null || raw.length() > responseLimit()) continue;
            try {
                LlmCacheEntryBO candidate = objectMapper.readValue(raw, LlmCacheEntryBO.class);
                if (!LlmCacheStrategy.fresh(candidate, partition, request.contextSha256(), policy.getTtlSeconds(), now)
                        || !validVector(candidate.getVector()) || candidate.getVector().length != vector.length) continue;
                double score = cosineSimilarity(vector, candidate.getVector());
                if (score >= threshold && score > bestScore) { best = candidate; bestScore = score; }
            } catch (JsonProcessingException invalid) {
                log.debug("Semantic cache entry rejected alias={}", snapshot.getModelKey());
            }
        }
        return best;
    }

    @Override
    public void store(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot, ObjectNode response) {
        RedissonClient redisson = redissonClient(); EmbeddingVectorResolver embedder = embeddingVectorResolver();
        String partition = LlmCacheStrategy.partition(command, snapshot, gatewayProperties, objectMapper);
        var request = LlmCacheStrategy.semanticRequest(command, objectMapper);
        LlmCachePolicyBO policy = snapshot == null ? null : snapshot.getCachePolicy();
        if (redisson == null || embedder == null || partition == null || request == null || response == null || policy == null
                || StringUtils.isBlank(policy.getEmbeddingModelKey()) || policy.getTtlSeconds() == null || policy.getTtlSeconds() < 1) return;
        double[] vector = embedder.resolve(policy.getEmbeddingModelKey(), request.text()); if (!validVector(vector)) return;
        int ttl = policy.getTtlSeconds(); Instant now = Instant.now(Clock.systemUTC());
        LlmCacheEntryBO entry = LlmCacheEntryBO.builder().createdAt(now).expiresAt(now.plusSeconds(ttl))
                .partitionSha256(partition).contextSha256(request.contextSha256()).responseJson(response.toString()).vector(vector.clone()).build();
        if (entry.getResponseJson().length() > responseLimit()) return;
        try {
            String raw = objectMapper.writeValueAsString(entry);
            if (raw.length() > responseLimit()) return;
            RList<String> list = redisson.getList(LIST_PREFIX + "v2:" + snapshot.getModelKey());
            list.add(0, raw); list.trim(0, maximumEntries() - 1); list.expire(Duration.ofSeconds(ttl));
        } catch (JsonProcessingException invalid) {
            log.warn("Semantic cache entry serialization failed alias={}", snapshot.getModelKey());
        }
    }

    @Override
    public void evictModel(String modelKey) {
        RedissonClient redisson = redissonClient();
        if (redisson != null && StringUtils.isNotBlank(modelKey)) redisson.getList(LIST_PREFIX + "v2:" + modelKey).delete();
    }

    public static double cosineSimilarity(double[] left, double[] right) {
        if (!validVector(left) || !validVector(right) || left.length != right.length) return -1.0;
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) { dot += left[i] * right[i]; a += left[i] * left[i]; b += right[i] * right[i]; }
        double denominator = Math.sqrt(a) * Math.sqrt(b);
        if (!Double.isFinite(dot) || !Double.isFinite(denominator) || denominator == 0.0) return -1.0;
        return Math.max(-1.0, Math.min(1.0, dot / denominator));
    }
    private static boolean validVector(double[] vector) {
        if (vector == null || vector.length == 0 || vector.length > 16000) return false;
        for (double value : vector) if (!Double.isFinite(value)) return false;
        return true;
    }
    private int maximumEntries() { return Math.max(1, Math.min(gatewayProperties.getCache().getSemanticMaxEntries(), 50000)); }
    private int responseLimit() { return (int) Math.min(Math.max(gatewayProperties.getMaxRequestBytes(), 1_048_576L), 8_388_608L); }
}
