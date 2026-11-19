package top.egon.cola.component.yuheng.admin.release.controller.scheduled;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.release.domain.bo.GatewayRecoverableReleaseAttemptBO;
import top.egon.cola.component.yuheng.admin.release.domain.bo.GatewayReleaseBO;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.release.service.GatewayReleasePublicationCoordinator;
import top.egon.cola.component.yuheng.admin.release.service.GatewayReleasePublicationCoordinator.NativeOutcome;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static top.egon.cola.component.yuheng.admin.config.GatewayPersistenceTestSupport.persistenceContext;

class GatewayReleaseReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-07-26T08:00:00Z");
    private static final long OWNER = 7001L;
    private static final String GROUP = "80";

    private final GatewayReleaseRepository releases = mock(GatewayReleaseRepository.class);
    private final GatewayUrlExposureRepository exposures = mock(GatewayUrlExposureRepository.class);
    private final GatewayReleasePublicationCoordinator coordinator = mock(GatewayReleasePublicationCoordinator.class);
    private final GatewayDraftJpaRepository drafts = mock(GatewayDraftJpaRepository.class);
    private final GatewayReleaseReconciler reconciler = new GatewayReleaseReconciler(
            releases, exposures, coordinator, drafts, transactions(), Clock.fixed(NOW, ZoneOffset.UTC), persistenceContext());

    @Test
    void requiresThePublisherRoleInsteadOfAnAdminDefault() {
        var condition = GatewayReleaseReconciler.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition.name()).containsExactly("yuheng.admin.exposure.role");
        assertThat(condition.havingValue()).isEqualTo("PUBLISHER");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void resumesTheAttemptFromItsOwnArtifactAndAdvancesTheOriginalDraftBasisOnlyAfterSuccess() throws Exception {
        var draft = new GatewayDraftBO(GROUP, "admin", NOW).setBasedOnReleaseId("49");
        when(releases.recoverableAfter(0, 64)).thenReturn(List.of(new GatewayRecoverableReleaseAttemptBO("51", GROUP, 2)));
        when(releases.find("51")).thenReturn(Optional.of(release("51", "49", GatewayReleaseStatus.PUBLISHING)));
        when(drafts.findById(GROUP)).thenReturn(Optional.of(draft));
        when(coordinator.recoverNative(OWNER, "51", 2)).thenAnswer(call -> {
            assertThat(EgonColaTenantIdProvider.currentTenantId()).isEqualTo(OWNER);
            return Optional.of(outcome(GatewayReleaseStatus.SUCCESS));
        });

        reconciler.reconcile();

        assertThat(draft.getBasedOnReleaseId()).isEqualTo("51");
        verify(drafts).save(draft);
        verify(coordinator).recoverNative(OWNER, "51", 2);
    }

    @Test
    void leavesArchivedAndSupersededAttemptsAndANewerDraftBasisUntouched() throws Exception {
        var draft = new GatewayDraftBO(GROUP, "admin", NOW).setBasedOnReleaseId("60");
        when(releases.recoverableAfter(0, 64)).thenReturn(List.of(
                new GatewayRecoverableReleaseAttemptBO("50", GROUP, 1),
                new GatewayRecoverableReleaseAttemptBO("51", GROUP, 2),
                new GatewayRecoverableReleaseAttemptBO("52", GROUP, 3),
                new GatewayRecoverableReleaseAttemptBO("53", GROUP, 0)));
        when(releases.find("50")).thenReturn(Optional.of(release("50", "49", GatewayReleaseStatus.PUBLISHING)));
        when(releases.find("51")).thenReturn(Optional.of(release("51", "49", GatewayReleaseStatus.PUBLISHING)));
        when(releases.find("52")).thenReturn(Optional.of(release("52", "49", GatewayReleaseStatus.SUPERSEDED)));
        when(coordinator.recoverNative(OWNER, "50", 1)).thenReturn(Optional.empty());
        when(coordinator.recoverNative(OWNER, "51", 2)).thenReturn(Optional.of(outcome(GatewayReleaseStatus.SUCCESS)));
        when(drafts.findById(GROUP)).thenReturn(Optional.of(draft));

        reconciler.reconcile();

        assertThat(draft.getBasedOnReleaseId()).isEqualTo("60");
        verify(drafts, never()).save(any(GatewayDraftBO.class));
        verify(coordinator, never()).recoverNative(OWNER, "52", 3);
        verify(coordinator, never()).recoverNative(OWNER, "53", 0);
        verify(coordinator).recoverNative(OWNER, "50", 1);
    }

    @Test
    void advancesTheBoundedCursorAndRequeuesStandaloneCloseWithoutAReleaseAttempt() throws Exception {
        when(exposures.findRecoveryIds(OWNER, 0, 64)).thenReturn(List.of(100L, 101L));
        when(exposures.findRecoveryIds(OWNER, 101, 64)).thenReturn(List.of(102L));
        when(exposures.findRecoveryIds(OWNER, 102, 64)).thenReturn(List.of());

        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        verify(coordinator).enqueueRecovery(OWNER, 100);
        verify(coordinator).enqueueRecovery(OWNER, 101);
        verify(coordinator).enqueueRecovery(OWNER, 102);
        verify(drafts, never()).save(any(GatewayDraftBO.class));
    }

    private static GatewayReleaseBO release(String id, String base, GatewayReleaseStatus status) {
        return GatewayReleaseBO.builder().id(id).gatewayGroupId(GROUP).basedOnReleaseId(base).status(status).build();
    }

    private static NativeOutcome outcome(GatewayReleaseStatus status) {
        return new NativeOutcome(status, false, List.of(), List.of());
    }

    private static TransactionTemplate transactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        });
    }
}
