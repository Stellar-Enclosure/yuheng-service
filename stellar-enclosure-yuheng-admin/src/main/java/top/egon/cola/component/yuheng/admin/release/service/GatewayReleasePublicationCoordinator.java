package top.egon.cola.component.yuheng.admin.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.outbox.api.OutboxMessage;
import top.egon.cola.component.outbox.api.TenantScopedOutboxKey;
import top.egon.cola.component.outbox.api.TransactionalOutbox;
import top.egon.cola.component.yuheng.admin.exposure.converter.GatewayUrlExposureViewConverter;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayReleaseVO.ClusterTargetVO;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayReleaseVO.ExposureOutcomeVO;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable native wakeups and current-row outcomes. It has no DDC publication or Kubernetes write path. */
public class GatewayReleasePublicationCoordinator {
    public static final String LEGACY_READ_ONLY = "YUHENG_RELEASE_LEGACY_ARTIFACT_READ_ONLY";
    private static final long RECOVERY_WINDOW_SECONDS = Duration.ofMinutes(5).toSeconds();
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

    /** Caller owns the same selected writable LOCAL transaction as release and control-row acceptance. */
    public void enqueueCurrent(long owner, String releaseId, int attemptNo, GatewayCiliumReleaseArtifact artifact) {
        requireTransaction(owner);
        requireArtifact(owner, artifact);
        if (!releaseId.equals(artifact.releaseId()) || attemptNo <= 0) throw invalid();
        // MCP mappings are immutable release content, not Cilium commands or runtime notifications.
        if (!artifact.hasExposurePublication()) return;
        for (JsonNode entry : artifact.canonicalSnapshot().path("exposurePublication").path("exposures")) {
            GatewayUrlExposureBO row = current(owner, entry.path("exposureId").asText());
            String commandId = releaseId + ":" + attemptNo + ":" + row.getVersion() + ":" + row.getId() + ":" + row.getDesiredGeneration();
            String key = TenantScopedOutboxKey.of(owner, "exposure-publication", commandId);
            enqueue(owner, row, key, Objects.requireNonNull(row.getUpdateTime(), "updateTime"));
        }
    }

    /** Across replicas, an outstanding current generation produces one recovery wakeup per window. */
    public void enqueueRecovery(long owner, long exposureId) {
        requireTransaction(owner);
        var row = exposures.findOwned(owner, exposureId).orElse(null);
        if (row == null) return;
        if (!Objects.equals(row.getOwnerTenantId(), owner) || !Objects.equals(row.getId(), exposureId)) throw invalid();
        if (!"PENDING".equals(row.getReconcileStatus()) && (!"SYNCED".equals(row.getReconcileStatus())
                || "SYNCED".equals(converter.toView(row).getReconcileStatus()))) return;
        long second = clock.instant().getEpochSecond();
        long window = second - Math.floorMod(second, RECOVERY_WINDOW_SECONDS);
        String commandId = exposureId + ":" + row.getDesiredGeneration() + ":" + window;
        enqueue(owner, row, TenantScopedOutboxKey.of(owner, "exposure-recovery", commandId), Instant.ofEpochSecond(window));
    }

    private void enqueue(long owner, GatewayUrlExposureBO row, String key, Instant occurredAt) {
        String eventId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        var event = new ExposureDesiredStateChangedEvent(eventId, owner, row.getId(), row.getDesiredGeneration(),
                row.getDesiredState(), occurredAt);
        outbox.enqueue(OutboxMessage.builder().messageId(eventId).idempotencyKey(key)
                .channel(ExposureDesiredStateChangedEvent.CHANNEL).destination(ExposureDesiredStateChangedEvent.DESTINATION)
                .schemaVersion(ExposureDesiredStateChangedEvent.SCHEMA_VERSION).payload(event)
                .header("tenant-id", Long.toString(owner)).header("event-id", eventId).build());
    }

    /**
     * Read-only publication evidence. MCP success proves the exact durable artifact, not runtime consumption.
     * A mixed artifact still requires all real Cilium targets to converge; enqueue success proves neither.
     */
    public NativeOutcome observeNative(long owner, GatewayCiliumReleaseArtifact artifact) {
        requireOwner(owner);
        requireArtifact(owner, artifact);
        if (artifact.hasMcpPublication()) requirePersistedArtifact(artifact);
        if (!artifact.hasExposurePublication()) {
            if (!artifact.hasMcpPublication()) throw invalid();
            return new NativeOutcome(GatewayReleaseStatus.SUCCESS, false, List.of(), List.of());
        }
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
        finish(releaseId, attemptNo, outcome);
        return outcome;
    }

    /** Legacy journals stay archived; only terminal evidence won by this transaction can advance the draft basis. */
    public Optional<NativeOutcome> recoverNative(long owner, String releaseId, int attemptNo) {
        requireTransaction(owner);
        return releases.loadNative(releaseId).flatMap(artifact -> {
            NativeOutcome outcome = observeNative(owner, artifact);
            return finish(releaseId, attemptNo, outcome) ? Optional.of(outcome) : Optional.empty();
        });
    }

    private boolean finish(String releaseId, int attemptNo, NativeOutcome outcome) {
        return outcome.status() != GatewayReleaseStatus.PUBLISHING
                && releases.completeCurrentNativeAttempt(releaseId, attemptNo, outcome.status(), outcome.partialApplied(), clock.instant());
    }

    private GatewayUrlExposureBO current(long owner, String id) {
        long exposureId;
        try { exposureId = Long.parseLong(id); } catch (RuntimeException malformed) { throw invalid(); }
        return exposures.findOwned(owner, exposureId).filter(row -> Objects.equals(row.getOwnerTenantId(), owner)
                && Objects.equals(row.getId(), exposureId)).orElseThrow(GatewayReleasePublicationCoordinator::invalid);
    }

    private void requirePersistedArtifact(GatewayCiliumReleaseArtifact artifact) {
        var persisted = releases.loadNative(artifact.releaseId()).orElseThrow(GatewayReleasePublicationCoordinator::invalid);
        if (!persisted.hasMcpPublication() || !artifact.releaseId().equals(persisted.releaseId())
                || !artifact.ownerTenantId().equals(persisted.ownerTenantId())
                || !artifact.gatewayGroupId().equals(persisted.gatewayGroupId())
                || !artifact.artifactSha256().equals(persisted.artifactSha256())) throw invalid();
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
