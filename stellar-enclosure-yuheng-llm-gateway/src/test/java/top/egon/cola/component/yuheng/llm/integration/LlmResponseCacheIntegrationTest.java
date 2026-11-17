package top.egon.cola.component.yuheng.llm.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import top.egon.cola.component.yuheng.llm.config.LlmGatewayProperties;
import top.egon.cola.component.yuheng.llm.proxy.cache.ExactLlmCacheStrategy;
import top.egon.cola.component.yuheng.llm.proxy.cache.LlmCacheStrategy;
import top.egon.cola.component.yuheng.llm.proxy.cache.LlmResponseCacheComponent;
import top.egon.cola.component.yuheng.llm.proxy.cache.SemanticLlmCacheStrategy;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmCacheEntryBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmCachePolicyBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.dto.LlmInvocationCommandDTO;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmCacheModeEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmDeploymentEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmModelKindEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmProtocolEnum;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class LlmResponseCacheIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("EXACT 缓存：首次 MISS 写入后二次命中 HIT，携带 Cache-Control: no-cache 强制穿透 BYPASS")
    void exactCacheHitsAndRespectsNoCacheHeader() {
        Map<String, LlmCacheEntryBO> store = new ConcurrentHashMap<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmGatewayProperties props = enabledCacheProps();

        ExactLlmCacheStrategy exact = new ExactLlmCacheStrategy(props, JSON, (org.redisson.api.RedissonClient) null);
        LlmResponseCacheComponent component = new LlmResponseCacheComponent(
                props, List.of(exact), registry, store::get, (k, v, ttl) -> store.put(k, v), prefix -> {});

        LlmModelSnapshotBO snapshot = modelWithPolicy(LlmCachePolicyBO.builder()
                .mode(LlmCacheModeEnum.EXACT)
                .ttlSeconds(300)
                .maxCacheTemperature(0.0)
                .build());

        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", "company-chat");
        payload.put("temperature", 0.0);
        payload.putArray("messages").addObject().put("role", "user").put("content", "what is cache rate");

        LlmInvocationCommandDTO cmd = chatCmd(payload, null);
        LlmResponseCacheComponent.CacheLookupResult first = component.lookup(cmd, snapshot);
        assertThat(first.outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);

        ObjectNode response = JSON.createObjectNode().put("id", "resp-1").put("model", "company-chat");
        component.storeIfEligible(cmd, snapshot, response);

        LlmResponseCacheComponent.CacheLookupResult second = component.lookup(cmd, snapshot);
        assertThat(second.outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.HIT);
        assertThat(second.cachedBody().path("id").asText()).isEqualTo("resp-1");

        LlmInvocationCommandDTO bypassCmd = chatCmd(payload, "no-cache");
        LlmResponseCacheComponent.CacheLookupResult third = component.lookup(bypassCmd, snapshot);
        assertThat(third.outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.BYPASS);

        assertThat(registry.counter("yuheng.llm.response_cache.requests",
                "alias", "company-chat", "mode", "EXACT", "outcome", "HIT").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SEMANTIC 缓存：余弦相似度达标命中，低于阈值 MISS")
    void semanticCacheMatchesAboveThresholdOnly() {
        List<LlmCacheEntryBO> semanticEntries = new java.util.concurrent.CopyOnWriteArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmGatewayProperties props = enabledCacheProps();

        SemanticLlmCacheStrategy.EmbeddingVectorResolver stubEmbedder = (modelKey, text) -> {
            if (text.contains("reset password") || text.contains("forgot password")) {
                return new double[]{0.99, 0.14};
            }
            return new double[]{0.0, 1.0};
        };

        SemanticLlmCacheStrategy semantic = new SemanticLlmCacheStrategy(props, JSON, null, stubEmbedder);
        LlmResponseCacheComponent component = new LlmResponseCacheComponent(
                props, List.of(semantic), registry,
                k -> null, (k, v, ttl) -> {}, prefix -> {});

        LlmModelSnapshotBO snapshot = modelWithPolicy(LlmCachePolicyBO.builder()
                .mode(LlmCacheModeEnum.SEMANTIC)
                .ttlSeconds(300)
                .maxCacheTemperature(0.2)
                .semanticThreshold(0.95)
                .embeddingModelKey("local-bge-m3")
                .build());

        semanticEntries.add(LlmCacheEntryBO.builder()
                .createdAt(Instant.now())
                .responseJson("{\"id\":\"sem-1\",\"model\":\"company-chat\"}")
                .vector(new double[]{1.0, 0.0})
                .build());

        double simHit = SemanticLlmCacheStrategy.cosineSimilarity(
                new double[]{0.99, 0.14}, semanticEntries.get(0).getVector());
        double simMiss = SemanticLlmCacheStrategy.cosineSimilarity(
                new double[]{0.0, 1.0}, semanticEntries.get(0).getVector());
        assertThat(simHit).isGreaterThanOrEqualTo(0.95);
        assertThat(simMiss).isLessThan(0.95);
        assertThat(semantic.supports(LlmCacheModeEnum.SEMANTIC)).isTrue();
    }

    @Test
    @DisplayName("Redis 缓存异常时 Fail-Open 降级为 BYPASS 不中断推理")
    void redisFailureDegradesGracefullyToBypass() {
        LlmGatewayProperties props = enabledCacheProps();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmCacheStrategy failing = new LlmCacheStrategy() {
            @Override
            public boolean supports(LlmCacheModeEnum mode) {
                return mode == LlmCacheModeEnum.EXACT;
            }

            @Override
            public LlmCacheEntryBO lookup(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot) {
                throw new IllegalStateException("Redis connection timeout");
            }

            @Override
            public void store(LlmInvocationCommandDTO command, LlmModelSnapshotBO snapshot, ObjectNode response) {
                throw new IllegalStateException("Redis connection timeout");
            }

            @Override
            public void evictModel(String modelKey) {
            }
        };
        LlmResponseCacheComponent component = new LlmResponseCacheComponent(
                props, List.of(failing), registry, k -> null, (k, v, ttl) -> {}, prefix -> {});
        LlmModelSnapshotBO snapshot = modelWithPolicy(LlmCachePolicyBO.builder()
                .mode(LlmCacheModeEnum.EXACT)
                .build());
        ObjectNode payload = JSON.createObjectNode().put("model", "company-chat");
        payload.putArray("messages").addObject().put("role", "user").put("content", "hi");

        LlmResponseCacheComponent.CacheLookupResult result = component.lookup(chatCmd(payload, null), snapshot);
        assertThat(result.outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.BYPASS);
        assertThat(registry.counter("yuheng.llm.response_cache.requests",
                "alias", "company-chat", "mode", "EXACT", "outcome", "ERROR").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("application.yml 与 application-local.yml 保持相同的 yuheng.llm.cache 键结构")
    void profileYamlFilesHaveIdenticalCacheKeyStructure() {
        Properties prod = loadYaml("application.yml");
        Properties local = loadYaml("application-local.yml");
        List<String> prodCacheKeys = prod.stringPropertyNames().stream()
                .filter(k -> k.startsWith("yuheng.llm.cache.")).sorted().toList();
        List<String> localCacheKeys = local.stringPropertyNames().stream()
                .filter(k -> k.startsWith("yuheng.llm.cache.")).sorted().toList();
        assertThat(prodCacheKeys).isNotEmpty();
        assertThat(localCacheKeys).isEqualTo(prodCacheKeys);
    }

    private static Properties loadYaml(String path) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(path));
        return factory.getObject();
    }

    private static LlmGatewayProperties enabledCacheProps() {
        return new LlmGatewayProperties()
                .setEnabled(true)
                .setCache(new LlmGatewayProperties.Cache()
                        .setEnabled(true)
                        .setTimeoutMs(200)
                        .setSemanticMaxEntries(2000));
    }

    private static LlmModelSnapshotBO modelWithPolicy(LlmCachePolicyBO policy) {
        return new LlmModelSnapshotBO()
                .setModelKey("company-chat")
                .setName("Company Chat")
                .setCreatedAt(Instant.now())
                .setKind(LlmModelKindEnum.CHAT)
                .setEnabled(true)
                .setProtocols(List.of(LlmProtocolEnum.OPENAI_CHAT))
                .setAllowedSubjects(List.of("svc:test"))
                .setRevision(1L)
                .setCachePolicy(policy);
    }

    private static LlmInvocationCommandDTO chatCmd(ObjectNode payload, String cacheControl) {
        return new LlmInvocationCommandDTO()
                .setProtocol(LlmProtocolEnum.OPENAI_CHAT)
                .setModel("company-chat")
                .setStream(Boolean.FALSE)
                .setPayload(payload)
                .setCacheControlHeader(cacheControl)
                .setCallerSubject("svc:test")
                .setAllowedDeployments(Set.of(LlmDeploymentEnum.LOCAL))
                .setRequiredCapabilities(Set.of());
    }
}
