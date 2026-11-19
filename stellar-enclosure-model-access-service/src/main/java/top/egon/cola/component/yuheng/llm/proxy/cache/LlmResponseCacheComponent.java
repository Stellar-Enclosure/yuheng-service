package top.egon.cola.component.yuheng.llm.proxy.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 中文说明：{@code LlmResponseCacheComponent} 是网关层响应缓存的统一调度门面，负责绕过判定、策略分发、Fail-Open 降级与 Cache Rate 指标采集。
 * English summary: {@code LlmResponseCacheComponent} orchestrates gateway response caching, bypass checks, strategy dispatch, Fail-Open degradation, and Cache Rate metrics.
 */
@Slf4j
@Component("llmResponseCacheComponent")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class LlmResponseCacheComponent {

    public enum CacheOutcome {
        HIT, MISS, BYPASS
    }

    public record CacheLookupResult(CacheOutcome outcome, ObjectNode cachedBody, int ttlSeconds) {
        public static CacheLookupResult bypass() {
            return new CacheLookupResult(CacheOutcome.BYPASS, null, 0);
        }

        public static CacheLookupResult miss(int ttlSeconds) {
            return new CacheLookupResult(CacheOutcome.MISS, null, ttlSeconds);
        }

        public static CacheLookupResult hit(ObjectNode body, int ttlSeconds) {
            return new CacheLookupResult(CacheOutcome.HIT, body, ttlSeconds);
        }
    }

    @FunctionalInterface
    public interface CacheWriter {
        void write(String key, LlmCacheEntryBO entry, int ttlSeconds);
    }

    private static final ObjectMapper PARSER = new ObjectMapper();

    @Qualifier(LlmGatewayProperties.BEAN_NAME)
    private final LlmGatewayProperties gatewayProperties;

    @Qualifier("exactLlmCacheStrategy")
    private final LlmCacheStrategy exactStrategy;

    @Qualifier("semanticLlmCacheStrategy")
    private final LlmCacheStrategy semanticStrategy;

    @Qualifier("meterRegistry")
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    private List<LlmCacheStrategy> strategiesOverride;
    private Function<String, LlmCacheEntryBO> inMemoryReader;
    private CacheWriter inMemoryWriter;
    private Consumer<String> inMemoryEvictor;

    public LlmResponseCacheComponent(
            LlmGatewayProperties gatewayProperties,
            List<LlmCacheStrategy> strategies,
            MeterRegistry meterRegistry,
            Function<String, LlmCacheEntryBO> inMemoryReader,
            CacheWriter inMemoryWriter,
            Consumer<String> inMemoryEvictor) {
        this.gatewayProperties = gatewayProperties;
        this.exactStrategy = null;
        this.semanticStrategy = null;
        this.meterRegistryProvider = staticProvider(meterRegistry);
        this.strategiesOverride = strategies == null ? List.of() : List.copyOf(strategies);
        this.inMemoryReader = inMemoryReader;
        this.inMemoryWriter = inMemoryWriter;
        this.inMemoryEvictor = inMemoryEvictor;
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

    private List<LlmCacheStrategy> activeStrategies() {
        if (strategiesOverride != null) {
            return strategiesOverride;
        }
        return List.of(exactStrategy, semanticStrategy);
    }

    public CacheLookupResult lookup(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
        if (!isEligible(command, snapshot)) {
            recordMetric(command == null ? null : command.getModel(), modeOf(snapshot), "BYPASS");
            return CacheLookupResult.bypass();
        }
        LlmCachePolicyBO policy = snapshot.getCachePolicy();
        int ttl = policy.getTtlSeconds() == null ? 3600 : policy.getTtlSeconds();
        try {
            LlmCacheEntryBO hit = null;
            if (inMemoryReader != null && policy.getMode() == LlmCacheModeEnum.EXACT) {
                ExactLlmCacheStrategy exact = findExactStrategy();
                if (exact != null) {
                    hit = inMemoryReader.apply(exact.buildCacheKey(command, snapshot));
                }
            }
            if (hit == null) {
                LlmCacheStrategy strategy = strategyFor(policy.getMode());
                if (strategy != null) {
                    hit = strategy.lookup(command, snapshot);
                }
            }
            String partition = LlmCacheStrategy.partition(command, snapshot, gatewayProperties, PARSER);
            var semantic = policy.getMode() == LlmCacheModeEnum.SEMANTIC ? LlmCacheStrategy.semanticRequest(command, PARSER) : null;
            String context = policy.getMode() == LlmCacheModeEnum.SEMANTIC
                    ? semantic == null ? null : semantic.contextSha256() : LlmCacheStrategy.digest(command.getPayload(), PARSER);
            java.time.Instant now = java.time.Instant.now(java.time.Clock.systemUTC());
            if (LlmCacheStrategy.fresh(hit, partition, context, ttl, now) && StringUtils.isNotBlank(hit.getResponseJson())) {
                JsonNode node = PARSER.readTree(hit.getResponseJson());
                if (node instanceof ObjectNode obj) {
                    recordMetric(command.getModel(), policy.getMode().wireValue(), "HIT");
                    return CacheLookupResult.hit(obj, (int) Math.max(0L, Math.min(ttl, java.time.Duration.between(now, hit.getExpiresAt()).getSeconds())));
                }
            }
            recordMetric(command.getModel(), policy.getMode().wireValue(), "MISS");
            return CacheLookupResult.miss(ttl);
        } catch (Exception ex) {
            log.warn("LLM response cache lookup failed, failing open alias={} errorType={}",
                    command.getModel(), ex.getClass().getSimpleName());
            recordMetric(command.getModel(), policy.getMode().wireValue(), "ERROR");
            return CacheLookupResult.bypass();
        }
    }

    public void storeIfEligible(
            LlmInvocationCommandDTO command,
            LlmModelSnapshotBO snapshot,
            ObjectNode responseBody) {
        if (responseBody == null || !isEligible(command, snapshot)) {
            return;
        }
        LlmCachePolicyBO policy = snapshot.getCachePolicy();
        int ttl = policy.getTtlSeconds() == null ? 3600 : policy.getTtlSeconds();
        try {
            if (inMemoryWriter != null && policy.getMode() == LlmCacheModeEnum.EXACT) {
                ExactLlmCacheStrategy exact = findExactStrategy();
                if (exact != null) {
                    inMemoryWriter.write(
                            exact.buildCacheKey(command, snapshot),
                            exactEntry(command, snapshot, responseBody, ttl),
                            ttl);
                }
            }
            LlmCacheStrategy strategy = strategyFor(policy.getMode());
            if (strategy != null) {
                strategy.store(command, snapshot, responseBody);
            }
        } catch (Exception ex) {
            log.warn("LLM response cache store failed, continuing alias={} errorType={}",
                    command.getModel(), ex.getClass().getSimpleName());
            recordMetric(command.getModel(), policy.getMode().wireValue(), "ERROR");
        }
    }

    private LlmCacheEntryBO exactEntry(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot, ObjectNode body, int ttl) {
        java.time.Instant now = java.time.Instant.now(java.time.Clock.systemUTC());
        return LlmCacheEntryBO.builder().createdAt(now).expiresAt(now.plusSeconds(ttl))
                .partitionSha256(LlmCacheStrategy.partition(command, snapshot, gatewayProperties, PARSER))
                .contextSha256(LlmCacheStrategy.digest(command.getPayload(), PARSER)).responseJson(body.toString()).build();
    }

    public void evictModel(String modelKey) {
        if (StringUtils.isBlank(modelKey)) {
            return;
        }
        if (inMemoryEvictor != null) {
            inMemoryEvictor.accept(modelKey);
        }
        for (LlmCacheStrategy strategy : activeStrategies()) {
            try {
                strategy.evictModel(modelKey);
            } catch (Exception ex) {
                log.warn("Failed to evict LLM cache for modelKey={} errorType={}",
                        modelKey, ex.getClass().getSimpleName());
            }
        }
    }

    private boolean isEligible(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
        try { return eligible(command, snapshot); }
        catch (RuntimeException invalidCacheContext) { return false; }
    }

    private boolean eligible(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
        if (gatewayProperties.getCache() == null || !gatewayProperties.getCache().isEnabled()) {
            return false;
        }
        if (!LlmCacheStrategy.hasTrustedContext(command, snapshot)) {
            return false;
        }
        LlmCachePolicyBO policy = snapshot.getCachePolicy();
        if (policy == null || policy.getMode() == null || policy.getMode() == LlmCacheModeEnum.NONE) {
            return false;
        }
        String cacheControl = command.getCacheControlHeader();
        if (StringUtils.isNotBlank(cacheControl)) {
            String lower = cacheControl.toLowerCase(Locale.ROOT);
            if (lower.contains("no-cache") || lower.contains("no-store")) {
                return false;
            }
        }
        JsonNode payload = command.getPayload();
        if (payload != null && payload.has("temperature") && payload.get("temperature").isNumber()) {
            double temp = payload.get("temperature").asDouble();
            double maxTemp = policy.getMaxCacheTemperature() == null ? 0.0 : policy.getMaxCacheTemperature();
            if (temp > maxTemp) {
                return false;
            }
        }
        if (policy.getTtlSeconds() == null || policy.getTtlSeconds() < 1) return false;
        if (policy.getMode() == LlmCacheModeEnum.SEMANTIC && LlmCacheStrategy.semanticRequest(command, PARSER) == null) return false;
        return LlmCacheStrategy.partition(command, snapshot, gatewayProperties, PARSER) != null;
    }

    private LlmCacheStrategy strategyFor(LlmCacheModeEnum mode) {
        return activeStrategies().stream().filter(s -> s != null && s.supports(mode)).findFirst().orElse(null);
    }

    private ExactLlmCacheStrategy findExactStrategy() {
        return activeStrategies().stream()
                .filter(ExactLlmCacheStrategy.class::isInstance)
                .map(ExactLlmCacheStrategy.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static String modeOf(LlmModelSnapshotBO snapshot) {
        if (snapshot == null || snapshot.getCachePolicy() == null || snapshot.getCachePolicy().getMode() == null) {
            return LlmCacheModeEnum.NONE.wireValue();
        }
        return snapshot.getCachePolicy().getMode().wireValue();
    }

    private void recordMetric(String alias, String mode, String outcome) {
        MeterRegistry meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if (meterRegistry == null || StringUtils.isBlank(alias)) {
            return;
        }
        meterRegistry.counter("yuheng.llm.response_cache.requests",
                "alias", alias, "mode", mode, "outcome", outcome).increment();
    }
}
