package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.AuthorizationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BackendReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GatewayReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GrantReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ListSnapshot;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.RouteSnapshot;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.RouteTarget;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ServiceKey;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.DataplaneAttestation;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.DependencyAttestation;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.KubernetesSnapshot;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.ObservationResult;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.ProofProvider;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService.ProofRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayUrlExposureObservationServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-18T18:00:00Z");
    private static final ServiceKey SERVICE = new ServiceKey("apps-prod", "synthetic-public");
    private final GatewayUrlExposureObservationService service = new GatewayUrlExposureObservationService(Clock.fixed(NOW, ZoneOffset.UTC), JSON, new GatewayHttpRouteCompiler(JSON));

    @Test
    void observationHasSeparatePreAttachAndAfterAttachBoundaries() throws Exception {
        Class<?> service = Class.forName("top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureObservationService");
        assertThat(service.getConstructor(Clock.class, ObjectMapper.class, GatewayHttpRouteCompiler.class)).isNotNull();
        assertThat(Arrays.stream(service.getMethods()).map(java.lang.reflect.Method::getName))
                .contains("preflight", "evaluate");
    }

    @Test
    void preflightAllowsOldDetachedControlButNeverRequiresAttachedTrafficProof() {
        Fixture fixture = fixture(GatewayUrlExposureStateEnum.OPEN, false);
        var old = replace(fixture.actual(), route(false, 7L, GatewayUrlExposureStateEnum.CLOSED, "route-uid"));
        var calls = new AtomicInteger();
        ProofProvider provider = new ProofProvider() {
            @Override public Optional<DependencyAttestation> verifyDependencies(ProofRequest request) { return proofs().verifyDependencies(request); }
            @Override public Optional<DataplaneAttestation> verifyDataplane(ProofRequest request) {
                calls.incrementAndGet(); throw new AssertionError("preattach must not probe attached traffic");
            }
        };
        assertThat(service.preflight(fixture.row(), fixture.binding(), old, provider).ready()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(service.preflight(fixture.row(), fixture.binding(), old, ProofProvider.unavailable()).ready()).isFalse();
        assertThat(service.preflight(fixture.row(), null, old, provider).lastErrorCode()).isEqualTo("PUBLICATION_BINDING_UNAVAILABLE");
        var future = replace(old, route(false, 9L, GatewayUrlExposureStateEnum.OPEN, "route-uid"));
        assertThat(service.preflight(fixture.row(), fixture.binding(), future, provider).ready()).isFalse();
    }

    @Test
    void currentOpenEvidenceAdvancesOnlyTheBusinessGenerationAndUsesMinimumExpiry() {
        Fixture fixture = fixture(GatewayUrlExposureStateEnum.OPEN, true);
        var result = service.evaluate(fixture.row(), fixture.binding(), fixture.actual(), proofs());
        assertThat(result.synced()).isTrue();
        assertThat(result.expectedVersion()).isEqualTo(4L);
        assertThat(result.evaluatedGeneration()).isEqualTo(8L);
        assertThat(result.observedGeneration()).isEqualTo(8L);
        assertThat(result.observedState()).isEqualTo("OPEN");
        assertThat(result.verifiedUid()).isEqualTo("route-uid");
        JsonNode json = result.observation();
        assertThat(json.path("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(json.at("/kubernetesRoute/metadataGeneration").asText()).isEqualTo("3");
        assertThat(json.at("/kubernetesRoute/conditionObservedGeneration").asText()).isEqualTo("3");
        assertThat(json.at("/evidence/expiresAt").asText()).isEqualTo(NOW.plusSeconds(30).toString());
        assertThat(json.at("/evidence/dependencies").size()).isEqualTo(2);
        for (JsonNode condition : json.path("conditions")) { assertThat(condition.path("observedGeneration").asText()).isEqualTo("8"); }
        ((ObjectNode) json).put("modified", true);
        assertThat(result.observation().has("modified")).isFalse();
        assertThat(fixture.row().getObservedGeneration()).isEqualTo(7L);
    }

    @Test
    void missingProofKeepsPreviousObservationAndCannotTurnPatchSuccessIntoConvergence() {
        Fixture fixture = fixture(GatewayUrlExposureStateEnum.OPEN, true);
        var result = service.evaluate(fixture.row(), fixture.binding(), fixture.actual(), ProofProvider.unavailable());
        assertThat(result.synced()).isFalse();
        assertThat(result.reconcileStatus()).isEqualTo("PENDING");
        assertThat(result.observedGeneration()).isEqualTo(7L);
        assertThat(result.observedState()).isEqualTo("OPEN");
        assertThat(condition(result, "DataplaneConverged")).isEqualTo("Unknown");
        assertThat(result.observation().at("/evidence/expiresAt").isMissingNode()).isTrue();
    }

    @Test
    void staleWrongControllerDuplicateStatusAndWrongParentNeverBecomeCurrentEvidence() {
        Fixture fixture = fixture(GatewayUrlExposureStateEnum.OPEN, true);
        for (UnaryOperator<ObjectNode> mutation : List.<UnaryOperator<ObjectNode>>of(
                value -> { ((ObjectNode) value.at("/status/parents/0/conditions/0")).put("observedGeneration", 2); return value; },
                value -> { ((ObjectNode) value.at("/status/parents/0")).put("controllerName", "foreign/controller"); return value; },
                value -> { ((com.fasterxml.jackson.databind.node.ArrayNode) value.at("/status/parents")).add(value.at("/status/parents/0").deepCopy()); return value; },
                value -> { ((ObjectNode) value.at("/spec/parentRefs/0")).remove("sectionName"); return value; },
                value -> { ((com.fasterxml.jackson.databind.node.ArrayNode) value.at("/spec/parentRefs")).add(parent().put("name", "another-gateway")); return value; })) {
            var actual = mutateRoute(fixture.actual(), mutation);
            assertThat(service.evaluate(fixture.row(), fixture.binding(), actual, proofs()).synced()).isFalse();
        }
        var oldFence = replace(fixture.actual(), route(true, 7L, GatewayUrlExposureStateEnum.OPEN, "route-uid"));
        var result = service.evaluate(fixture.row(), fixture.binding(), oldFence, proofs());
        assertThat(result.lastErrorCode()).isEqualTo("ROUTE_CONTROL_NOT_CURRENT");
        assertThat(result.observedGeneration()).isEqualTo(7L);
    }

    @Test
    void liveGatewayServiceGrantAndEndpointSliceChangesBlockPreattach() {
        Fixture fixture = fixture(GatewayUrlExposureStateEnum.OPEN, false);
        ObjectNode wrongGateway = (ObjectNode) fixture.actual().gateway();
        ((ObjectNode) wrongGateway.at("/spec/listeners/0")).put("protocol", "HTTP");
        assertNotReady(fixture, snapshot(fixture.actual(), wrongGateway, fixture.actual().services(), fixture.actual().referenceGrants(), fixture.actual().endpointSlices()));
        ObjectNode staleGateway = (ObjectNode) fixture.actual().gateway();
        ((ObjectNode) staleGateway.at("/status/conditions/1")).put("observedGeneration", 4);
        assertNotReady(fixture, snapshot(fixture.actual(), staleGateway, fixture.actual().services(), fixture.actual().referenceGrants(), fixture.actual().endpointSlices()));
        for (String field : List.of("uid", "resourceVersion")) {
            ObjectNode changed = (ObjectNode) fixture.actual().services().get(0);
            ((ObjectNode) changed.path("metadata")).put(field, "changed");
            assertNotReady(fixture, snapshot(fixture.actual(), fixture.actual().gateway(), List.of(changed), fixture.actual().referenceGrants(), fixture.actual().endpointSlices()));
        }
        ObjectNode wrongPort = (ObjectNode) fixture.actual().services().get(0);
        ((ObjectNode) wrongPort.at("/spec/ports/0")).put("targetPort", 9090);
        assertNotReady(fixture, snapshot(fixture.actual(), fixture.actual().gateway(), List.of(wrongPort), fixture.actual().referenceGrants(), fixture.actual().endpointSlices()));
        ObjectNode wrongGrant = (ObjectNode) fixture.actual().referenceGrants().get(0);
        ((ObjectNode) wrongGrant.at("/spec/from/0")).put("namespace", "other-routes");
        assertNotReady(fixture, snapshot(fixture.actual(), fixture.actual().gateway(), fixture.actual().services(), List.of(wrongGrant), fixture.actual().endpointSlices()));
        ObjectNode wrongSlice = (ObjectNode) fixture.actual().endpointSlices().get(SERVICE).items().get(0);
        ((ObjectNode) wrongSlice.at("/endpoints/0/conditions")).put("ready", false);
        assertNotReady(fixture, snapshot(fixture.actual(), fixture.actual().gateway(), fixture.actual().services(), fixture.actual().referenceGrants(),
                Map.of(SERVICE, new ListSnapshot("28", List.of(wrongSlice)))));
    }

    @Test
    void closedWithStaleParentStatusNeedsBothFullRuleInventoryAndIndependentDataplaneEvidence() {
        Fixture fixture = fixture(GatewayUrlExposureStateEnum.CLOSED, false);
        var noProof = service.evaluate(fixture.row(), fixture.binding(), fixture.actual(), ProofProvider.unavailable());
        assertThat(condition(noProof, "Detached")).isEqualTo("True");
        assertThat(condition(noProof, "NoAlternateRoute")).isEqualTo("Unknown");
        assertThat(noProof.observedGeneration()).isEqualTo(7L);
        var complete = service.evaluate(fixture.row(), fixture.binding(), fixture.actual(), proofs());
        assertThat(complete.synced()).isTrue();
        assertThat(complete.observedState()).isEqualTo("CLOSED");
        assertThat(complete.observation().at("/kubernetesRoute/controllerName").isNull()).isTrue();
        assertThat(complete.observation().at("/kubernetesRoute/conditionObservedGeneration").isNull()).isTrue();
        assertThat(complete.observation().at("/evidence/expiresAt").asText()).isEqualTo(NOW.plusSeconds(60).toString());
        var partial = new KubernetesSnapshot(fixture.actual().route(), fixture.actual().gateway(), fixture.actual().services(), fixture.actual().referenceGrants(),
                fixture.actual().endpointSlices(), fixture.actual().routeLists(), false, NOW);
        assertThat(service.evaluate(fixture.row(), fixture.binding(), partial, proofs()).synced()).isFalse();
        var missingBinding = service.evaluate(fixture.row(), null, KubernetesSnapshot.minimal(fixture.actual().route(), NOW), proofs());
        assertThat(missingBinding.synced()).isFalse();
        assertThat(condition(missingBinding, "Detached")).isEqualTo("True");
    }

    @Test
    void defaultWildcardPrefixAndUnsupportedAlternateRoutesPreventClosure() {
        Fixture fixture = fixture(GatewayUrlExposureStateEnum.WITHDRAWN, false);
        for (String pathType : List.of("DEFAULT", "PathPrefix", "RegularExpression", "ENCODED")) {
            ObjectNode alternate = (ObjectNode) route(true, 8L, GatewayUrlExposureStateEnum.OPEN, "alternate-uid").document();
            ((ObjectNode) alternate.path("metadata")).put("name", "platform-default");
            ((ObjectNode) alternate.path("spec")).putArray("hostnames").add("*.example.com");
            ObjectNode rule = ((ObjectNode) alternate.path("spec")).putArray("rules").addObject();
            if (!"DEFAULT".equals(pathType)) {
                rule.putArray("matches").addObject().putObject("path").put("type", "ENCODED".equals(pathType) ? "Exact" : pathType)
                        .put("value", "ENCODED".equals(pathType) ? "/api/%69tems" : "/api");
            }
            var actual = inventory(fixture.actual(), List.of(fixture.actual().route().document(), alternate));
            var result = service.evaluate(fixture.row(), fixture.binding(), actual, proofs());
            assertThat(result.synced()).isFalse();
            assertThat(condition(result, "NoAlternateRoute")).isNotEqualTo("True");
        }
    }

    @Test
    void unconfirmedTargetsCannotBeCountedAsConvergedAndIsolationCannotOpenEveryTarget() {
        Fixture closed = fixture(GatewayUrlExposureStateEnum.CLOSED, false);
        ProofProvider incomplete = proofs(UnaryOperator.identity(), proof -> new DataplaneAttestation(proof.requestDigest(), proof.verifiedAt(), proof.validUntil(), proof.evidenceRef(),
                proof.ruleSetDigest(), proof.targetIds(), Set.of("node-a/envoy-a"), Set.of("node-a/envoy-a"), Set.of(), true, true));
        assertThat(service.evaluate(closed.row(), closed.binding(), closed.actual(), incomplete).synced()).isFalse();
        ProofProvider isolated = proofs(UnaryOperator.identity(), proof -> new DataplaneAttestation(proof.requestDigest(), proof.verifiedAt(), proof.validUntil(), proof.evidenceRef(),
                proof.ruleSetDigest(), proof.targetIds(), Set.of("node-a/envoy-a"), Set.of("node-a/envoy-a"), Set.of("node-b/envoy-b"), true, true));
        assertThat(service.evaluate(closed.row(), closed.binding(), closed.actual(), isolated).synced()).isTrue();
        Fixture open = fixture(GatewayUrlExposureStateEnum.OPEN, true);
        ProofProvider allIsolated = proofs(UnaryOperator.identity(), proof -> new DataplaneAttestation(proof.requestDigest(), proof.verifiedAt(), proof.validUntil(), proof.evidenceRef(),
                proof.ruleSetDigest(), proof.targetIds(), Set.of(), Set.of(), proof.targetIds(), true, true));
        assertThat(service.evaluate(open.row(), open.binding(), open.actual(), allIsolated).synced()).isFalse();
    }

    @Test
    void expiredAndMisboundProofsNeverAdvanceAndUidDriftNeverChangesThePinnedIdentity() {
        Fixture fixture = fixture(GatewayUrlExposureStateEnum.OPEN, true);
        ProofProvider staleDependency = proofs(proof -> new DependencyAttestation(proof.requestDigest(), NOW, NOW.minusSeconds(1), proof.evidenceRef(), true, true, true, true), UnaryOperator.identity());
        assertThat(service.preflight(fixture.row(), fixture.binding(), fixture.actual(), staleDependency).ready()).isFalse();
        ProofProvider wrongDigest = proofs(UnaryOperator.identity(), proof -> new DataplaneAttestation("0".repeat(64), NOW, proof.validUntil(), proof.evidenceRef(), proof.ruleSetDigest(),
                proof.targetIds(), proof.configurationVerified(), proof.requestVerified(), proof.isolated(), true, true));
        assertThat(service.evaluate(fixture.row(), fixture.binding(), fixture.actual(), wrongDigest).synced()).isFalse();
        var replacement = replace(fixture.actual(), route(true, 8L, GatewayUrlExposureStateEnum.OPEN, "replacement-uid"));
        var conflict = service.evaluate(fixture.row(), fixture.binding(), replacement, proofs());
        assertThat(conflict.reconcileStatus()).isEqualTo("FAILED");
        assertThat(conflict.lastErrorCode()).isEqualTo("ROUTE_UID_CONFLICT");
        assertThat(conflict.verifiedUid()).isEqualTo("route-uid");
        assertThat(conflict.observedGeneration()).isEqualTo(7L);
        assertThat(conflict.observation().path("kubernetesRoute").isNull()).isTrue();
    }

    @Test
    void endpointSliceDigestHasAPortableGoldenVectorAndOmitsCollectionResourceVersion() throws Exception {
        JsonNode z = JSON.readTree("{\"metadata\":{\"namespace\":\"a\",\"name\":\"z\"},\"kind\":\"EndpointSlice\"}");
        JsonNode b = JSON.readTree("{\"metadata\":{\"namespace\":\"a\",\"name\":\"b\"},\"kind\":\"EndpointSlice\"}");
        assertThat(service.endpointSliceDigest(new ListSnapshot("opaque-1", List.of(z, b))))
                .isEqualTo("4f03080c39afa18f8c9edd47ed46f80236117925864a7643d13bfd5b9e33e347")
                .isEqualTo(service.endpointSliceDigest(new ListSnapshot("opaque-2", List.of(b, z))));
    }

    private void assertNotReady(Fixture fixture, KubernetesSnapshot actual) {
        assertThat(service.preflight(fixture.row(), fixture.binding(), actual, proofs()).ready()).isFalse();
    }
    private static String condition(ObservationResult result, String type) {
        for (JsonNode condition : result.observation().path("conditions")) { if (type.equals(condition.path("type").asText())) { return condition.path("status").asText(); } }
        return "missing";
    }
    private static ProofProvider proofs() { return proofs(UnaryOperator.identity(), UnaryOperator.identity()); }
    private static ProofProvider proofs(UnaryOperator<DependencyAttestation> dependency, UnaryOperator<DataplaneAttestation> dataplane) {
        return new ProofProvider() {
            @Override public Optional<DependencyAttestation> verifyDependencies(ProofRequest request) {
                return Optional.of(dependency.apply(new DependencyAttestation(request.requestDigest(), NOW, NOW.plusSeconds(30), "synthetic/dependency", true, true, true, true)));
            }
            @Override public Optional<DataplaneAttestation> verifyDataplane(ProofRequest request) {
                Set<String> targets = Set.of("node-a/envoy-a", "node-b/envoy-b");
                return Optional.of(dataplane.apply(new DataplaneAttestation(request.requestDigest(), NOW, NOW.plusSeconds(60), "synthetic/dataplane",
                        request.routeInventoryDigest(), targets, targets, targets, Set.of(), true, true)));
            }
        };
    }
    private static KubernetesSnapshot mutateRoute(KubernetesSnapshot actual, UnaryOperator<ObjectNode> mutation) {
        var old = actual.route();
        return replace(actual, new RouteSnapshot(old.target(), old.uid(), old.resourceVersion(), old.metadataGeneration(), old.controlGeneration(), old.controlState(),
                mutation.apply((ObjectNode) old.document())));
    }
    private static KubernetesSnapshot replace(KubernetesSnapshot actual, RouteSnapshot route) {
        return new KubernetesSnapshot(route, actual.gateway(), actual.services(), actual.referenceGrants(), actual.endpointSlices(),
                Map.of("yuheng-routes", new ListSnapshot("routes-30", List.of(route.document()))), actual.routeInventoryComplete(), actual.observedAt());
    }
    private static KubernetesSnapshot inventory(KubernetesSnapshot actual, List<JsonNode> routes) {
        return new KubernetesSnapshot(actual.route(), actual.gateway(), actual.services(), actual.referenceGrants(), actual.endpointSlices(),
                Map.of("yuheng-routes", new ListSnapshot("routes-31", routes)), true, NOW);
    }
    private static KubernetesSnapshot snapshot(KubernetesSnapshot actual, JsonNode gateway, List<JsonNode> services, List<JsonNode> grants, Map<ServiceKey, ListSnapshot> slices) {
        return new KubernetesSnapshot(actual.route(), gateway, services, grants, slices, actual.routeLists(), actual.routeInventoryComplete(), NOW);
    }
    private Fixture fixture(GatewayUrlExposureStateEnum desired, boolean attached) {
        GatewayUrlExposureBO row = new GatewayUrlExposureBO().setId(810001L).setOwnerTenantId(7L).setGatewayGroupId(9L).setVersion(4L)
                .setRouteId("items").setClusterId("cluster-a").setGatewayNamespace("edge-system").setGatewayName("public-apps").setGatewayUid("gateway-uid")
                .setListenerName("https-api").setHostname("public.apps.example.com").setHttpMethod("GET").setPathMatchType("EXACT").setPathValue("/api/items")
                .setRouteKind("HTTPRoute").setRouteNamespace("yuheng-routes").setRouteName("yuheng-exposure-810001").setRouteUid("route-uid")
                .setDesiredState(desired).setDesiredGeneration(8L).setObservedState("OPEN").setObservedGeneration(7L).setContentSha256("a".repeat(64))
                .setCompilerProfileId(GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID);
        ObjectNode slice = object("EndpointSlice", "apps-prod", "synthetic-slice", "slice-uid", "19", 1);
        slice.put("apiVersion", "discovery.k8s.io/v1");
        ((ObjectNode) slice.path("metadata")).putObject("labels").put("kubernetes.io/service-name", SERVICE.name());
        ((ObjectNode) slice.path("metadata")).putArray("ownerReferences").addObject().put("kind", "Service").put("name", SERVICE.name()).put("uid", "service-uid");
        slice.put("addressType", "IPv4"); slice.putArray("ports").addObject().put("name", "http").put("port", 8080).put("protocol", "TCP");
        ObjectNode endpoint = slice.putArray("endpoints").addObject(); endpoint.putArray("addresses").add("10.2.3.4");
        endpoint.putObject("conditions").put("ready", true); endpoint.putObject("targetRef").put("kind", "Pod").put("namespace", "apps-prod").put("uid", "pod-uid");
        var slices = new ListSnapshot("slices-27", List.of(slice));
        var grant = new GrantReference("apps-prod", "allow-synthetic", "grant-uid", "23", "yuheng-routes", SERVICE.name());
        var backend = new BackendReference("apps-prod", SERVICE.name(), "service-uid", "17", 80, 8080, 100, "HTTP_1_1", grant,
                service.endpointSliceDigest(slices), Set.of("pod-uid"), true);
        var binding = new PublicationBinding(7L, 9L, "items", "readItems", "prod", "knowledge", "binding-a", 1L,
                new PublicationDomainVO("cluster-a", "public-domain", "topology-1", List.of(new BindingVO("edge-system", "public-apps", "https-api")), true),
                new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true), "yuheng-routes",
                Set.of("public.apps.example.com"), Set.of("GET"), Set.of("/api/items"), List.of(backend),
                new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE, "https://api.example.test/items", "1", "b".repeat(64), true, List.of()));
        ObjectNode service = object("Service", "apps-prod", SERVICE.name(), "service-uid", "17", 1); service.put("apiVersion", "v1");
        ObjectNode spec = service.putObject("spec").put("type", "ClusterIP");
        spec.putArray("ports").addObject().put("port", 80).put("targetPort", 8080).put("protocol", "TCP").put("appProtocol", "http");
        ObjectNode grantObject = object("ReferenceGrant", "apps-prod", "allow-synthetic", "grant-uid", "23", 1);
        ObjectNode grantSpec = grantObject.putObject("spec");
        grantSpec.putArray("from").addObject().put("group", "gateway.networking.k8s.io").put("kind", "HTTPRoute").put("namespace", "yuheng-routes");
        grantSpec.putArray("to").addObject().put("group", "").put("kind", "Service").put("name", SERVICE.name());
        RouteSnapshot route = route(attached, 8L, desired, "route-uid");
        return new Fixture(row, binding, new KubernetesSnapshot(route, gateway(), List.of(service), List.of(grantObject), Map.of(SERVICE, slices),
                Map.of("yuheng-routes", new ListSnapshot("routes-30", List.of(route.document()))), true, NOW));
    }
    private static ObjectNode gateway() {
        ObjectNode gateway = object("Gateway", "edge-system", "public-apps", "gateway-uid", "11", 5);
        ObjectNode spec = gateway.putObject("spec").put("gatewayClassName", "cilium");
        ObjectNode listener = spec.putArray("listeners").addObject().put("name", "https-api").put("hostname", "public.apps.example.com").put("protocol", "HTTPS").put("port", 443);
        listener.putObject("tls").put("mode", "Terminate").putArray("certificateRefs").addObject().put("group", "").put("kind", "Secret").put("name", "public-tls");
        ObjectNode allowed = listener.putObject("allowedRoutes"); allowed.putArray("kinds").addObject().put("group", "gateway.networking.k8s.io").put("kind", "HTTPRoute");
        allowed.putObject("namespaces").put("from", "Selector").putObject("selector").putObject("matchLabels").put("kubernetes.io/metadata.name", "yuheng-routes");
        ObjectNode status = gateway.putObject("status");
        var conditions = status.putArray("conditions"); addCondition(conditions, "Accepted", 5); addCondition(conditions, "Programmed", 5);
        var listenerConditions = status.putArray("listeners").addObject().put("name", "https-api").putArray("conditions");
        for (String type : List.of("Accepted", "ResolvedRefs", "Programmed")) { addCondition(listenerConditions, type, 5); }
        return gateway;
    }
    private static RouteSnapshot route(boolean attached, long controlGeneration, GatewayUrlExposureStateEnum state, String uid) {
        ObjectNode route = object("HTTPRoute", "yuheng-routes", "yuheng-exposure-810001", uid, "70", 3);
        ObjectNode spec = route.putObject("spec"); var parents = spec.putArray("parentRefs"); if (attached) { parents.add(parent()); }
        ObjectNode parentStatus = route.putObject("status").putArray("parents").addObject().put("controllerName", GatewayHttpRouteCompiler.CONTROLLER_NAME);
        parentStatus.set("parentRef", parent()); var conditions = parentStatus.putArray("conditions");
        addCondition(conditions, "Accepted", 3); addCondition(conditions, "ResolvedRefs", 3);
        return new RouteSnapshot(new RouteTarget(7L, 810001L, "yuheng-routes"), uid, "70", 3L, controlGeneration, state, route);
    }
    private static ObjectNode parent() {
        return JSON.createObjectNode().put("group", "gateway.networking.k8s.io").put("kind", "Gateway").put("namespace", "edge-system").put("name", "public-apps").put("sectionName", "https-api");
    }
    private static ObjectNode object(String kind, String namespace, String name, String uid, String rv, long generation) {
        ObjectNode value = JSON.createObjectNode().put("apiVersion", "gateway.networking.k8s.io/v1").put("kind", kind);
        value.putObject("metadata").put("namespace", namespace).put("name", name).put("uid", uid).put("resourceVersion", rv).put("generation", generation);
        return value;
    }
    private static void addCondition(com.fasterxml.jackson.databind.node.ArrayNode values, String type, long generation) {
        values.addObject().put("type", type).put("status", "True").put("observedGeneration", generation);
    }
    private record Fixture(GatewayUrlExposureBO row, PublicationBinding binding, KubernetesSnapshot actual) { }
}
