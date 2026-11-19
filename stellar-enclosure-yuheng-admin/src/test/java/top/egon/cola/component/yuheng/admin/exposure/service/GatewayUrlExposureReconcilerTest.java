package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.outbox.delivery.DeliveryResult;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.*;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.*;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.*;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuntimeRoute;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Fake cluster enforces actual UID/RV concurrency; positive admission/observation stubs exist only inside this fixture. */
class GatewayUrlExposureReconcilerTest {
    static final long OWNER = 101L, ID = 810001L;
    static final Instant NOW = Instant.parse("2026-11-18T20:00:00Z");
    Fixture f;
    @BeforeEach void setup() throws Exception { MDC.put("tenantId", "101"); f = new Fixture(); }
    @AfterEach void clear() { MDC.clear(); }

    @Test
    void exposesAnExplicitOwnerAndStableExposureReconciliationBoundary() throws Exception {
        assertThat(GatewayUrlExposureReconciler.class.getMethod("reconcile", long.class, long.class).getReturnType()).isEqualTo(DeliveryResult.class);
    }

    @Test
    void foreignEventCannotSelectATenantOrTouchAnyControlOrClusterResource() {
        var result = f.reconciler().reconcile(202L, ID);
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.PERMANENT_FAILURE);
        verifyNoInteractions(f.controls, f.client, f.releases);
    }

    @Test
    void closesEvenEmptyParentsWithANewGenerationFenceBeforeClaimingAnything() {
        f.close(2L);
        f.reconciler().reconcile(OWNER, ID);
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(f.cluster.get().resourceVersion()).isEqualTo("2");
        assertThat(f.cluster.get().controlGeneration()).isEqualTo(2L);
        assertThat(f.cluster.get().document().path("spec").path("parentRefs")).isEmpty();
        assertThat(f.row.getObservedGeneration()).isZero();
        assertThat(f.row.getReconcileStatus()).isEqualTo("PENDING");
    }

    @Test
    void canonicalNumericEncodingPreservesExactFrozenBindingAuthority() {
        JsonNode frozen = f.artifact.chunkManifest().path("dependencies").get(1).path("publicationBinding");
        assertThat(frozen.path("backends").get(0).path("port").decimalValue()).isEqualByComparingTo("80");
        assertThat(f.compiler.contentDigest(frozen)).isNotEqualTo(f.compiler.contentDigest(f.json.valueToTree(f.binding)));
        f.reconciler().reconcile(OWNER, ID);
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.OPEN);
    }

    @Test
    void differentCurrentBindingRevisionCannotAttachDespiteMatchingCoordinates() throws Exception {
        ObjectNode changed = f.json.valueToTree(f.binding); changed.put("bindingVersion", 2L);
        var different = f.json.treeToValue(changed, PublicationBinding.class);
        when(f.resolver.resolve(OWNER, 800001L, "orders-read", "9001")).thenReturn(Optional.of(different));
        var result = f.reconciler().reconcile(OWNER, ID);
        assertThat(result.code()).isEqualTo("PUBLICATION_BINDING_UNAVAILABLE");
        assertThat(f.patchCalls).isZero();
    }

    @Test
    void oldAttachAfterNewEmptyDetachCannotPassItsOldResourceVersion() {
        var old = f.reconciler(); var newer = f.reconciler();
        f.beforePatch = () -> { f.close(2L); newer.reconcile(OWNER, ID); };
        old.reconcile(OWNER, ID);
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(f.cluster.get().controlState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(f.cluster.get().document().path("spec").path("parentRefs")).isEmpty();
    }

    @Test
    void oldAttachBeforeNewDetachIsOverwrittenAndNeverReappearsAfterTheClose() {
        var older = f.reconciler(); var newer = f.reconciler();
        f.afterPatch = () -> { f.close(2L); newer.reconcile(OWNER, ID); };
        older.reconcile(OWNER, ID);
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.OPEN, GatewayUrlExposureStateEnum.CLOSED);
        assertThat(f.cluster.get().controlState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(f.cluster.get().document().path("spec").path("parentRefs")).isEmpty();
    }

    @Test
    void finalPrimaryReadSeesAWithdrawnIntentAndNeverUsesAnOldOpenDecision() {
        f.beforePreflight = () -> { f.row.setDesiredState(GatewayUrlExposureStateEnum.WITHDRAWN).setDesiredGeneration(3L).setVersion(3L); };
        f.reconciler().reconcile(OWNER, ID);
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.WITHDRAWN);
        assertThat(f.cluster.get().document().path("spec").path("parentRefs")).isEmpty();
    }

    @Test
    void unavailableFinalControlReadPreventsEveryPatch() {
        f.beforePreflight = () -> f.failNextRead = true;
        var result = f.reconciler().reconcile(OWNER, ID);
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE_FAILURE);
        assertThat(f.patchCalls).isZero();
        assertThat(f.appliedStates).isEmpty();
    }

    @Test
    void unknownPatchResponseIsResolvedByReadingTheFixedObjectBack() {
        f.losePatchResponse = true;
        f.reconciler().reconcile(OWNER, ID);
        assertThat(f.patchCalls).isEqualTo(1);
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.OPEN);
        assertThat(f.getCalls).isGreaterThanOrEqualTo(2);
        assertThat(f.cluster.get().document().path("spec").path("parentRefs")).hasSize(1);
    }

    @Test
    void unexpectedUidIsNeitherPatchedNorAdoptedEvenWhenReplacementIsAttached() {
        f.attachExisting();
        var document = (ObjectNode) f.cluster.get().document(); document.withObject("metadata").put("uid", "replacement-uid");
        f.cluster.set(f.snapshot(document));
        f.close(2L);
        var result = f.reconciler().reconcile(OWNER, ID);
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.PERMANENT_FAILURE);
        assertThat(result.code()).isEqualTo("ROUTE_UID_CONFLICT");
        assertThat(f.row.getRouteUid()).isEqualTo("uid-original");
        assertThat(f.patchCalls).isZero();
        assertThat(f.row.getObservedGeneration()).isZero();
    }

    @Test
    void deletedPublishedObjectIsOnlyRecreatedInertAndItsNewUidRequiresManualRecovery() {
        f.cluster.set(null);
        var result = f.reconciler().reconcile(OWNER, ID);
        assertThat(result.code()).isEqualTo("ROUTE_UID_CONFLICT");
        assertThat(f.createCalls).isEqualTo(1);
        assertThat(f.cluster.get().target().name()).isEqualTo("yuheng-exposure-810001");
        assertThat(f.cluster.get().document().path("spec").path("parentRefs")).isEmpty();
        assertThat(f.row.getRouteUid()).isEqualTo("uid-original");
        assertThat(f.patchCalls).isZero();
    }

    @Test
    void firstInertCreatePinsItsReadBackUidBeforeAnyAttach() {
        f.cluster.set(null); f.row.setRouteUid(null); f.loseCreateResponse = true;
        f.reconciler().reconcile(OWNER, ID);
        assertThat(f.createCalls).isEqualTo(1);
        assertThat(f.row.getRouteUid()).isEqualTo("uid-created");
        assertThat(f.order.indexOf("pin")).isLessThan(f.order.indexOf("patch:OPEN"));
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.OPEN);
    }

    @Test
    void initialAttachedObjectCannotBeAdoptedWithoutAPersistedUid() {
        f.row.setRouteUid(null); f.attachExisting();
        var result = f.reconciler().reconcile(OWNER, ID);
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE_FAILURE);
        assertThat(result.code()).isEqualTo("ROUTE_INITIAL_STATE_CONFLICT");
        assertThat(f.row.getRouteUid()).isNull();
        verify(f.controls, never()).bindRouteUid(anyLong(), anyLong(), anyLong(), anyString(), any());
        assertThat(f.patchCalls).isZero();
    }

    @Test
    void lostCreateResponseCannotAuthorizeAdoptionOfAnAlreadyAttachedReadBack() {
        f.cluster.set(null); f.row.setRouteUid(null); f.loseCreateResponse = true;
        f.afterCreate = f::attachExisting;
        var result = f.reconciler().reconcile(OWNER, ID);
        assertThat(result.code()).isEqualTo("ROUTE_INITIAL_STATE_CONFLICT");
        assertThat(f.createCalls).isEqualTo(1);
        assertThat(f.row.getRouteUid()).isNull();
        verify(f.controls, never()).bindRouteUid(anyLong(), anyLong(), anyLong(), anyString(), any());
        assertThat(f.patchCalls).isZero();
    }

    @Test
    void missingIndependentProofCannotAttachOrAdvanceObservedGeneration() {
        var realObservation = new GatewayUrlExposureObservationService(Clock.fixed(NOW, ZoneOffset.UTC), f.json, new GatewayHttpRouteCompiler(f.json));
        var result = f.reconciler(realObservation).reconcile(OWNER, ID);
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE_FAILURE);
        assertThat(f.patchCalls).isZero();
        assertThat(f.row.getObservedGeneration()).isZero();
        assertThat(f.row.getReconcileStatus()).isEqualTo("PENDING");
    }

    @Test
    void ordinaryCloseDoesNotRequireAReadableArtifactAndMissingProofStaysPending() {
        doAnswer(call -> { f.order.add("artifact"); return Optional.empty(); }).when(f.releases).loadNative(anyString());
        f.attachExisting(); f.close(2L);
        var result = f.reconciler().reconcile(OWNER, ID);
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE_FAILURE);
        assertThat(f.order.indexOf("patch:CLOSED")).isLessThan(f.order.indexOf("artifact"));
        assertThat(f.row.getObservedGeneration()).isZero();
    }

    @Test
    void alreadyMatchedRouteRenewsEvidenceWithoutAnotherPatch() {
        f.close(2L); f.reconciler().reconcile(OWNER, ID);
        int mutations = f.patchCalls; int observations = f.observationCalls;
        f.reconciler().reconcile(OWNER, ID);
        assertThat(f.patchCalls).isEqualTo(mutations);
        assertThat(f.observationCalls).isGreaterThan(observations);
    }

    @Test
    void goneAndResourceVersionConflictsRereadBothAuthoritiesWithinABoundedBudget() {
        f.failPatchOnce = Kind.GONE;
        f.reconciler().reconcile(OWNER, ID);
        assertThat(f.patchCalls).isEqualTo(2);
        assertThat(f.getCalls).isGreaterThanOrEqualTo(3);
        assertThat(f.appliedStates).containsExactly(GatewayUrlExposureStateEnum.OPEN);
    }

    static final class Fixture {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final GatewayPersistenceContextComponent installation = mock(GatewayPersistenceContextComponent.class);
        final GatewayUrlExposureRepository controls = mock(GatewayUrlExposureRepository.class);
        final GatewayReleaseRepository releases = mock(GatewayReleaseRepository.class);
        final GatewayKubernetesClient client = mock(GatewayKubernetesClient.class);
        final GatewayUrlExposureObservationService observations = mock(GatewayUrlExposureObservationService.class);
        final GatewayHttpRouteCompiler compiler = new GatewayHttpRouteCompiler(json);
        final BindingResolver resolver = mock(BindingResolver.class);
        final RouteTarget target = new RouteTarget(OWNER, ID, "yuheng-routes");
        final PublicationBinding binding = binding();
        final GatewayCiliumReleaseArtifact artifact;
        final JsonNode compiled;
        final JsonNode parent;
        final AtomicReference<RouteSnapshot> cluster = new AtomicReference<>();
        final List<GatewayUrlExposureStateEnum> appliedStates = new ArrayList<>();
        final List<String> order = new ArrayList<>();
        final TransactionTemplate tx = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        });
        GatewayUrlExposureBO row;
        Runnable beforePatch, afterPatch, beforePreflight, afterCreate;
        Kind failPatchOnce;
        boolean failNextRead, losePatchResponse, loseCreateResponse;
        int patchCalls, createCalls, getCalls, observationCalls;

        Fixture() throws Exception {
            var runtime = new GatewayRuntimeRoute("orders-read", "9001", "api.example.com", "GET", "/orders", Set.of(AccessZone.PUBLIC), 0, true, null);
            var result = compiler.compile(new ExposureInput(OWNER, 800001L, "820001", ID, 1L, runtime, GatewayProtocol.HTTP, true,
                    Map.of("host", "api.example.com", "httpMethod", "GET", "pathPattern", "/orders", "accessZones", List.of("PUBLIC")),
                    List.of(), List.of(new ResourceReference("BUSINESS_API", "9001", "1", "d".repeat(64)))), binding);
            compiled = result.routeObject(); parent = result.approvedParent();
            row = GatewayUrlExposureBO.builder().id(ID).ownerTenantId(OWNER).tenantId(0L).gatewayGroupId(800001L).routeId("orders-read")
                    .clusterId("cluster-main").gatewayNamespace("edge-system").gatewayName("public-apps").gatewayUid("gateway-uid")
                    .listenerName("https-api").hostname(result.hostname()).httpMethod(result.httpMethod()).pathMatchType("EXACT").pathValue("/orders")
                    .routeKind("HTTPRoute").routeNamespace("yuheng-routes").routeName(target.name()).routeUid("uid-original")
                    .version(1L).desiredGeneration(1L).desiredState(GatewayUrlExposureStateEnum.OPEN).observedGeneration(0L).observedState("UNKNOWN")
                    .reconcileStatus("PENDING").contentSha256(result.contentSha256()).compilerProfileId(GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID)
                    .releaseId(820001L).updateTime(NOW).build();
            ObjectNode canonical = json.createObjectNode().put("releaseId", "820001").put("ownerTenantId", "101").put("gatewayGroupId", "800001").put("generatedAt", NOW.toString());
            canonical.putObject("sourceContent");
            var publication = canonical.putObject("exposurePublication").put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION)
                    .put("target", "CILIUM_GATEWAY_API").put("gatewayClassName", "cilium").put("routeControllerName", GatewayHttpRouteCompiler.CONTROLLER_NAME)
                    .put("compilerVersion", "2").put("capabilityProfileId", GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID)
                    .put("publicationDomainId", "public-https-main").put("backendAuthorizationProfile", GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE);
            publication.putArray("exposures").add(result.snapshotEntry());
            var activation = json.createObjectNode().put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION).put("releaseId", "820001");
            activation.putArray("exposures").addObject().put("exposureId", "810001").put("desiredGeneration", "1").put("effectiveState", "OPEN")
                    .put("contentSha256", result.contentSha256()).putNull("precondition");
            var manifest = json.createObjectNode().put("schemaVersion", GatewayHttpRouteCompiler.SCHEMA_VERSION).put("compilerVersion", "2")
                    .put("capabilityProfileId", GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID);
            var resource = manifest.putArray("resources").addObject().put("exposureId", "810001").put("contentSha256", result.contentSha256());
            resource.set("object", compiled); resource.set("approvedParent", parent); manifest.set("dependencies", result.dependencies());
            artifact = new GatewayCiliumReleaseArtifact(canonical, activation, manifest);
            cluster.set(snapshot(document("uid-original", "1", new ControlFence(1, 1, GatewayUrlExposureStateEnum.OPEN), compiled)));
            when(installation.installationTenantId()).thenReturn(OWNER);
            doAnswer(call -> {
                String old = MDC.get("tenantId"); MDC.put("tenantId", "101");
                try { return ((Callable<?>) call.getArgument(1)).call(); }
                finally { if (old == null) MDC.remove("tenantId"); else MDC.put("tenantId", old); }
            }).when(installation).call(isNull(), any());
            when(controls.findOwned(anyLong(), anyLong())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); order.add("control");
                if (failNextRead) { failNextRead = false; throw new DataAccessResourceFailureException("fixture authority unavailable"); }
                return call.getArgument(0, Long.class) == OWNER && call.getArgument(1, Long.class) == ID ? Optional.of(copy()) : Optional.empty();
            });
            when(controls.bindRouteUid(anyLong(), anyLong(), anyLong(), anyString(), any())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                if (row.getRouteUid() != null || !row.getVersion().equals(call.getArgument(2))) return false;
                order.add("pin"); row.setRouteUid(call.getArgument(3)).setVersion(row.getVersion() + 1); return true;
            });
            when(controls.recordObservation(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), any(), any()))
                    .thenAnswer(call -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); observationCalls++;
                        if (!row.getVersion().equals(call.getArgument(2)) || !row.getDesiredGeneration().equals(call.getArgument(3))
                                || !row.getRouteUid().equals(call.getArgument(4))) return false;
                        row.setObservedState(call.getArgument(5)).setObservedGeneration(call.getArgument(6)).setReconcileStatus(call.getArgument(7))
                                .setObservation(call.getArgument(8)).setLastErrorCode(call.getArgument(9)).setVersion(row.getVersion() + 1); return true;
                    });
            when(releases.loadNative(anyString())).thenAnswer(call -> { order.add("artifact"); return Optional.of(artifact); });
            when(resolver.resolve(OWNER, 800001L, "orders-read", "9001")).thenReturn(Optional.of(binding));
            when(client.scope()).thenReturn(new AccessScope("cluster-main", "yuheng-routes", Set.of(), Set.of(), Set.of()));
            when(client.getRoute(any())).thenAnswer(call -> { network(); getCalls++; return Optional.ofNullable(cluster.get()); });
            when(client.createInert(any(), any(), any())).thenAnswer(call -> {
                network(); createCalls++; if (cluster.get() != null) throw new Failure(Kind.CONFLICT, 409);
                var created = snapshot(document("uid-created", "1", call.getArgument(1), call.getArgument(2)));
                cluster.set(created); Runnable hook = afterCreate; afterCreate = null; if (hook != null) hook.run();
                if (loseCreateResponse) { loseCreateResponse = false; throw new Failure(Kind.UNAVAILABLE); } return created;
            });
            when(client.patchDesired(any(), any(), any(), any())).thenAnswer(call -> {
                network(); patchCalls++; RouteSnapshot expected = call.getArgument(0); ControlFence fence = call.getArgument(1);
                Runnable hook = beforePatch; beforePatch = null; if (hook != null) hook.run();
                if (failPatchOnce != null) { Kind failure = failPatchOnce; failPatchOnce = null; throw new Failure(failure); }
                var actual = cluster.get();
                if (actual == null || !expected.uid().equals(actual.uid()) || !expected.resourceVersion().equals(actual.resourceVersion())) throw new Failure(Kind.CONFLICT, 409);
                ObjectNode updated = (ObjectNode) actual.document();
                if (fence.desiredState() == GatewayUrlExposureStateEnum.OPEN) {
                    JsonNode source = call.getArgument(2); updated.set("spec", source.path("spec").deepCopy());
                    updated.withObject("spec").putArray("parentRefs").add((JsonNode) call.getArgument(3));
                } else updated.withObject("spec").putArray("parentRefs");
                var applied = snapshot(document(actual.uid(), Integer.toString(Integer.parseInt(actual.resourceVersion()) + 1), fence, updated));
                cluster.set(applied); appliedStates.add(fence.desiredState()); order.add("patch:" + fence.desiredState());
                hook = afterPatch; afterPatch = null; if (hook != null) hook.run();
                if (losePatchResponse) { losePatchResponse = false; throw new Failure(Kind.UNAVAILABLE); }
                return applied;
            });
            when(observations.preflight(any(), any(), any(), any())).thenAnswer(call -> {
                Runnable hook = beforePreflight; beforePreflight = null; if (hook != null) hook.run();
                return new PreAttachResult(true, null); // Explicit workflow-only authority stub; real denial is separately tested.
            });
            when(observations.evaluate(any(), any(), any(), any())).thenAnswer(call -> pending(call.getArgument(0), "DATAPLANE_EVIDENCE_INCOMPLETE"));
            when(observations.unavailable(any(), any(), anyString())).thenAnswer(call -> pending(call.getArgument(0), call.getArgument(2)));
        }

        GatewayUrlExposureReconciler reconciler() { return reconciler(observations); }
        GatewayUrlExposureReconciler reconciler(GatewayUrlExposureObservationService observer) {
            var beans = new StaticListableBeanFactory(); beans.addBean("bindings", resolver);
            beans.addBean("snapshot", (SnapshotProvider) (row, binding, route) -> KubernetesSnapshot.minimal(route, NOW));
            return new GatewayUrlExposureReconciler(installation, controls, releases, tx, client, observer,
                    beans.getBeanProvider(BindingResolver.class), beans.getBeanProvider(SnapshotProvider.class), beans.getBeanProvider(ProofProvider.class),
                    Clock.fixed(NOW, ZoneOffset.UTC), json);
        }
        GatewayUrlExposureBO copy() { return json.convertValue(json.valueToTree(row), GatewayUrlExposureBO.class); }
        void close(long generation) { row.setDesiredState(GatewayUrlExposureStateEnum.CLOSED).setDesiredGeneration(generation).setVersion(row.getVersion() + 1); }
        void attachExisting() { ObjectNode doc = (ObjectNode) cluster.get().document(); doc.withObject("spec").putArray("parentRefs").add(parent); cluster.set(snapshot(doc)); }
        ObservationResult pending(GatewayUrlExposureBO row, String error) {
            var observation = json.createObjectNode().put("schemaVersion", 1); observation.putNull("kubernetesRoute"); observation.putArray("conditions");
            return new ObservationResult(row.getVersion(), row.getDesiredGeneration(), row.getRouteUid(), row.getObservedState(), row.getObservedGeneration(),
                    "ROUTE_UID_CONFLICT".equals(error) ? "FAILED" : "PENDING", observation, error);
        }
        ObjectNode document(String uid, String rv, ControlFence fence, JsonNode source) {
            ObjectNode doc = (ObjectNode) source.deepCopy(); ObjectNode metadata = doc.withObject("metadata");
            metadata.put("uid", uid).put("resourceVersion", rv).put("generation", 1L);
            metadata.withObject("annotations").put(GatewayKubernetesClient.EXPOSURE_ANNOTATION, Long.toString(ID))
                    .put(GatewayKubernetesClient.GENERATION_ANNOTATION, Long.toString(fence.desiredGeneration()))
                    .put(GatewayKubernetesClient.STATE_ANNOTATION, fence.desiredState().wireValue());
            return doc;
        }
        RouteSnapshot snapshot(JsonNode doc) {
            JsonNode meta = doc.path("metadata"), annotations = meta.path("annotations");
            return new RouteSnapshot(target, meta.path("uid").asText(), meta.path("resourceVersion").asText(), meta.path("generation").asLong(),
                    annotations.path(GatewayKubernetesClient.GENERATION_ANNOTATION).asLong(),
                    GatewayUrlExposureStateEnum.fromWire(annotations.path(GatewayKubernetesClient.STATE_ANNOTATION).asText()), doc);
        }
        static void network() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
        static PublicationBinding binding() {
            var domain = new PublicationDomainVO("cluster-main", "public-https-main", "topology-1", List.of(new BindingVO("edge-system", "public-apps", "https-api")), true);
            var backend = new BackendReference("apps-prod", "orders-public", "service-uid", "7", 80, 8080, 100, "HTTP_1_1",
                    new GrantReference("apps-prod", "allow-orders-public", "grant-uid", "23", "yuheng-routes", "orders-public"), "a".repeat(64), Set.of("pod-a:container-a"), true);
            return new PublicationBinding(OWNER, 800001L, "orders-read", "9001", "prod", "business", "orders-prod", 1L, domain,
                    new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true), "yuheng-routes",
                    Set.of("api.example.com"), Set.of("GET"), Set.of(), List.of(backend),
                    new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE, "https://resource.example.com/orders", "1", "b".repeat(64), true, List.of()));
        }
    }
}
