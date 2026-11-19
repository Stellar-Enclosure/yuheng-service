package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BindingResolver;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ControlFence;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.Failure;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.Kind;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.RouteSnapshot;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.RouteTarget;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.KubernetesSnapshot;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.ObservationResult;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.ProofProvider;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.SnapshotProvider;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Current-authority writer. Cluster reads precede the final primary read; every mutation uses the
 * observed UID/RV and an independently reread committed control fence. No network call holds a DB transaction.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class GatewayUrlExposureReconciler {
    private static final int MAX_PASSES = 6;
    @NonNull @Qualifier("gatewayPersistenceContextComponent") private final GatewayPersistenceContextComponent installation;
    @NonNull @Qualifier("mpGatewayUrlExposureRepository") private final GatewayUrlExposureRepository controls;
    @NonNull @Qualifier("mpGatewayReleaseRepository") private final GatewayReleaseRepository releases;
    @NonNull @Qualifier("gatewayExposureTransactionTemplate") private final TransactionTemplate transactions;
    @NonNull @Qualifier("gatewayKubernetesClient") private final GatewayKubernetesClient kubernetes;
    @NonNull @Qualifier("gatewayUrlExposureObservationService") private final GatewayUrlExposureObservationService observations;
    @NonNull @Qualifier("gatewayHttpRouteBindingResolver") private final ObjectProvider<BindingResolver> bindings;
    @NonNull @Qualifier("gatewayUrlExposureSnapshotProvider") private final ObjectProvider<SnapshotProvider> snapshots;
    @NonNull @Qualifier("gatewayUrlExposureProofProvider") private final ObjectProvider<ProofProvider> proofs;
    @NonNull @Qualifier("gatewayProjectionClock") private final Clock clock;
    @NonNull @Qualifier("jacksonObjectMapper") private final ObjectMapper json;
    private final ReentrantLock[] stripes = createLockStripes();

    private static ReentrantLock[] createLockStripes() {
        ReentrantLock[] locks = new ReentrantLock[256];
        java.util.Arrays.setAll(locks, ignored -> new ReentrantLock());
        return locks;
    }

    /** Events provide identity only; their state/generation never become a write instruction. */
    public DeliveryResult reconcile(long trustedOwner, long exposureId) {
        if (trustedOwner <= 0 || exposureId <= 0 || !Objects.equals(installation.installationTenantId(), trustedOwner)) {
            return DeliveryResult.permanentFailure("EXPOSURE_INSTALLATION_MISMATCH", "The exposure does not belong to this installation");
        }
        ReentrantLock lock = stripes[Math.floorMod(Long.hashCode(exposureId), stripes.length)];
        if (!lock.tryLock()) return retry("EXPOSURE_WRITER_BUSY");
        try {
            return installation.call(null, () -> {
                if (!Objects.equals(EgonColaTenantIdProvider.currentTenantId(), trustedOwner)) return retry("EXPOSURE_OWNER_CONTEXT_INVALID");
                return reconcileOwned(trustedOwner, exposureId);
            });
        } catch (Exception unavailable) {
            log.debug("Exposure reconciliation will retry after an authority lookup failure. exposureId={}", exposureId);
            return retry("EXPOSURE_CONTROL_UNAVAILABLE");
        } finally { lock.unlock(); }
    }

    private DeliveryResult reconcileOwned(long owner, long id) {
        GatewayUrlExposureBO identity = read(owner, id).orElse(null);
        if (identity == null) return DeliveryResult.permanentFailure("EXPOSURE_NOT_FOUND", "The exposure is unavailable");
        RouteTarget target = target(owner, identity);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            RouteSnapshot route = null;
            GatewayUrlExposureBO row = null;
            try {
                noTransaction();
                route = kubernetes.getRoute(target).orElse(null);
                row = read(owner, id).orElse(null);
                if (row == null) return DeliveryResult.permanentFailure("EXPOSURE_NOT_FOUND", "The exposure is unavailable");
                if (!target(owner, row).equals(target) || !sameSlot(identity, row)) return retry("EXPOSURE_SLOT_CHANGED");
                Material material = row.getDesiredState() == GatewayUrlExposureStateEnum.OPEN || route == null ? material(row) : null;
                if (route == null) {
                    if (material == null) return failure(owner, row, null, "EXPOSURE_ARTIFACT_UNAVAILABLE");
                    GatewayUrlExposureBO current = read(owner, id).orElse(null);
                    if (!sameControl(row, current)) continue;
                    noTransaction();
                    try { kubernetes.createInert(target, fence(current), material.object()); }
                    catch (Failure unknown) {
                        if (unknown.kind() != Kind.CONFLICT && unknown.kind() != Kind.UNAVAILABLE
                                && unknown.kind() != Kind.THROTTLED && unknown.kind() != Kind.GONE) throw unknown;
                    }
                    noTransaction();
                    route = kubernetes.getRoute(target).orElse(null);
                    if (route == null) return retry("EXPOSURE_CREATE_UNCONFIRMED");
                    row = read(owner, id).orElse(null);
                    if (row == null || !sameSlot(identity, row)) return retry("EXPOSURE_CONTROL_CHANGED");
                    if (row.getRouteUid() != null && !row.getRouteUid().equals(route.uid())) {
                        return failure(owner, row, route, "ROUTE_UID_CONFLICT");
                    }
                    if (row.getRouteUid() == null) {
                        if (!inert(route)) return failure(owner, row, route, "ROUTE_INITIAL_STATE_CONFLICT");
                        RouteSnapshot created = route;
                        GatewayUrlExposureBO expected = row;
                        write(() -> controls.bindRouteUid(owner, id, expected.getVersion(), created.uid(), clock.instant()));
                    }
                    continue; // Fresh cluster and committed-control reads precede any later attach.
                }
                if (row.getRouteUid() == null) {
                    if (!inert(route)) return failure(owner, row, route, "ROUTE_INITIAL_STATE_CONFLICT");
                    RouteSnapshot initial = route;
                    GatewayUrlExposureBO expected = row;
                    write(() -> controls.bindRouteUid(owner, id, expected.getVersion(), initial.uid(), clock.instant()));
                    continue;
                }
                if (!row.getRouteUid().equals(route.uid())) return failure(owner, row, route, "ROUTE_UID_CONFLICT");
                if (row.getDesiredState() == GatewayUrlExposureStateEnum.OPEN) {
                    if (material == null || material.binding() == null) return failure(owner, row, route, "PUBLICATION_BINDING_UNAVAILABLE");
                    KubernetesSnapshot snapshot = snapshot(row, material.binding(), route);
                    var gate = observations.preflight(row, material.binding(), snapshot, proofProvider());
                    if (!gate.ready()) return failure(owner, row, route, gate.lastErrorCode());
                }
                if (!desired(route, row, material)) {
                    // Nothing with I/O occurs between this primary read and the UID/RV-conditional patch.
                    GatewayUrlExposureBO current = read(owner, id).orElse(null);
                    if (!sameControl(row, current)) continue;
                    noTransaction();
                    try {
                        kubernetes.patchDesired(route, fence(current), current.getDesiredState() == GatewayUrlExposureStateEnum.OPEN ? material.object() : null,
                                current.getDesiredState() == GatewayUrlExposureStateEnum.OPEN ? material.parent() : null);
                    } catch (Failure conflictOrUnknown) {
                        if (conflictOrUnknown.kind() == Kind.CONFLICT || conflictOrUnknown.kind() == Kind.GONE
                                || conflictOrUnknown.kind() == Kind.NOT_FOUND) continue;
                        if (conflictOrUnknown.kind() != Kind.UNAVAILABLE && conflictOrUnknown.kind() != Kind.THROTTLED) throw conflictOrUnknown;
                        // A lost response is not failure evidence. Read the fixed object back before deciding again.
                        noTransaction();
                        RouteSnapshot actual = kubernetes.getRoute(target).orElse(null);
                        GatewayUrlExposureBO latest = read(owner, id).orElse(null);
                        if (actual == null || latest == null) return retry("EXPOSURE_PATCH_UNCONFIRMED");
                        if (!Objects.equals(latest.getRouteUid(), actual.uid())) return failure(owner, latest, actual, "ROUTE_UID_CONFLICT");
                        if (!sameControl(current, latest) || !desired(actual, latest, material)) continue;
                        return observe(owner, latest, actual, material);
                    }
                    continue; // Do not turn the mutation's response or old status into a confirmed observation.
                }
                return observe(owner, row, route, material);
            } catch (Failure clusterFailure) {
                if (row != null) return failure(owner, row, route, "KUBERNETES_" + clusterFailure.kind().name());
                return retry("KUBERNETES_" + clusterFailure.kind().name());
            }
        }
        return retry("EXPOSURE_RECONCILE_CONFLICT");
    }

    private boolean inert(RouteSnapshot route) {
        JsonNode parents = route.document().path("spec").path("parentRefs");
        return parents.isArray() && parents.isEmpty();
    }

    private DeliveryResult observe(long owner, GatewayUrlExposureBO row, RouteSnapshot route, Material material) {
        Material resolved = material == null && row.getDesiredState() != GatewayUrlExposureStateEnum.OPEN ? material(row) : material;
        var actual = snapshot(row, resolved == null ? null : resolved.binding(), route);
        ObservationResult result = observations.evaluate(row, resolved == null ? null : resolved.binding(), actual, proofProvider());
        if (!record(owner, row.getId(), result)) return retry("EXPOSURE_OBSERVATION_STALE");
        return result.synced() ? DeliveryResult.success() : retry(result.lastErrorCode() == null ? "EXPOSURE_CONVERGENCE_PENDING" : result.lastErrorCode());
    }

    private DeliveryResult failure(long owner, GatewayUrlExposureBO row, RouteSnapshot route, String code) {
        String safeCode = code != null && code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : "EXPOSURE_CONVERGENCE_PENDING";
        ObservationResult result = observations.unavailable(row, route, safeCode);
        record(owner, row.getId(), result);
        return "ROUTE_UID_CONFLICT".equals(safeCode)
                ? DeliveryResult.permanentFailure(safeCode, "The stable route identity requires authorized recovery") : retry(safeCode);
    }

    private boolean record(long owner, long id, ObservationResult result) {
        if (result.verifiedUid() == null) return false;
        return write(() -> controls.recordObservation(owner, id, result.expectedVersion(), result.evaluatedGeneration(), result.verifiedUid(),
                result.observedState(), result.observedGeneration(), result.reconcileStatus(), result.observation(), result.lastErrorCode(), clock.instant()));
    }

    private KubernetesSnapshot snapshot(GatewayUrlExposureBO row, PublicationBinding binding, RouteSnapshot route) {
        noTransaction();
        SnapshotProvider provider = snapshots.getIfAvailable();
        KubernetesSnapshot result = provider == null || binding == null ? KubernetesSnapshot.minimal(route, clock.instant())
                : Objects.requireNonNull(provider.collect(row, binding, route), "snapshot");
        if (result.route() == null || !route.target().equals(result.route().target()) || !route.uid().equals(result.route().uid())
                || !route.resourceVersion().equals(result.route().resourceVersion()) || !route.document().equals(result.route().document())) {
            throw new IllegalStateException("EXPOSURE_SNAPSHOT_ROUTE_CHANGED");
        }
        return result;
    }

    private ProofProvider proofProvider() { return proofs.getIfAvailable(ProofProvider::unavailable); }

    private Material material(GatewayUrlExposureBO row) {
        try {
            Optional<GatewayCiliumReleaseArtifact> loaded = transactions.execute(status -> releases.loadNative(row.getReleaseId().toString()));
            if (loaded == null || loaded.isEmpty()) return null;
            GatewayCiliumReleaseArtifact artifact = loaded.orElseThrow();
            if (!row.getOwnerTenantId().toString().equals(artifact.ownerTenantId()) || !row.getGatewayGroupId().toString().equals(artifact.gatewayGroupId())
                    || !GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID.equals(row.getCompilerProfileId())) return null;
            JsonNode entry = null; JsonNode resource = null;
            for (JsonNode candidate : artifact.canonicalSnapshot().path("exposurePublication").path("exposures")) {
                if (row.getId().toString().equals(candidate.path("exposureId").asText())) entry = candidate;
            }
            for (JsonNode candidate : artifact.chunkManifest().path("resources")) {
                if (row.getId().toString().equals(candidate.path("exposureId").asText())) resource = candidate;
            }
            if (entry == null || resource == null || !row.getContentSha256().equals(entry.path("contentSha256").asText())
                    || !row.getContentSha256().equals(resource.path("contentSha256").asText())
                    || !row.getRouteName().equals(resource.path("object").path("metadata").path("name").asText())
                    || !row.getRouteNamespace().equals(resource.path("object").path("metadata").path("namespace").asText())) return null;
            PublicationBinding binding = null;
            BindingResolver resolver = bindings.getIfAvailable();
            if (resolver != null) {
                String operation = null;
                for (JsonNode reference : entry.path("resourceRefs")) if ("BUSINESS_API".equals(reference.path("kind").asText())) operation = reference.path("resourceId").asText();
                if (operation != null) {
                    PublicationBinding current = resolver.resolve(row.getOwnerTenantId(), row.getGatewayGroupId(), row.getRouteId(), operation).orElse(null);
                    if (current != null) for (JsonNode dependency : artifact.chunkManifest().path("dependencies")) {
                        JsonNode frozen = dependency.get("publicationBinding");
                        if (frozen != null && row.getRouteId().equals(frozen.path("routeId").asText())
                                && GatewayCiliumReleaseArtifact.sameCanonicalObject(frozen, json.valueToTree(current))) binding = current;
                    }
                }
            }
            return new Material(resource.path("object"), resource.path("approvedParent"), binding);
        } catch (RuntimeException unavailable) {
            // A closed intent can still remove parentRefs when its old artifact is unavailable.
            return null;
        }
    }

    private Optional<GatewayUrlExposureBO> read(long owner, long id) {
        Optional<GatewayUrlExposureBO> result = transactions.execute(status -> controls.findOwned(owner, id));
        return Objects.requireNonNull(result, "control read").filter(row -> Objects.equals(row.getOwnerTenantId(), owner) && Objects.equals(row.getId(), id));
    }

    private boolean write(Supplier<Boolean> action) { return Boolean.TRUE.equals(transactions.execute(status -> action.get())); }

    private RouteTarget target(long owner, GatewayUrlExposureBO row) {
        RouteTarget target = new RouteTarget(owner, row.getId(), row.getRouteNamespace());
        if (!"HTTPRoute".equals(row.getRouteKind()) || !target.name().equals(row.getRouteName())
                || !Objects.equals(kubernetes.scope().clusterId(), row.getClusterId())
                || !Objects.equals(kubernetes.scope().routeNamespace(), row.getRouteNamespace())) throw new IllegalStateException("EXPOSURE_SLOT_INVALID");
        return target;
    }

    private static boolean sameSlot(GatewayUrlExposureBO first, GatewayUrlExposureBO second) {
        return second != null && Objects.equals(first.getOwnerTenantId(), second.getOwnerTenantId())
                && Objects.equals(first.getId(), second.getId()) && Objects.equals(first.getClusterId(), second.getClusterId())
                && Objects.equals(first.getRouteKind(), second.getRouteKind()) && Objects.equals(first.getRouteNamespace(), second.getRouteNamespace())
                && Objects.equals(first.getRouteName(), second.getRouteName());
    }

    private static boolean sameControl(GatewayUrlExposureBO before, GatewayUrlExposureBO after) {
        return sameSlot(before, after) && Objects.equals(before.getVersion(), after.getVersion())
                && Objects.equals(before.getDesiredGeneration(), after.getDesiredGeneration()) && before.getDesiredState() == after.getDesiredState()
                && Objects.equals(before.getRouteUid(), after.getRouteUid()) && Objects.equals(before.getContentSha256(), after.getContentSha256())
                && Objects.equals(before.getReleaseId(), after.getReleaseId()) && Objects.equals(before.getCompilerProfileId(), after.getCompilerProfileId());
    }

    private static ControlFence fence(GatewayUrlExposureBO row) { return new ControlFence(row.getVersion(), row.getDesiredGeneration(), row.getDesiredState()); }

    private static boolean desired(RouteSnapshot route, GatewayUrlExposureBO row, Material material) {
        if (!Objects.equals(row.getRouteUid(), route.uid()) || route.controlGeneration() != row.getDesiredGeneration()
                || route.controlState() != row.getDesiredState()) return false;
        JsonNode parents = route.document().path("spec").path("parentRefs");
        if (row.getDesiredState() != GatewayUrlExposureStateEnum.OPEN) return parents.isArray() && parents.isEmpty();
        if (material == null || material.binding() == null) return false;
        ObjectNode expected = (ObjectNode) material.object().path("spec").deepCopy();
        expected.putArray("parentRefs").add(material.parent());
        return GatewayCiliumReleaseArtifact.sameCanonicalObject(expected, route.document().path("spec"));
    }

    private static void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("EXPOSURE_NETWORK_TRANSACTION_FORBIDDEN");
    }

    private static DeliveryResult retry(String code) { return DeliveryResult.retryableFailure(code, "The current exposure is not yet confirmed"); }
    private record Material(JsonNode object, JsonNode parent, PublicationBinding binding) {
        private Material { object = object.deepCopy(); parent = parent.deepCopy(); }
        @Override public JsonNode object() { return object.deepCopy(); }
        @Override public JsonNode parent() { return parent.deepCopy(); }
    }
}
