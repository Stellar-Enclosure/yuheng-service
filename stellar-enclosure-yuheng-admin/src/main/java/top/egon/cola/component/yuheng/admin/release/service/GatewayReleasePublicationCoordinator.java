package top.egon.cola.component.yuheng.admin.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.outbox.api.OutboxMessage;
import top.egon.cola.component.outbox.api.TenantScopedOutboxKey;
import top.egon.cola.component.outbox.api.TransactionalOutbox;
import top.egon.cola.component.tianshu.api.client.DdcManagementClient;
import top.egon.cola.component.yuheng.admin.config.properties.GatewayAdminDdcProperties;
import top.egon.cola.component.yuheng.admin.exposure.converter.GatewayUrlExposureViewConverter;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayPublicationOutcomeVO;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayReleaseVO.ClusterTargetVO;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayReleaseVO.ExposureOutcomeVO;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleasePublicationRepository;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.rule.domain.vo.CompiledGatewayRelease;
import top.egon.cola.component.yuheng.admin.rule.service.GatewayDdcRulePublisher;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable native wakeups and current-row outcomes. It has no DDC publication or Kubernetes write path. */
public class GatewayReleasePublicationCoordinator {
    public static final String LEGACY_READ_ONLY = "YUHENG_RELEASE_LEGACY_ARTIFACT_READ_ONLY";
    private final GatewayReleaseRepository releases;
    private final GatewayUrlExposureRepository exposures;
    private final TransactionalOutbox outbox;
    private final GatewayUrlExposureViewConverter converter;
    private final ObjectMapper json;
    private final Clock clock;

    public GatewayReleasePublicationCoordinator(GatewayReleaseRepository releases, GatewayUrlExposureRepository exposures,
            TransactionalOutbox outbox, GatewayUrlExposureViewConverter converter, ObjectMapper json, Clock clock) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.exposures = Objects.requireNonNull(exposures, "exposures");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Source-compatible archive constructor; supplied old publishers are never retained or invoked. */
    public GatewayReleasePublicationCoordinator(GatewayReleasePublicationRepository journal, GatewayReleaseRepository releases,
            DdcManagementClient client, GatewayDdcRulePublisher publisher, Clock clock, Duration timeout,
            GatewayAdminDdcProperties properties) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.exposures = null; this.outbox = null; this.converter = null; this.json = null;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The obsolete scheduled job cannot recover an old engine path, including after process restart. */
    public GatewayPublicationOutcomeVO resume(String releaseId, int attemptNo) {
        throw new IllegalStateException(LEGACY_READ_ONLY);
    }

    public GatewayPublicationOutcomeVO execute(String releaseId, int attemptNo, CompiledGatewayRelease compiled, String actorId) {
        throw new IllegalStateException(LEGACY_READ_ONLY);
    }

    /** Caller owns the same selected writable LOCAL transaction as release and control-row acceptance. */
    public void enqueueCurrent(long owner, String releaseId, int attemptNo, GatewayCiliumReleaseArtifact artifact) {
        requireTransaction(owner);
        requireArtifact(owner, artifact);
        if (!releaseId.equals(artifact.releaseId()) || attemptNo <= 0) throw invalid();
        for (JsonNode entry : artifact.canonicalSnapshot().path("exposurePublication").path("exposures")) {
            GatewayUrlExposureBO row = current(owner, entry.path("exposureId").asText());
            String commandId = releaseId + ":" + attemptNo + ":" + row.getVersion() + ":" + row.getId() + ":" + row.getDesiredGeneration();
            String key = TenantScopedOutboxKey.of(owner, "exposure-publication", commandId);
            String eventId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
            var event = new ExposureDesiredStateChangedEvent(eventId, owner, row.getId(), row.getDesiredGeneration(),
                    row.getDesiredState(), Objects.requireNonNull(row.getUpdateTime(), "updateTime"));
            outbox.enqueue(OutboxMessage.builder().messageId(eventId).idempotencyKey(key)
                    .channel(ExposureDesiredStateChangedEvent.CHANNEL).destination(ExposureDesiredStateChangedEvent.DESTINATION)
                    .schemaVersion(ExposureDesiredStateChangedEvent.SCHEMA_VERSION).payload(event)
                    .header("tenant-id", Long.toString(owner)).header("event-id", eventId).build());
        }
    }

