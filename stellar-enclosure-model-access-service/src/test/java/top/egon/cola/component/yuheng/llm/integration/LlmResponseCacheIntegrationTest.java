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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LlmResponseCacheIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

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

    @Test
    void exactCacheNeverSharesAResponseAcrossVerifiedCallers() {
        var store = new ConcurrentHashMap<String, LlmCacheEntryBO>();
        var props = enabledCacheProps();
        var exact = new ExactLlmCacheStrategy(props, JSON, (org.redisson.api.RedissonClient) null);
        var component = new LlmResponseCacheComponent(props, List.of(exact), new SimpleMeterRegistry(), store::get,
                (key, value, ttl) -> store.put(key, value), prefix -> {});
        var snapshot = modelWithPolicy(LlmCachePolicyBO.builder().mode(LlmCacheModeEnum.EXACT).build());
        var payload = JSON.createObjectNode().put("model", "company-chat");
        payload.putArray("messages").addObject().put("role", "user").put("content", "private answer");
        component.storeIfEligible(chatCmd(payload, null), snapshot, JSON.createObjectNode().put("answer", "caller-a"));
        var other = chatCmd(payload.deepCopy(), null).setCallerSubject("svc:other");
        assertThat(component.lookup(other, snapshot).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
    }

    @Test
    void exactCacheIncludesEveryResultSensitiveParameter() {
        var store = new ConcurrentHashMap<String, LlmCacheEntryBO>();
        var props = enabledCacheProps();
        var exact = new ExactLlmCacheStrategy(props, JSON, (org.redisson.api.RedissonClient) null);
        var component = new LlmResponseCacheComponent(props, List.of(exact), new SimpleMeterRegistry(), store::get,
                (key, value, ttl) -> store.put(key, value), prefix -> {});
        var snapshot = modelWithPolicy(LlmCachePolicyBO.builder().mode(LlmCacheModeEnum.EXACT).build());
        var payload = JSON.createObjectNode().put("model", "company-chat").put("max_tokens", 20).put("top_p", 0.5);
        payload.putArray("messages").addObject().put("role", "user").put("content", "same question");
        component.storeIfEligible(chatCmd(payload, null), snapshot, JSON.createObjectNode().put("answer", "twenty"));
        assertThat(component.lookup(chatCmd(payload.deepCopy().put("max_tokens", 200), null), snapshot).outcome())
                .isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
        assertThat(component.lookup(chatCmd(payload.deepCopy().put("top_p", 0.9), null), snapshot).outcome())
                .isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
    }

    @Test
    void exactCacheIsolatesTrustedTenantsPublicationsCallerProofsAndDeploymentLimits() {
        try (var f = new MemoryExact()) {
            var snapshot = modelWithPolicy(exactPolicy()); var command = chatCmd(question("question"), null);
            f.cache.storeIfEligible(command, snapshot, JSON.createObjectNode().put("id", "first"));
            assertThat(f.cache.lookup(command, snapshot).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.HIT);
            assertThat(f.cache.lookup(command, modelWithPolicy(exactPolicy()).setInstallationTenantId(8L)).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
            assertThat(f.cache.lookup(command, modelWithPolicy(exactPolicy()).setPublicationDigest("b".repeat(64))).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
            assertThat(f.cache.lookup(chatCmd(question("question"), null).setCallerCacheIdentity("d".repeat(64)), snapshot).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
            assertThat(f.cache.lookup(chatCmd(question("question"), null).setAllowedDeployments(Set.of(LlmDeploymentEnum.LOCAL, LlmDeploymentEnum.CLOUD)), snapshot).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
            assertThat(f.cache.lookup(command, modelWithPolicy(exactPolicy()).setRevision(2L)).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
        }
    }

    @Test
    void exactBodyCanonicalizationIgnoresObjectOrderButPreservesArrayOrderAndNullPresence() throws Exception {
        try (var f = new MemoryExact()) {
            var snapshot = modelWithPolicy(exactPolicy());
            var first = (ObjectNode) JSON.readTree("{\"model\":\"company-chat\",\"temperature\":0,\"messages\":[{\"role\":\"system\",\"content\":\"policy\"},{\"role\":\"user\",\"content\":\"question\"}],\"max_tokens\":20}");
            f.cache.storeIfEligible(chatCmd(first, null), snapshot, JSON.createObjectNode().put("id", "ordered"));
            var reordered = (ObjectNode) JSON.readTree("{\"max_tokens\":20.0,\"messages\":[{\"content\":\"policy\",\"role\":\"system\"},{\"content\":\"question\",\"role\":\"user\"}],\"temperature\":0.0,\"model\":\"company-chat\"}");
            assertThat(f.cache.lookup(chatCmd(reordered, null), snapshot).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.HIT);
            var reversed = first.deepCopy(); var list = reversed.withArray("messages"); var a = list.remove(0); list.add(a);
            assertThat(f.cache.lookup(chatCmd(reversed, null), snapshot).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
            assertThat(f.cache.lookup(chatCmd(first.deepCopy().putNull("seed"), null), snapshot).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.MISS);
        }
    }

    @Test
    void absentUnverifiedOrUnauthorizedContextNeverReadsOrWritesACacheEntry() {
        try (var f = new MemoryExact()) {
            var command = chatCmd(question("question"), null); var snapshot = modelWithPolicy(exactPolicy());
            for (var untrusted : List.of(modelWithPolicy(exactPolicy()).setInstallationTenantId(null), modelWithPolicy(exactPolicy()).setPublicationDigest(null), modelWithPolicy(exactPolicy()).setAllowedSubjects(List.of()))) {
                f.cache.storeIfEligible(command, untrusted, JSON.createObjectNode().put("id", "forbidden"));
                assertThat(f.cache.lookup(command, untrusted).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.BYPASS);
            }
            command.setCallerCacheIdentity(null); f.cache.storeIfEligible(command, snapshot, JSON.createObjectNode());
            assertThat(f.cache.lookup(command, snapshot).outcome()).isEqualTo(LlmResponseCacheComponent.CacheOutcome.BYPASS);
            assertThat(f.store).isEmpty();
        }
    }

    @Test
    void semanticCachePermitsOnlyTheLastUserTextToVaryWithCompleteCompatibleContext() {
        var redis = new MemoryRedis(); var props = enabledCacheProps();
        var semantic = new SemanticLlmCacheStrategy(props, JSON, redis.client, (model, text) -> new double[]{1, 0});
        var snapshot = modelWithPolicy(semanticPolicy()); var body = question("reset password").put("system", "policy-a").put("max_tokens", 20);
        body.putArray("tools").addObject().put("name", "lookup");
        semantic.store(chatCmd(body, null), snapshot, JSON.createObjectNode().put("id", "semantic"));
        var similar = body.deepCopy(); ((ObjectNode) similar.withArray("messages").get(0)).put("content", "forgot password");
        assertThat(semantic.lookup(chatCmd(similar, null), snapshot).getResponseJson()).contains("semantic");
        assertThat(semantic.lookup(chatCmd(similar.deepCopy().put("system", "policy-b"), null), snapshot)).isNull();
        assertThat(semantic.lookup(chatCmd(similar.deepCopy().put("max_tokens", 200), null), snapshot)).isNull();
        var changedTool = similar.deepCopy(); ((ObjectNode) changedTool.withArray("tools").get(0)).put("name", "other");
        assertThat(semantic.lookup(chatCmd(changedTool, null), snapshot)).isNull();
        var history = similar.deepCopy(); history.withArray("messages").insert(0, JSON.createObjectNode().put("role", "assistant").put("content", "different history"));
        assertThat(semantic.lookup(chatCmd(history, null), snapshot)).isNull();
        assertThat(semantic.lookup(chatCmd(similar, null).setCallerSubject("svc:other"), snapshot)).isNull();
        assertThat(semantic.lookup(chatCmd(similar, null), modelWithPolicy(semanticPolicy()).setInstallationTenantId(8L))).isNull();
        assertThat(semantic.lookup(chatCmd(similar, null), modelWithPolicy(semanticPolicy()).setPublicationDigest("b".repeat(64)))).isNull();
    }

    @Test
    void semanticEntryExpiresIndividuallyEvenWhenAnUnrelatedInsertionRefreshesTheListTtl() throws Exception {
        var redis = new MemoryRedis(); var props = enabledCacheProps();
        var semantic = new SemanticLlmCacheStrategy(props, JSON, redis.client, (model, text) -> new double[]{1, 0});
        var snapshot = modelWithPolicy(semanticPolicy()); var command = chatCmd(question("old question").put("max_tokens", 20), null);
        semantic.store(command, snapshot, JSON.createObjectNode().put("id", "expired"));
        var records = redis.lists.values().iterator().next(); var old = JSON.readValue(records.getFirst(), LlmCacheEntryBO.class);
        old.setCreatedAt(Instant.now().minusSeconds(600)).setExpiresAt(Instant.now().minusSeconds(300)); records.set(0, JSON.writeValueAsString(old));
        semantic.store(chatCmd(question("new question").put("max_tokens", 200), null), snapshot, JSON.createObjectNode().put("id", "fresh"));
        assertThat(records).hasSize(2); assertThat(semantic.lookup(command, snapshot)).isNull();
        assertThat(semantic.lookup(chatCmd(question("new question").put("max_tokens", 200), null), snapshot)).isNotNull();
    }

    @Test
    void semanticCacheRejectsStreamingNoCacheAmbiguousConversationAndInvalidVectors() {
        var redis = new MemoryRedis(); var props = enabledCacheProps();
        var semantic = new SemanticLlmCacheStrategy(props, JSON, redis.client, (model, text) -> new double[]{1, 0});
        var snapshot = modelWithPolicy(semanticPolicy());
        semantic.store(chatCmd(question("no cache"), "no-cache"), snapshot, JSON.createObjectNode());
        semantic.store(chatCmd(question("stream"), null).setStream(true), snapshot, JSON.createObjectNode());
        var assistantLast = question("question"); assistantLast.withArray("messages").addObject().put("role", "assistant").put("content", "pending");
        semantic.store(chatCmd(assistantLast, null), snapshot, JSON.createObjectNode()); assertThat(redis.lists).isEmpty();
        var invalid = new SemanticLlmCacheStrategy(props, JSON, redis.client, (model, text) -> new double[]{Double.NaN});
        invalid.store(chatCmd(question("invalid"), null), snapshot, JSON.createObjectNode()); assertThat(redis.lists).isEmpty();
        assertThat(SemanticLlmCacheStrategy.cosineSimilarity(new double[]{Double.POSITIVE_INFINITY}, new double[]{1})).isEqualTo(-1);
    }

    @Test
    void exactRedisEntryCarriesAndChecksIndividualExpiryAndRejectsLegacyEntry() throws Exception {
        var redis = new MemoryRedis(); var props = enabledCacheProps(); var exact = new ExactLlmCacheStrategy(props, JSON, redis.client);
        var snapshot = modelWithPolicy(exactPolicy()); var command = chatCmd(question("question"), null);
        exact.store(command, snapshot, JSON.createObjectNode().put("id", "exact")); assertThat(exact.lookup(command, snapshot)).isNotNull();
        var map = redis.maps.values().iterator().next(); String key = map.keySet().iterator().next();
        var entry = JSON.readValue(map.get(key), LlmCacheEntryBO.class); entry.setCreatedAt(Instant.now().minusSeconds(600)).setExpiresAt(Instant.now().minusSeconds(300));
        map.put(key, JSON.writeValueAsString(entry)); assertThat(exact.lookup(command, snapshot)).isNull();
        map.put(key, "{\"responseJson\":\"{}\",\"createdAt\":\"2026-11-19T00:00:00Z\"}"); assertThat(exact.lookup(command, snapshot)).isNull();
    }

    private static LlmCachePolicyBO exactPolicy() { return LlmCachePolicyBO.builder().mode(LlmCacheModeEnum.EXACT).ttlSeconds(300).build(); }
    private static LlmCachePolicyBO semanticPolicy() { return LlmCachePolicyBO.builder().mode(LlmCacheModeEnum.SEMANTIC).ttlSeconds(300).embeddingModelKey("local-bge-m3").semanticThreshold(0.95).build(); }
    private static ObjectNode question(String text) { var body = JSON.createObjectNode().put("model", "company-chat"); body.putArray("messages").addObject().put("role", "user").put("content", text); return body; }

    private static final class MemoryExact implements AutoCloseable {
        final Map<String, LlmCacheEntryBO> store = new ConcurrentHashMap<>(); final SimpleMeterRegistry registry = new SimpleMeterRegistry(); final LlmResponseCacheComponent cache;
        MemoryExact() { var props = enabledCacheProps(); var exact = new ExactLlmCacheStrategy(props, JSON, (org.redisson.api.RedissonClient) null); cache = new LlmResponseCacheComponent(props, List.of(exact), registry, store::get, (key, value, ttl) -> store.put(key, value), prefix -> {}); }
        @Override public void close() { registry.close(); }
    }
    /** Only Redis transport is a fixture; production strategies serialize, partition, compare and expire actual entries. */
    private static final class MemoryRedis {
        final org.redisson.api.RedissonClient client = mock(org.redisson.api.RedissonClient.class);
        final Map<String, List<String>> lists = new ConcurrentHashMap<>(); final Map<String, Map<String,String>> maps = new ConcurrentHashMap<>();
        @SuppressWarnings("unchecked") MemoryRedis() {
            when(client.<String>getList(anyString())).thenAnswer(call -> {
                var rows = lists.computeIfAbsent(call.getArgument(0), key -> new java.util.ArrayList<>());
                org.redisson.api.RList<String> list = mock(org.redisson.api.RList.class);
                when(list.readAll()).thenAnswer(ignored -> List.copyOf(rows));
                when(list.range(anyInt(), anyInt())).thenAnswer(query -> { int start = query.getArgument(0), end = query.getArgument(1); return rows.isEmpty() ? List.of() : List.copyOf(rows.subList(start, Math.min(rows.size(), end + 1))); });
                doAnswer(insert -> { rows.add(insert.getArgument(0), insert.getArgument(1)); return null; }).when(list).add(anyInt(), anyString());
                doAnswer(trim -> { int end = trim.getArgument(1); if (rows.size() > end + 1) rows.subList(end + 1, rows.size()).clear(); return null; }).when(list).trim(anyInt(), anyInt());
                return list;
            });
            when(client.<String,String>getMapCache(anyString())).thenAnswer(call -> {
                var rows = maps.computeIfAbsent(call.getArgument(0), key -> new ConcurrentHashMap<>());
                org.redisson.api.RMapCache<String,String> map = mock(org.redisson.api.RMapCache.class);
                when(map.get(anyString())).thenAnswer(get -> rows.get(get.getArgument(0)));
                when(map.put(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class))).thenAnswer(put -> rows.put(put.getArgument(0), put.getArgument(1)));
                return map;
            });
        }
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
                .setAllowedSubjects(List.of("svc:test", "svc:other"))
                .setInstallationTenantId(7L).setPublicationDigest("a".repeat(64))
                .setRoutes(List.of(new LlmModelSnapshotBO.RouteBO().setChannelKey("local-chat").setUpstreamModel("upstream-chat")
                        .setPriority(0).setWeight(1).setCapabilities(Set.of()).setChannel(new LlmModelSnapshotBO.ChannelBO()
                                .setChannelKey("local-chat").setDeployment(LlmDeploymentEnum.LOCAL).setProtocol(LlmProtocolEnum.OPENAI_CHAT)
                                .setBaseUrl("http://model.fixture.example").setEnabled(true).setRevision(1L))))
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
                .setCallerSubject("svc:test").setCallerCacheIdentity("c".repeat(64))
                .setAllowedDeployments(Set.of(LlmDeploymentEnum.LOCAL))
                .setRequiredCapabilities(Set.of());
    }
}
