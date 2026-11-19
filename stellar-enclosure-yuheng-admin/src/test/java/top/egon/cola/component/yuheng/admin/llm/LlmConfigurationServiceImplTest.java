package top.egon.cola.component.yuheng.admin.llm;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.yuheng.admin.llm.converter.LlmCachePolicyConverter;
import top.egon.cola.component.yuheng.admin.llm.domain.bo.LlmChannelBO;
import top.egon.cola.component.yuheng.admin.llm.domain.bo.LlmModelBO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmChannelCommandDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmModelCommandDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.dto.LlmRouteBindingDTO;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmCapabilityEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmDeploymentEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmModelKindEnum;
import top.egon.cola.component.yuheng.admin.llm.domain.enums.LlmProtocolEnum;
import top.egon.cola.component.yuheng.admin.llm.repository.LlmConfigurationRepository;
import top.egon.cola.component.yuheng.admin.llm.service.impl.LlmConfigurationServiceImpl;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LlmConfigurationServiceImplTest {
    private final LlmConfigurationRepository configuration = mock(LlmConfigurationRepository.class);
    private final GatewayAuditLogRepository audits = mock(GatewayAuditLogRepository.class);
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RTopic topic = mock(RTopic.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
    private final TransactionTemplate transactions = new TransactionTemplate(new LocalTransactions());
    private final AdminActor actor = new AdminActor("operator", AdminActorTypeEnum.USER, Set.of("yuheng:llm:write"), Set.of());
    private final RequestAuditContext request = new RequestAuditContext("synthetic-request", "synthetic-trace");
    private final LlmConfigurationServiceImpl service = new LlmConfigurationServiceImpl(configuration, audits,
            Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC), new LlmCachePolicyConverter(), provider);

    LlmConfigurationServiceImplTest() {
        doAnswer(call -> {
            Consumer<RedissonClient> callback = call.getArgument(0);
            callback.accept(redis);
            return null;
        }).when(provider).ifAvailable(any());
        when(redis.getTopic("yuheng:llm:cache:invalidate")).thenReturn(topic);
        when(configuration.findModelsByChannelKey(anyString())).thenReturn(List.of());
        when(configuration.findChannelsByKeys(any())).thenReturn(List.of(channel()));
        when(configuration.saveChannel(any())).thenAnswer(call -> ((LlmChannelBO) call.getArgument(0)).setRevision(1L));
        when(configuration.saveModel(any())).thenAnswer(call -> ((LlmModelBO) call.getArgument(0)).setRevision(1L));
    }

    @Test
    void modelHintIsOnlyDeliveredAfterSuccessfulCommit() {
        try (MockedStatic<SnowflakeIdGenerator> ids = mockStatic(SnowflakeIdGenerator.class)) {
            ids.when(SnowflakeIdGenerator::nextId).thenReturn("1001");
            transactions.executeWithoutResult(status -> {
                assertThat(service.replaceModel("company-chat", modelCommand(), actor, request).getRevision()).isEqualTo(1L);
                verifyNoInteractions(provider);
                verify(audits).save(any());
            });
            verify(topic).publish("company-chat");
        }
    }

    @Test
    void channelHintReconcilesEveryDependentModelOnlyAfterCommit() {
        try (MockedStatic<SnowflakeIdGenerator> ids = mockStatic(SnowflakeIdGenerator.class)) {
            ids.when(SnowflakeIdGenerator::nextId).thenReturn("1002");
            transactions.executeWithoutResult(status -> {
                service.replaceChannel("local-main", channelCommand(), actor, request);
                verifyNoInteractions(provider);
                verify(audits).save(any());
            });
            verify(topic).publish("*");
        }
    }

    @Test
    void rolledBackModelAndChannelWritesPublishNoHint() {
        try (MockedStatic<SnowflakeIdGenerator> ids = mockStatic(SnowflakeIdGenerator.class)) {
            ids.when(SnowflakeIdGenerator::nextId).thenReturn("1003");
            transactions.executeWithoutResult(status -> {
                service.replaceModel("company-chat", modelCommand(), actor, request);
                service.replaceChannel("local-main", channelCommand(), actor, request);
                status.setRollbackOnly();
            });
            verifyNoInteractions(provider, topic);
        }
    }

    @Test
    void hintFailureCannotChangeTheCommittedWriteOrInventDeliverySuccess() {
        when(topic.publish("company-chat")).thenThrow(new IllegalStateException("synthetic unavailable"));
        try (MockedStatic<SnowflakeIdGenerator> ids = mockStatic(SnowflakeIdGenerator.class)) {
            ids.when(SnowflakeIdGenerator::nextId).thenReturn("1004");
            var saved = transactions.execute(status -> service.replaceModel("company-chat", modelCommand(), actor, request));
            assertThat(saved.getRevision()).isEqualTo(1L);
            verify(topic, times(1)).publish("company-chat");
            verify(configuration, times(1)).saveModel(any());
        }
    }

    @Test
    void optionalRedisBeanResolutionFailureAlsoCannotUndoACommittedWrite() {
        doThrow(new IllegalStateException("synthetic provider unavailable")).when(provider).ifAvailable(any());
        try (MockedStatic<SnowflakeIdGenerator> ids = mockStatic(SnowflakeIdGenerator.class)) {
            ids.when(SnowflakeIdGenerator::nextId).thenReturn("1005");
            var saved = transactions.execute(status -> service.replaceModel("company-chat", modelCommand(), actor, request));
            assertThat(saved.getRevision()).isEqualTo(1L);
            verifyNoInteractions(topic);
        }
    }

    private static LlmModelCommandDTO modelCommand() {
        var route = LlmRouteBindingDTO.builder().channelKey("local-main").upstreamModel("actual")
                .priority(1).weight(100).capabilities(Set.of(LlmCapabilityEnum.TEXT)).build();
        return LlmModelCommandDTO.builder().key("company-chat").name("Synthetic model").kind(LlmModelKindEnum.CHAT)
                .protocols(List.of(LlmProtocolEnum.OPENAI_CHAT)).enabled(true).allowedSubjects(List.of("svc:caller"))
                .routes(List.of(route)).expectedRevision(0L).build();
    }

    private static LlmChannelCommandDTO channelCommand() {
        return LlmChannelCommandDTO.builder().key("local-main").name("Synthetic channel")
                .deployment(LlmDeploymentEnum.LOCAL).protocol(LlmProtocolEnum.OPENAI_CHAT)
                .baseUrl("http://127.0.0.1:8000/v1").enabled(true).connectTimeoutMs(1000).headerTimeoutMs(10000)
                .idleTimeoutMs(10000).totalTimeoutMs(20000).maxConcurrent(4).expectedRevision(0L).build();
    }

    private static LlmChannelBO channel() {
        return LlmChannelBO.builder().channelKey("local-main").name("Synthetic channel")
                .deployment(LlmDeploymentEnum.LOCAL).protocol(LlmProtocolEnum.OPENAI_CHAT)
                .baseUrl("http://127.0.0.1:8000/v1").enabled(true).connectTimeoutMs(1000).headerTimeoutMs(10000)
                .idleTimeoutMs(10000).totalTimeoutMs(20000).maxConcurrent(4).revision(1L).build();
    }

    private static final class LocalTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
