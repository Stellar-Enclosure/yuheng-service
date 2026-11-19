package top.egon.cola.component.yuheng.llm.integration;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.yuheng.llm.config.LlmPersistenceContextComponent;
import top.egon.cola.component.yuheng.llm.config.LlmPersistenceProperties;
import top.egon.cola.component.yuheng.llm.proxy.dao.LlmModelDAO;
import top.egon.cola.component.yuheng.llm.proxy.dao.LlmChannelDAO;
import top.egon.cola.component.yuheng.llm.proxy.repository.impl.MpLlmConfigurationRepository;
import java.util.Collection;
import static org.mockito.Mockito.*;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmCapabilityEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmDeploymentEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmModelKindEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmProtocolEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.po.LlmChannelPO;
import top.egon.cola.component.yuheng.llm.proxy.domain.po.LlmModelPO;
import top.egon.cola.component.yuheng.llm.proxy.repository.LlmModelSnapshotConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmModelSnapshotConverterTest {

    private static final Instant MODEL_CREATED_AT = Instant.parse("2026-09-21T08:00:00.123456Z");

    private final LlmModelSnapshotConverter converter = new LlmModelSnapshotConverter();

    @Test
    @DisplayName("MapStruct projection preserves nullable chat dimensions, embedding space and unauthenticated local secretRef")
    void projectionPreservesNullablePersistenceFieldsAndResolvesTheDeclaredRoute() {
        LlmModelPO model = LlmModelPO.builder()
                .modelKey("company-chat")
                .name("Company Chat")
                .createTime(MODEL_CREATED_AT)
                .kind(LlmModelKindEnum.CHAT)
                .enabled(Boolean.TRUE)
                .dimensions(null)
                .embeddingSpaceId(null)
                .revision(7L)
                .build();
        LlmChannelPO channel = LlmChannelPO.builder()
                .channelKey("local-main")
                .name("Local Main")
                .deployment(LlmDeploymentEnum.LOCAL)
                .protocol(LlmProtocolEnum.OPENAI_CHAT)
                .baseUrl("http://127.0.0.1:8000/v1")
                .secretRef(null)
                .enabled(Boolean.TRUE)
                .connectTimeoutMs(2_000)
                .headerTimeoutMs(30_000)
                .idleTimeoutMs(60_000)
                .totalTimeoutMs(120_000)
                .maxConcurrent(4)
                .revision(3L)
                .build();
        LlmModelSnapshotBO.RouteBO route = converter.toRoute(
                "local-main", "qwen-local", 1, 100, Set.of(LlmCapabilityEnum.TEXT));

        LlmModelSnapshotBO snapshot = converter.toTarget(converter.toProjection(
                model,
                List.of(LlmProtocolEnum.OPENAI_CHAT),
                List.of("svc:wiki-indexer"),
                List.of(converter.withChannel(route, converter.toChannel(channel)))));

        assertThat(snapshot.getModelKey()).isEqualTo("company-chat");
        assertThat(snapshot.getCreatedAt()).isEqualTo(MODEL_CREATED_AT);
        assertThat(snapshot.getDimensions()).isNull();
        assertThat(snapshot.getEmbeddingSpaceId()).isNull();
        assertThat(snapshot.getProtocols()).containsExactly(LlmProtocolEnum.OPENAI_CHAT);
        assertThat(snapshot.getAllowedSubjects()).containsExactly("svc:wiki-indexer");
        assertThat(snapshot.getRoutes()).hasSize(1);
        assertThat(snapshot.getRoutes().getFirst().getChannel().getSecretRef()).isNull();
        assertThat(snapshot.getRoutes().getFirst().getChannel().getDeployment()).isEqualTo(LlmDeploymentEnum.LOCAL);
    }

    @Test
    @DisplayName("A route to a missing channel remains unresolved instead of widening to another channel")
    void projectionPreservesAnUnresolvedRoute() {
        LlmModelSnapshotBO.RouteBO route = converter.toRoute(
                "missing-channel", "qwen-local", 1, 100, Set.of(LlmCapabilityEnum.TEXT));

        assertThat(converter.withChannel(route, null).getChannel()).isNull();
    }

    @Test
    void publicationIsTenantBoundAndIncludesActualChannelRevision() {
        LlmModelSnapshotBO source = policy();
        LlmModelSnapshotBO published = converter.toPublication(source, 7L);
        assertThat(published.getInstallationTenantId()).isEqualTo(7L);
        assertThat(published.getPublicationDigest()).matches("[0-9a-f]{64}");
        assertThat(published.getRoutes().getFirst().getChannel().getRevision()).isEqualTo(3L);
        assertThat(converter.toPublication(source, 8L).getPublicationDigest())
                .isNotEqualTo(published.getPublicationDigest());
        source.getRoutes().getFirst().getChannel().setRevision(4L);
        // A getter never exposes the repository's nested objects; update through a new route list.
        LlmModelSnapshotBO.RouteBO changed = source.getRoutes().getFirst();
        changed.getChannel().setRevision(4L);
        source.setRoutes(List.of(changed));
        assertThat(converter.toPublication(source, 7L).getPublicationDigest())
                .isNotEqualTo(published.getPublicationDigest());
    }

    @Test
    void completePolicyIdentityChangesForRoutingAuthorizationCacheAndChannelBudgets() {
        String digest = converter.toPublication(policy(), 7L).getPublicationDigest();
        assertThat(converter.toPublication(policy().setAllowedSubjects(List.of("svc:other")), 7L)
                .getPublicationDigest()).isNotEqualTo(digest);
        LlmModelSnapshotBO cacheChange = policy();
        var cache = cacheChange.getCachePolicy();
        cache.setTtlSeconds(120);
        cacheChange.setCachePolicy(cache);
        assertThat(converter.toPublication(cacheChange, 7L).getPublicationDigest()).isNotEqualTo(digest);
        LlmModelSnapshotBO channelChange = policy();
        var route = channelChange.getRoutes().getFirst();
        var channel = route.getChannel();
        channel.setTotalTimeoutMs(30000);
        route.setChannel(channel);
        channelChange.setRoutes(List.of(route));
        assertThat(converter.toPublication(channelChange, 7L).getPublicationDigest()).isNotEqualTo(digest);
    }

    @Test
    void equivalentCapabilitySetOrderDoesNotChangePublicationIdentity() {
        LlmModelSnapshotBO left = policy();
        var route = left.getRoutes().getFirst();
        route.setCapabilities(new LinkedHashSet<>(List.of(LlmCapabilityEnum.TEXT, LlmCapabilityEnum.FUNCTION_TOOLS)));
        left.setRoutes(List.of(route));
        LlmModelSnapshotBO right = policy();
        route = right.getRoutes().getFirst();
        route.setCapabilities(new LinkedHashSet<>(List.of(LlmCapabilityEnum.FUNCTION_TOOLS, LlmCapabilityEnum.TEXT)));
        right.setRoutes(List.of(route));
        assertThat(converter.toPublication(left, 7L).getPublicationDigest())
                .isEqualTo(converter.toPublication(right, 7L).getPublicationDigest());
    }

    @Test
    void frozenPublicationDoesNotLeakAnyMutablePolicyOrNestedChannel() {
        LlmModelSnapshotBO original = policy();
        LlmModelSnapshotBO published = converter.toPublication(original, 7L);
        String digest = published.getPublicationDigest();
        original.setEnabled(false);
        var returned = published.getRoutes().getFirst();
        returned.setWeight(999);
        returned.getChannel().setEnabled(false);
        published.getCachePolicy().setTtlSeconds(99);
        assertThat(published.getEnabled()).isTrue();
        assertThat(published.getRoutes().getFirst().getWeight()).isEqualTo(100);
        assertThat(published.getRoutes().getFirst().getChannel().getEnabled()).isTrue();
        assertThat(published.getCachePolicy().getTtlSeconds()).isEqualTo(3600);
        assertThat(published.getPublicationDigest()).isEqualTo(digest);
        assertThatThrownBy(() -> published.setEnabled(false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> published.setRoutes(List.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publicationRejectsMissingAuthorityUnversionedChannelsAndUnboundedPolicy() {
        assertThatThrownBy(() -> converter.toPublication(policy(), 0L)).isInstanceOf(IllegalArgumentException.class);
        LlmModelSnapshotBO invalid = policy();
        var route = invalid.getRoutes().getFirst();
        var channel = route.getChannel();
        channel.setRevision(null);
        route.setChannel(channel);
        invalid.setRoutes(List.of(route));
        LlmModelSnapshotBO unversioned = invalid;
        assertThatThrownBy(() -> converter.toPublication(unversioned, 7L)).isInstanceOf(IllegalStateException.class);
        invalid = policy().setAllowedSubjects(new ArrayList<>(java.util.Collections.nCopies(101, "svc:caller")));
        LlmModelSnapshotBO oversized = invalid;
        assertThatThrownBy(() -> converter.toPublication(oversized, 7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void authorityStartsBoundedReadOnlyRepeatableReadAfterTrustedMdcAndClosesBeforeReturn() {
        var modelDAO = mock(LlmModelDAO.class);
        var channelDAO = mock(LlmChannelDAO.class);
        var manager = new ReadTransactionManager();
        var transactions = readTemplate(manager);
        var repository = authority(modelDAO, channelDAO, transactions);
        MDC.put("tenantId", "999");
        MDC.put("userId", "original");
        try {
            when(modelDAO.selectActiveByModelKey("company-chat")).thenAnswer(ignored -> {
                assertReadContext();
                return persistedModel("company-chat", "local-main");
            });
            when(channelDAO.selectActiveByChannelKeys(any())).thenAnswer(ignored -> {
                assertReadContext();
                return List.of(persistedChannel("local-main"));
            });
            var snapshot = repository.findSnapshot("company-chat").orElseThrow();
            assertThat(snapshot.getInstallationTenantId()).isEqualTo(7L);
            assertThat(snapshot.getPublicationDigest()).matches("[0-9a-f]{64}");
            assertThat(manager.commits).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(MDC.get("tenantId")).isEqualTo("999");
            assertThat(MDC.get("userId")).isEqualTo("original");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void invalidReadTransactionConfigurationAndAmbientTransactionsFailBeforeSql() {
        var modelDAO = mock(LlmModelDAO.class);
        var channelDAO = mock(LlmChannelDAO.class);
        var template = readTemplate(new ReadTransactionManager());
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var repository = authority(modelDAO, channelDAO, template);
        assertThatThrownBy(() -> repository.findSnapshot("company-chat"))
                .hasMessage("LLM_SNAPSHOT_READ_TRANSACTION_REQUIRED");
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(repository::loadPublication).hasMessage("LLM_SNAPSHOT_READ_TRANSACTION_REQUIRED");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(modelDAO, channelDAO);
    }

    @Test
    void failedChannelReadRollsBackAndNeverReturnsMixedOrEmptySuccess() {
        var modelDAO = mock(LlmModelDAO.class);
        var channelDAO = mock(LlmChannelDAO.class);
        var manager = new ReadTransactionManager();
        var repository = authority(modelDAO, channelDAO, readTemplate(manager));
        when(modelDAO.selectActiveByModelKey("company-chat")).thenReturn(persistedModel("company-chat", "local-main"));
        when(channelDAO.selectActiveByChannelKeys(any())).thenThrow(new IllegalStateException("synthetic-store"));
        assertThatThrownBy(() -> repository.findSnapshot("company-chat")).isInstanceOf(IllegalStateException.class);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(MDC.get("tenantId")).isNull();
    }

    @Test
    void fullPublicationSharesOneTransactionAndBatchesAtMost64ExactChannelKeys() {
        var modelDAO = mock(LlmModelDAO.class);
        var channelDAO = mock(LlmChannelDAO.class);
        var manager = new ReadTransactionManager();
        var repository = authority(modelDAO, channelDAO, readTemplate(manager));
        var models = new ArrayList<LlmModelPO>();
        for (int i = 0; i < 65; i++) {
            models.add(persistedModel("model-" + i, "channel-" + i));
        }
        when(modelDAO.selectActiveCatalog(1001)).thenReturn(models);
        when(channelDAO.selectActiveByChannelKeys(any())).thenAnswer(call -> {
            assertReadContext();
            Collection<String> keys = call.getArgument(0);
            assertThat(keys).isNotEmpty().hasSizeLessThanOrEqualTo(64);
            return keys.stream().map(LlmModelSnapshotConverterTest::persistedChannel).toList();
        });
        assertThat(repository.loadPublication()).hasSize(65).allSatisfy(snapshot ->
                assertThat(snapshot.getPublicationDigest()).matches("[0-9a-f]{64}"));
        verify(channelDAO, times(2)).selectActiveByChannelKeys(any());
        assertThat(manager.commits).isEqualTo(1);
    }

    @Test
    void fullPublicationRejectsTruncationInsteadOfAuthorizingOnlyFirst1000Models() {
        var modelDAO = mock(LlmModelDAO.class);
        var channelDAO = mock(LlmChannelDAO.class);
        var repository = authority(modelDAO, channelDAO, readTemplate(new ReadTransactionManager()));
        when(modelDAO.selectActiveCatalog(1001)).thenReturn(java.util.Collections.nCopies(1001,
                persistedModel("company-chat", "local-main")));
        assertThatThrownBy(repository::loadPublication).hasMessage("YUHENG_LLM_CONFIGURATION_INVALID");
        verifyNoInteractions(channelDAO);
    }

    private MpLlmConfigurationRepository authority(LlmModelDAO models, LlmChannelDAO channels,
            TransactionTemplate template) {
        var context = new LlmPersistenceContextComponent(new LlmPersistenceProperties()
                .setTenantId(7L).setServiceUserId("model-role"), new EgonColaMybatisPlusProperties());
        return new MpLlmConfigurationRepository(models, channels, context, converter, template);
    }

    private static TransactionTemplate readTemplate(ReadTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(3);
        return template;
    }

    private static void assertReadContext() {
        assertThat(MDC.get("tenantId")).isEqualTo("7");
        assertThat(MDC.get("userId")).isEqualTo("model-role");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private static LlmModelPO persistedModel(String alias, String channel) {
        return LlmModelPO.builder().modelKey(alias).name("Synthetic Model").createTime(MODEL_CREATED_AT)
                .kind(LlmModelKindEnum.CHAT).enabled(true).revision(1L).protocols("[\"OPENAI_CHAT\"]")
                .allowedSubjects("[\"svc:caller\"]")
                .routes("[{\"channelKey\":\"" + channel + "\",\"upstreamModel\":\"actual\",\"priority\":1,\"weight\":100,\"capabilities\":[\"TEXT\"]}]")
                .build();
    }

    private static LlmChannelPO persistedChannel(String channel) {
        return LlmChannelPO.builder().channelKey(channel).deployment(LlmDeploymentEnum.LOCAL)
                .protocol(LlmProtocolEnum.OPENAI_CHAT).baseUrl("http://127.0.0.1:8000/v1").enabled(true).revision(3L)
                .connectTimeoutMs(1000).headerTimeoutMs(10000).idleTimeoutMs(10000).totalTimeoutMs(20000)
                .maxConcurrent(4).build();
    }

    private static final class ReadTransactionManager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertThat(MDC.get("tenantId")).isEqualTo("7");
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getTimeout()).isEqualTo(3);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }

    private static LlmModelSnapshotBO policy() {
        return LlmModelSnapshotBO.builder().modelKey("company-chat").name("Company Chat")
                .createdAt(MODEL_CREATED_AT).kind(LlmModelKindEnum.CHAT).enabled(true)
                .protocols(List.of(LlmProtocolEnum.OPENAI_CHAT)).allowedSubjects(List.of("svc:caller"))
                .revision(7L).routes(List.of(LlmModelSnapshotBO.RouteBO.builder()
                        .channelKey("local-main").upstreamModel("qwen-local").priority(1).weight(100)
                        .capabilities(Set.of(LlmCapabilityEnum.TEXT))
                        .channel(LlmModelSnapshotBO.ChannelBO.builder().channelKey("local-main")
                                .deployment(LlmDeploymentEnum.LOCAL).protocol(LlmProtocolEnum.OPENAI_CHAT)
                                .baseUrl("http://127.0.0.1:8000/v1").enabled(true).revision(3L)
                                .connectTimeoutMs(1000).headerTimeoutMs(10000).idleTimeoutMs(10000)
                                .totalTimeoutMs(20000).maxConcurrent(4).build()).build())).build();
    }

}
