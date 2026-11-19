package top.egon.cola.component.yuheng.admin.release.controller.scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.release.domain.bo.GatewayRecoverableReleaseAttemptBO;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.release.service.GatewayReleasePublicationCoordinator;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Recovers committed local work for the publisher's installation. Kubernetes writes remain with the Outbox writer. */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Component("gatewayReleaseReconciler")
@ConditionalOnProperty(name = "yuheng.admin.exposure.role", havingValue = "PUBLISHER")
public final class GatewayReleaseReconciler {

    private static final int RECOVERY_BATCH = 64;

    @Qualifier("mpGatewayReleaseRepository") private final GatewayReleaseRepository releases;
    @Qualifier("mpGatewayUrlExposureRepository") private final GatewayUrlExposureRepository exposures;
    @Qualifier("gatewayReleasePublicationCoordinator") private final GatewayReleasePublicationCoordinator coordinator;
    @Qualifier("mpGatewayDraftMetadataRepository") private final GatewayDraftJpaRepository drafts;
    @Qualifier("gatewayExposureTransactionTemplate") private final TransactionTemplate transactions;
    @Qualifier("gatewayProjectionClock") private final Clock clock;
    @Qualifier("gatewayPersistenceContextComponent") private final GatewayPersistenceContextComponent persistenceContext;

    private long releaseCursor;
    private long exposureCursor;

    @Scheduled(fixedDelayString = "${yuheng.admin.release-reconcile-delay:30000}")
    public synchronized void reconcile() throws Exception {
        persistenceContext.call(null, () -> {
            long owner = persistenceContext.installationTenantId();
            recoverReleases(owner);
            wakePendingExposures(owner);
            return null;
        });
    }

    private void recoverReleases(long owner) {
        List<GatewayRecoverableReleaseAttemptBO> page = Objects.requireNonNull(
                transactions.execute(status -> releases.recoverableAfter(releaseCursor, RECOVERY_BATCH)));
        releaseCursor = page.isEmpty() ? 0 : Long.parseLong(page.getLast().getReleaseId());
        for (GatewayRecoverableReleaseAttemptBO attempt : page) {
            if (attempt.getAttemptNo() <= 0) continue;
            try {
                transactions.executeWithoutResult(status -> settle(owner, attempt));
            } catch (RuntimeException unavailable) {
                log.warn("YUHENG_RELEASE_RECOVERY_PENDING releaseId={} attempt={}", attempt.getReleaseId(), attempt.getAttemptNo());
            }
        }
    }

    private void settle(long owner, GatewayRecoverableReleaseAttemptBO attempt) {
        var release = releases.find(attempt.getReleaseId()).orElse(null);
        if (release == null || release.getStatus() != GatewayReleaseStatus.PUBLISHING
                || !Objects.equals(attempt.getGatewayGroupId(), release.getGatewayGroupId())) return;
        var completed = coordinator.recoverNative(owner, attempt.getReleaseId(), attempt.getAttemptNo());
        if (completed.isEmpty() || completed.orElseThrow().status() != GatewayReleaseStatus.SUCCESS) return;
        var draft = drafts.findById(release.getGatewayGroupId()).orElse(null);
        if (draft == null || Objects.equals(draft.getBasedOnReleaseId(), release.getId())
                || !Objects.equals(draft.getBasedOnReleaseId(), release.getBasedOnReleaseId())) return;
        draft.baseOn(release.getId(), "gateway_release_reconciler", clock.instant());
        drafts.save(draft);
    }

    private void wakePendingExposures(long owner) {
        List<Long> page = Objects.requireNonNull(
                transactions.execute(status -> exposures.findRecoveryIds(owner, exposureCursor, RECOVERY_BATCH)));
        exposureCursor = page.isEmpty() ? 0 : page.getLast();
        for (long exposureId : page) {
            try {
                transactions.executeWithoutResult(status -> coordinator.enqueueRecovery(owner, exposureId));
            } catch (RuntimeException unavailable) {
                log.warn("YUHENG_EXPOSURE_RECOVERY_PENDING exposureId={}", exposureId);
            }
        }
    }
}
