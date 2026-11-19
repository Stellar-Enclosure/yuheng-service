package top.egon.cola.component.yuheng.llm.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.aop.support.AopUtils;
import jakarta.validation.ConstraintViolationException;
import org.redisson.api.RedissonClient;
import org.redisson.api.RTopic;
import org.redisson.api.listener.MessageListener;
import top.egon.cola.component.yuheng.llm.proxy.cache.LlmCacheInvalidationListener;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.llm.proxy.domain.bo.LlmModelSnapshotBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.exception.LlmInvocationException;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmCapabilityEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmDeploymentEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmModelKindEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmProtocolEnum;
import top.egon.cola.component.yuheng.llm.proxy.repository.LlmConfigurationSnapshotCoordinator;
import top.egon.cola.component.yuheng.llm.proxy.repository.LlmModelSnapshotConverter;
import top.egon.cola.component.yuheng.llm.proxy.repository.impl.MpLlmConfigurationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LlmConfigurationSnapshotCoordinatorTest {
    private final MpLlmConfigurationRepository authority = mock(MpLlmConfigurationRepository.class);
    private final MutableClock clock = new MutableClock();
    private final AtomicLong nanos = new AtomicLong();
    private final LlmConfigurationSnapshotCoordinator coordinator = new LlmConfigurationSnapshotCoordinator(
            authority, clock, Duration.ofSeconds(5), Duration.ofSeconds(15), nanos::get);

    @Test
    void requestBeforeFirstCaptureNeverQueriesDatabaseOrFabricatesEmptyConfiguration() {
        assertThatThrownBy(() -> coordinator.findSnapshot("model-a")).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
        assertThatThrownBy(coordinator::findCatalog).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
        verifyNoInteractions(authority);
    }

    @Test
    void completedPublicationIsImmutableAndRequestsOnlyUseMemory() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)));
        assertThat(coordinator.reconcile()).isTrue();
        var first = coordinator.findSnapshot("model-a").orElseThrow();
        assertThat(coordinator.findSnapshot("model-a").orElseThrow()).isSameAs(first);
        assertThat(coordinator.findCatalog()).containsExactly(first);
        assertThatThrownBy(() -> first.setEnabled(false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> coordinator.findCatalog().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(authority, times(1)).loadPublication();
    }

    @Test
    void successfulEmptyCaptureIsAnAuthoritativeMissAndRemovesOldModels() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)), List.of());
        assertThat(coordinator.reconcile()).isTrue();
        assertThat(coordinator.reconcile()).isTrue();
        assertThat(coordinator.findSnapshot("model-a")).isEmpty();
        assertThat(coordinator.findCatalog()).isEmpty();
    }

    @Test
    void failedReconciliationDoesNotRenewLastKnownGoodAge() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)))
                .thenThrow(new IllegalStateException("synthetic storage failure"));
        assertThat(coordinator.reconcile()).isTrue();
        advance(Duration.ofSeconds(10));
        assertThat(coordinator.reconcile()).isFalse();
        assertThat(coordinator.findSnapshot("model-a")).isPresent();
        advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> coordinator.findSnapshot("model-a")).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
    }

    @Test
    void currentPolicyRevisionReplacesTheEntirePublicationWithoutHistoryFallback() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)), List.of(policy("model-a", 2L)));
        coordinator.reconcile();
        String prior = coordinator.findSnapshot("model-a").orElseThrow().getPublicationDigest();
        coordinator.reconcile();
        assertThat(coordinator.findSnapshot("model-a").orElseThrow().getPublicationDigest()).isNotEqualTo(prior);
        assertThat(coordinator.findSnapshot("model-a").orElseThrow().getRoutes().getFirst().getChannel().getRevision())
                .isEqualTo(2L);
    }

    @Test
    void hintOnlyInvalidatesAndCannotGrantFreshnessWhenAuthorityFails() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)))
                .thenThrow(new IllegalStateException("synthetic storage failure"));
        coordinator.reconcile();
        coordinator.hint();
        assertThat(coordinator.reconcile()).isFalse();
        assertThatThrownBy(() -> coordinator.findSnapshot("model-a")).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
    }

    @Test
    void hintDuringReadPreventsPublishingTheSupersededCapture() {
        when(authority.loadPublication()).thenAnswer(ignored -> {
            coordinator.hint();
            return List.of(policy("model-a", 1L));
        });
        assertThat(coordinator.reconcile()).isFalse();
        assertThatThrownBy(coordinator::findCatalog).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
    }

    @Test
    void loadingTimeConsumesFreshnessBudgetAndDoesNotBecomeNewAuthorityTime() {
        when(authority.loadPublication()).thenAnswer(ignored -> {
            advance(Duration.ofSeconds(15));
            return List.of(policy("model-a", 1L));
        });
        assertThat(coordinator.reconcile()).isFalse();
        assertThatThrownBy(coordinator::findCatalog).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
    }

    @Test
    void clockRollbackOrMonotonicExpiryCannotReviveAnOldPublication() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)));
        coordinator.reconcile();
        clock.instant = clock.instant.minusSeconds(1);
        assertThatThrownBy(coordinator::findCatalog).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
        clock.instant = clock.instant.plusSeconds(1);
        nanos.addAndGet(Duration.ofSeconds(15).toNanos());
        assertThatThrownBy(coordinator::findCatalog).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
    }

    @Test
    void mixedTenantOrDuplicateAliasDoesNotReplaceAValidPublication() {
        var other = policy("model-b", 1L).immutableCopy(8L, "b".repeat(64));
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)),
                List.of(policy("model-a", 1L), other), List.of(policy("model-a", 1L), policy("model-a", 2L)));
        assertThat(coordinator.reconcile()).isTrue();
        assertThat(coordinator.reconcile()).isFalse();
        assertThat(coordinator.reconcile()).isFalse();
        assertThat(coordinator.findCatalog()).hasSize(1);
    }

    @Test
    void startupFailureAndCloseNeverExposeAReadyEmptyPublication() {
        when(authority.loadPublication()).thenThrow(new IllegalStateException("synthetic failure"));
        assertThatThrownBy(coordinator::start).hasMessage("YUHENG_LLM_CONFIGURATION_UNAVAILABLE");
        coordinator.close();
        assertThat(coordinator.reconcile()).isFalse();
        assertThatThrownBy(coordinator::findCatalog).isInstanceOf(LlmInvocationException.class).hasMessage("The model configuration is unavailable");
    }

    @Test
    void liveUnavailableHasNative503ContractAndReadinessUsesTheSameGate() {
        assertThat(coordinator.isReady()).isFalse();
        assertThatThrownBy(() -> coordinator.findSnapshot("model-a"))
                .isInstanceOfSatisfying(LlmInvocationException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(503);
                    assertThat(error.getCode()).isEqualTo("model_unavailable");
                    assertThat(error.getParam()).isNull();
                    assertThat(error.isRetryable()).isTrue();
                });
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)));
        coordinator.reconcile();
        assertThat(coordinator.isReady()).isTrue();
        advance(Duration.ofSeconds(15));
        assertThat(coordinator.isReady()).isFalse();
        assertThatThrownBy(coordinator::findCatalog).isInstanceOf(LlmInvocationException.class);
        verify(authority, times(1)).loadPublication();
    }

    @Test
    void periodicBackgroundReconciliationFindsChangesEvenWithoutAnyPubsubHint() throws Exception {
        var captures = new AtomicInteger();
        var secondCapture = new CountDownLatch(1);
        when(authority.loadPublication()).thenAnswer(ignored -> {
            int call = captures.incrementAndGet();
            if (call >= 2) {
                secondCapture.countDown();
            }
            return List.of(policy("model-a", call == 1 ? 1L : 2L));
        });
        var worker = new LlmConfigurationSnapshotCoordinator(authority, clock,
                Duration.ofMillis(100), Duration.ofSeconds(1), nanos::get);
        try {
            worker.start();
            assertThat(secondCapture.await(3, TimeUnit.SECONDS)).isTrue();
            // The callback signals before publication; its monitor waits for the complete atomic install.
            synchronized (worker) {
                assertThat(worker.findSnapshot("model-a").orElseThrow().getRoutes().getFirst()
                        .getChannel().getRevision()).isEqualTo(2L);
            }
        } finally {
            worker.close();
        }
        assertThat(worker.isReady()).isFalse();
    }

    @Test
    void cacheDisabledListenerNeedsNoCacheOrRedisBeanAndAnyMessageIsOnlyAWakeSignal() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> optional = mock(ObjectProvider.class);
        var target = mock(LlmConfigurationSnapshotCoordinator.class);
        new LlmCacheInvalidationListener(target, optional).subscribe();
        verifyNoInteractions(target);
        var redis = mock(RedissonClient.class);
        var topic = mock(RTopic.class);
        when(optional.getIfAvailable()).thenReturn(redis);
        when(redis.getTopic("yuheng:llm:cache:invalidate")).thenReturn(topic);
        AtomicReference<MessageListener<String>> callback = new AtomicReference<>();
        when(topic.addListener(eq(String.class), any(MessageListener.class))).thenAnswer(call -> {
            callback.set(call.getArgument(1));
            return 1;
        });
        new LlmCacheInvalidationListener(target, optional).subscribe();
        callback.get().onMessage("yuheng:llm:cache:invalidate", "untrusted-not-a-policy");
        verify(target, times(1)).hint();
        verifyNoMoreInteractions(target);
    }

    @Test
    void realCglibMethodValidationProxySupportsStartupAndShutdownLifecycle() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L)));
        AtomicReference<LlmConfigurationSnapshotCoordinator> actual = new AtomicReference<>();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("methodValidationPostProcessor", MethodValidationPostProcessor.class, () -> {
                var validation = new MethodValidationPostProcessor();
                validation.setProxyTargetClass(true);
                return validation;
            });
            context.registerBean("mpLlmConfigurationRepository", LlmConfigurationSnapshotCoordinator.class, () -> {
                var target = new LlmConfigurationSnapshotCoordinator(authority, clock,
                        Duration.ofSeconds(5), Duration.ofSeconds(15), nanos::get);
                actual.set(target);
                return target;
            }, definition -> {
                definition.setInitMethodName("start");
                definition.setDestroyMethodName("close");
            });
            context.refresh();
            var repository = context.getBean("mpLlmConfigurationRepository", LlmConfigurationSnapshotCoordinator.class);
            assertThat(AopUtils.isCglibProxy(repository)).isTrue();
            assertThat(repository.isReady()).isTrue();
            assertThat(repository.findSnapshot("model-a")).isPresent();
            assertThatThrownBy(() -> repository.findSnapshot("invalid/alias"))
                    .isInstanceOf(ConstraintViolationException.class);
        }
        assertThat(actual.get().isReady()).isFalse();
    }

    @Test
    void embeddingAliasOnlyChangeRotatesWholeBundleWithoutMutatingAnAlreadyPinnedRequest() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L), policy("embedding-b", 1L)),
                List.of(policy("model-a", 1L), policy("embedding-b", 2L)));
        coordinator.reconcile();
        var pinned = coordinator.findSnapshot("model-a").orElseThrow();
        String oldDigest = pinned.getPublicationDigest();
        coordinator.reconcile();
        var accepted = coordinator.findSnapshot("model-a").orElseThrow();
        assertThat(accepted.getPublicationDigest()).isNotEqualTo(oldDigest)
                .isEqualTo(coordinator.findSnapshot("embedding-b").orElseThrow().getPublicationDigest());
        assertThat(pinned.getPublicationDigest()).isEqualTo(oldDigest);
        assertThat(pinned.getRevision()).isEqualTo(accepted.getRevision());
        assertThat(pinned.getRoutes().getFirst().getChannel().getRevision())
                .isEqualTo(accepted.getRoutes().getFirst().getChannel().getRevision());
    }

    @Test
    void bundleDigestIsIndependentOfDatabaseRowOrderAndStableOnUnchangedReconciliation() {
        when(authority.loadPublication()).thenReturn(List.of(policy("model-a", 1L), policy("embedding-b", 1L)),
                List.of(policy("embedding-b", 1L), policy("model-a", 1L)));
        coordinator.reconcile();
        String prior = coordinator.findSnapshot("model-a").orElseThrow().getPublicationDigest();
        coordinator.reconcile();
        assertThat(coordinator.findSnapshot("model-a").orElseThrow().getPublicationDigest()).isEqualTo(prior);
    }

    private void advance(Duration duration) {
        clock.instant = clock.instant.plus(duration);
        nanos.addAndGet(duration.toNanos());
    }

    private static LlmModelSnapshotBO policy(String alias, long channelRevision) {
        var channel = LlmModelSnapshotBO.ChannelBO.builder().channelKey("local-main").revision(channelRevision)
                .deployment(LlmDeploymentEnum.LOCAL).protocol(LlmProtocolEnum.OPENAI_CHAT)
                .baseUrl("http://127.0.0.1:8000/v1").enabled(true).connectTimeoutMs(1000).headerTimeoutMs(10000)
                .idleTimeoutMs(10000).totalTimeoutMs(20000).maxConcurrent(4).build();
        var route = LlmModelSnapshotBO.RouteBO.builder().channelKey("local-main").upstreamModel("local-actual")
                .priority(1).weight(100).capabilities(Set.of(LlmCapabilityEnum.TEXT)).channel(channel).build();
        return new LlmModelSnapshotConverter().toPublication(LlmModelSnapshotBO.builder().modelKey(alias)
                .name("Synthetic model").createdAt(Instant.parse("2026-11-19T12:00:00Z")).kind(LlmModelKindEnum.CHAT)
                .enabled(true).protocols(List.of(LlmProtocolEnum.OPENAI_CHAT)).allowedSubjects(List.of("svc:caller"))
                .revision(1L).routes(List.of(route)).build(), 7L);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-11-19T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