    /** Read-only evidence projection; enqueue success is never evidence of URL convergence. */
    public NativeOutcome observeNative(long owner, GatewayCiliumReleaseArtifact artifact) {
        requireOwner(owner);
        requireArtifact(owner, artifact);
        List<ExposureOutcomeVO> outcomes = new ArrayList<>();
        List<ClusterTargetVO> targets = new ArrayList<>();
        int converged = 0;
        boolean stale = false;
        boolean failed = false;
        for (JsonNode entry : artifact.canonicalSnapshot().path("exposurePublication").path("exposures")) {
            GatewayUrlExposureBO row = current(owner, entry.path("exposureId").asText());
            var view = converter.toView(row);
            boolean matches = Long.toString(row.getDesiredGeneration()).equals(entry.path("expectedDesiredGeneration").asText());
            boolean isConverged = matches && "SYNCED".equals(view.getReconcileStatus())
                    && Objects.equals(view.getDesiredGeneration(), view.getObservedGeneration());
            String result;
            if (!matches) { result = "STALE"; stale = true; }
            else if ("FAILED".equals(row.getReconcileStatus())) { result = "FAILED"; failed = true; isConverged = false; }
            else if (row.getDesiredState() == GatewayUrlExposureStateEnum.WITHDRAWN) result = "WITHDRAWN_OVERRIDE";
            else if (!Objects.equals(row.getContentSha256(), entry.path("contentSha256").asText())) {
                result = "STALE"; stale = true; isConverged = false;
            } else if (row.getDesiredState() == GatewayUrlExposureStateEnum.CLOSED) result = "CLOSED_OVERRIDE";
            else result = isConverged ? "APPLIED" : null;
            if (isConverged) converged++;
            outcomes.add(new ExposureOutcomeVO(row.getId().toString(), row.getDesiredGeneration().toString(),
                    row.getObservedGeneration().toString(), row.getDesiredState().wireValue(), result,
                    view.getLastErrorCode()));
            var route = view.getKubernetesRoute();
            targets.add(new ClusterTargetVO(row.getClusterId(), row.getRouteKind(), row.getRouteNamespace(), row.getRouteName(),
                    route == null ? row.getRouteUid() : route.getUid(), route == null ? null : route.getMetadataGeneration(),
                    route == null ? null : route.getResourceVersion(), isConverged));
        }
        if (outcomes.isEmpty()) throw invalid();
        GatewayReleaseStatus status = stale ? GatewayReleaseStatus.SUPERSEDED
                : failed ? GatewayReleaseStatus.FAILED
                : converged == outcomes.size() ? GatewayReleaseStatus.SUCCESS : GatewayReleaseStatus.PUBLISHING;
        outcomes.sort(Comparator.comparing(value -> Long.valueOf(value.exposureId())));
        targets.sort(Comparator.comparing(ClusterTargetVO::clusterId).thenComparing(ClusterTargetVO::namespace).thenComparing(ClusterTargetVO::name));
        return new NativeOutcome(status, converged > 0 && converged < outcomes.size(), outcomes, targets);
    }

    /** Called by the writer/recovery owner only after its observations are committed, in a new selected LOCAL transaction. */
    public NativeOutcome completeNative(long owner, String releaseId, int attemptNo) {
        requireTransaction(owner);
        var artifact = releases.loadNative(releaseId).orElseThrow(() -> new IllegalStateException(LEGACY_READ_ONLY));
        NativeOutcome outcome = observeNative(owner, artifact);
        if (outcome.status() != GatewayReleaseStatus.PUBLISHING) releases.completeAttempt(releaseId, attemptNo, outcome.status(),
                outcome.partialApplied(), null, null, null, List.of(), clock.instant());
        return outcome;
    }

    private GatewayUrlExposureBO current(long owner, String id) {
        long exposureId;
        try { exposureId = Long.parseLong(id); } catch (RuntimeException malformed) { throw invalid(); }
        return exposures.findOwned(owner, exposureId).filter(row -> Objects.equals(row.getOwnerTenantId(), owner)
                && Objects.equals(row.getId(), exposureId)).orElseThrow(GatewayReleasePublicationCoordinator::invalid);
    }

    private static void requireOwner(long owner) {
        if (owner <= 0 || !Objects.equals(EgonColaTenantIdProvider.currentTenantId(), owner)) throw invalid();
    }

    private static void requireTransaction(long owner) {
        requireOwner(owner);
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw invalid();
    }

    private static void requireArtifact(long owner, GatewayCiliumReleaseArtifact artifact) {
        if (!Long.toString(owner).equals(artifact.ownerTenantId())) throw invalid();
    }

    private static IllegalStateException invalid() { return new IllegalStateException("YUHENG_RELEASE_NATIVE_BINDING_INVALID"); }

    public record NativeOutcome(GatewayReleaseStatus status, boolean partialApplied,
                                List<ExposureOutcomeVO> exposureOutcomes, List<ClusterTargetVO> clusterTargets) {
        public NativeOutcome { exposureOutcomes = List.copyOf(exposureOutcomes); clusterTargets = List.copyOf(clusterTargets); }
    }
}
