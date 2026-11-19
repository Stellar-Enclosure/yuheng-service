package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BackendReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ListSnapshot;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.RouteSnapshot;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ServiceKey;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure evaluation of current objects and independent evidence. It performs no network, persistence or patch. */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public final class GatewayUrlExposureObservationService {
    private static final String CONTROLLER = GatewayHttpRouteCompiler.CONTROLLER_NAME;
    @NonNull @Qualifier("gatewayProjectionClock") private final Clock clock;
    @NonNull @Qualifier("jacksonObjectMapper") private final ObjectMapper mapper;
    @NonNull @Qualifier("gatewayHttpRouteCompiler") private final GatewayHttpRouteCompiler canonical;

    /** Checks live prerequisites without requiring the route to be attached or business traffic to have passed. */
    public PreAttachResult preflight(GatewayUrlExposureBO row, @Nullable PublicationBinding binding,
            KubernetesSnapshot actual, ProofProvider proofs) {
        try {
            requireBinding(row, binding);
            requireRoute(row, actual.route());
            if (actual.route().controlGeneration() > row.getDesiredGeneration()) { throw fail("ROUTE_CONTROL_CONFLICT"); }
            requireLiveDependencies(row, binding, actual);
            ProofRequest request = request(row, binding, actual, Stage.PRE_ATTACH);
            if (dependencies(proofs, request) == null) { throw fail("DEPENDENCY_EVIDENCE_INCOMPLETE"); }
            return new PreAttachResult(true, null);
        } catch (RuntimeException failure) {
            String reason = code(failure, "DEPENDENCY_EVIDENCE_INCOMPLETE");
            log.debug("Exposure pre-attach evidence is incomplete. exposureId={} reason={}", row.getId(), reason);
            return new PreAttachResult(false, reason);
        }
    }

    /** Evaluates the current desired generation; a missing provider can never become a positive proof. */
    public ObservationResult evaluate(GatewayUrlExposureBO row, @Nullable PublicationBinding binding,
            KubernetesSnapshot actual, ProofProvider proofs) {
        requireControl(row);
        try { requireRoute(row, actual.route()); }
        catch (RuntimeException failure) { return unavailable(row, actual.route(), code(failure, "ROUTE_OBSERVATION_INVALID")); }
        JsonNode document = actual.route().document();
        boolean currentControl = actual.route().controlGeneration() == row.getDesiredGeneration()
                && actual.route().controlState() == row.getDesiredState();
        boolean attached = managedParent(row, document);
        ObjectNode observation = base(row, actual.route(), attached);
        List<Condition> conditions = new ArrayList<>();
        boolean bindingAvailable = true;
        ProofRequest request = null;
        try { requireBinding(row, binding); request = request(row, binding, actual, Stage.OBSERVE); }
        catch (RuntimeException invalid) { bindingAvailable = false; }
        DataplaneAttestation dataplane = bindingAvailable ? dataplane(proofs, request) : null;
        boolean completeDataplane = dataplane != null && validTargets(dataplane)
                && (row.getDesiredState() != GatewayUrlExposureStateEnum.OPEN || dataplane.targetIds().stream().anyMatch(target ->
                    !dataplane.isolated().contains(target) && dataplane.configurationVerified().contains(target) && dataplane.requestVerified().contains(target)));
        boolean synced;
        String error = null;
        DependencyAttestation dependencyProof = null;
        if (row.getDesiredState() == GatewayUrlExposureStateEnum.OPEN) {
            ParentStatus status = parentStatus(row, document);
            conditions.add(new Condition("Accepted", status.accepted(), "CurrentParentEvaluated"));
            conditions.add(new Condition("ResolvedRefs", status.resolvedRefs(), "CurrentParentEvaluated"));
            boolean live = false;
            try {
                requireBinding(row, binding); requireLiveDependencies(row, binding, actual);
                dependencyProof = dependencies(proofs, request); live = dependencyProof != null;
            }
            catch (RuntimeException invalid) { error = code(invalid, "DEPENDENCY_EVIDENCE_INCOMPLETE"); }
            if (!live && error == null) { error = "DEPENDENCY_EVIDENCE_INCOMPLETE"; }
            conditions.add(new Condition("Programmed", gatewayProgrammed(row, actual.gateway()) ? "True" : "Unknown", "GatewayListenerEvaluated"));
            conditions.add(new Condition("DataplaneConverged", completeDataplane ? "True" : "Unknown", completeDataplane ? "ReachableTargetsVerified" : "Degraded"));
            synced = currentControl && exactManagedParent(row, document) && live && "True".equals(status.accepted()) && "True".equals(status.resolvedRefs()) && completeDataplane;
        } else {
            boolean detached = document.path("spec").path("parentRefs").isArray() && document.path("spec").path("parentRefs").isEmpty();
            String alternate = bindingAvailable && actual.routeInventoryComplete() && dataplane != null && dataplane.noAlternateRouteVerified()
                    ? noAlternateRoute(row, binding, actual) : "Unknown";
            conditions.add(new Condition("Detached", detached ? "True" : "False", detached ? "ParentRefsRemoved" : "ParentRefsRemain"));
            conditions.add(new Condition("NoAlternateRoute", alternate, "True".equals(alternate) ? "InventoryVerified" : "Degraded"));
            conditions.add(new Condition("DataplaneConverged", completeDataplane ? "True" : "Unknown", completeDataplane ? "ReachableTargetsVerified" : "Degraded"));
            synced = currentControl && detached && "True".equals(alternate) && completeDataplane;
        }
        if (!synced && error == null) {
            error = !currentControl ? "ROUTE_CONTROL_NOT_CURRENT" : !bindingAvailable ? "PUBLICATION_BINDING_UNAVAILABLE" : "DATAPLANE_EVIDENCE_INCOMPLETE";
        }
        writeConditions(observation, row, conditions);
        if (request != null) {
            ObjectNode evidence = observation.putObject("evidence").put("requestDigest", request.requestDigest())
                    .put("topologyVersion", binding.publicationDomain().topologyVersion()).put("routeInventoryDigest", request.routeInventoryDigest())
                    .put("generatedAt", clock.instant().toString());
            var dependencies = evidence.putArray("dependencies");
            for (JsonNode item : actual.services()) { dependency(dependencies, item); }
            for (JsonNode item : actual.referenceGrants()) { dependency(dependencies, item); }
            if (dataplane != null) {
                evidence.put("evidenceRef", dataplane.evidenceRef()).put("targetSetDigest", targetDigest(dataplane.targetIds()))
                        .put("targetCount", dataplane.targetIds().size()).put("confirmedCount", dataplane.requestVerified().size())
                        .put("isolatedCount", dataplane.isolated().size());
            }
            if (synced) {
                Instant expiry = dataplane.validUntil();
                if (dependencyProof != null && dependencyProof.validUntil().isBefore(expiry)) { expiry = dependencyProof.validUntil(); }
                evidence.put("expiresAt", expiry.toString());
            }
        }
        return result(row, observation, synced, error);
    }

    /** Records a bounded failure without presenting a replacement object's UID as the persisted identity. */
    public ObservationResult unavailable(GatewayUrlExposureBO row, @Nullable RouteSnapshot route, String stableErrorCode) {
        requireControl(row);
        ObjectNode observation = mapper.createObjectNode().put("schemaVersion", 1);
        observation.putNull("kubernetesRoute"); observation.putArray("conditions");
        String error = stableErrorCode != null && stableErrorCode.matches("[A-Z][A-Z0-9_]{0,127}") ? stableErrorCode : "OBSERVATION_UNAVAILABLE";
        return new ObservationResult(row.getVersion(), row.getDesiredGeneration(), row.getRouteUid(), row.getObservedState(),
                row.getObservedGeneration(), "ROUTE_UID_CONFLICT".equals(error) ? "FAILED" : "PENDING", observation, error);
    }

    private void requireLiveDependencies(GatewayUrlExposureBO row, PublicationBinding binding, KubernetesSnapshot actual) {
        JsonNode gateway = actual.gateway();
        if (!identity(gateway, "Gateway", row.getGatewayNamespace(), row.getGatewayName(), row.getGatewayUid(), null)
                || !"cilium".equals(gateway.path("spec").path("gatewayClassName").asText()) || !gatewayProgrammed(row, gateway)) {
            throw fail("GATEWAY_NOT_PROGRAMMED");
        }
        JsonNode listener = unique(gateway.path("spec").path("listeners"), "name", row.getListenerName());
        if (listener == null || !"HTTPS".equals(listener.path("protocol").asText()) || listener.path("port").asInt(-1) != 443
                || !"Terminate".equals(listener.path("tls").path("mode").asText())
                || listener.path("hostname").asText("").isEmpty()
                || !hostMatches(listener.path("hostname").asText(""), row.getHostname()) || !allowedNamespace(listener, row)) {
            throw fail("GATEWAY_LISTENER_INVALID");
        }
        JsonNode certificates = listener.path("tls").path("certificateRefs");
        if (!certificates.isArray() || certificates.isEmpty() || certificates.size() > 8) { throw fail("GATEWAY_TLS_REFERENCE_INVALID"); }
        for (JsonNode certificate : certificates) {
            if (!certificate.path("group").asText("").isEmpty() || !"Secret".equals(certificate.path("kind").asText("Secret"))
                    || certificate.path("name").asText("").isBlank()) { throw fail("GATEWAY_TLS_REFERENCE_INVALID"); }
        }
        Set<String> readyPods = new HashSet<>();
        for (BackendReference expected : binding.backends()) {
            JsonNode service = object(actual.services(), "Service", expected.namespace(), expected.name());
            if (!identity(service, "Service", expected.namespace(), expected.name(), expected.uid(), expected.resourceVersion())
                    || !"ClusterIP".equals(service.path("spec").path("type").asText("ClusterIP"))
                    || !service.path("spec").path("externalIPs").isMissingNode() && !service.path("spec").path("externalIPs").isEmpty()) {
                throw fail("BACKEND_SERVICE_CHANGED");
            }
            int matched = 0;
            for (JsonNode port : service.path("spec").path("ports")) {
                if (port.path("port").asInt(-1) != expected.port()) { continue; }
                if (!port.path("targetPort").isIntegralNumber() || port.path("targetPort").intValue() != expected.targetPort()
                        || !"TCP".equals(port.path("protocol").asText("TCP")) || !http1(port.path("appProtocol"))
                        || port.path("nodePort").asInt(0) != 0) { throw fail("BACKEND_PROTOCOL_INVALID"); }
                matched++;
            }
            if (matched != 1) { throw fail("BACKEND_SERVICE_CHANGED"); }
            if (expected.grant() != null) {
                var grant = expected.grant();
                JsonNode actualGrant = object(actual.referenceGrants(), "ReferenceGrant", grant.namespace(), grant.name());
                if (!identity(actualGrant, "ReferenceGrant", grant.namespace(), grant.name(), grant.uid(), grant.resourceVersion())
                        || !exactGrant(actualGrant, binding.routeNamespace(), expected.name())) { throw fail("REFERENCE_GRANT_CHANGED"); }
            } else if (!expected.namespace().equals(binding.routeNamespace())) { throw fail("REFERENCE_GRANT_MISSING"); }
            ListSnapshot slices = actual.endpointSlices().get(new ServiceKey(expected.namespace(), expected.name()));
            if (slices == null || !expected.endpointSliceDigest().equals(endpointSliceDigest(slices))) { throw fail("ENDPOINT_SLICES_CHANGED"); }
            Set<String> pods = readyPods(expected, slices);
            if (!pods.equals(expected.readyEndpointIds()) || pods.isEmpty()) { throw fail("READY_ENDPOINTS_CHANGED"); }
            for (String pod : pods) { if (!readyPods.add(pod)) { throw fail("BACKEND_ENDPOINT_OVERLAP"); } }
        }
    }

    /** Canonical sorted full EndpointSlice items; collection RV is an observation cursor, not content. */
    public String endpointSliceDigest(ListSnapshot slices) { return canonical.contentDigest(mapper.valueToTree(sorted(slices.items()))); }

    private static Set<String> readyPods(BackendReference service, ListSnapshot slices) {
        Set<String> pods = new HashSet<>();
        if (slices.resourceVersion() == null || slices.resourceVersion().isBlank() || slices.items().isEmpty() || slices.items().size() > 128) {
            throw fail("ENDPOINT_SLICES_INVALID");
        }
        for (JsonNode slice : slices.items()) {
            if (!"EndpointSlice".equals(slice.path("kind").asText()) || !service.namespace().equals(slice.path("metadata").path("namespace").asText())
                    || !service.name().equals(slice.path("metadata").path("labels").path("kubernetes.io/service-name").asText())) { throw fail("ENDPOINT_SLICES_INVALID"); }
            boolean owner = false;
            for (JsonNode reference : slice.path("metadata").path("ownerReferences")) {
                owner |= "Service".equals(reference.path("kind").asText()) && service.uid().equals(reference.path("uid").asText())
                        && service.name().equals(reference.path("name").asText());
            }
            if (!owner) { throw fail("ENDPOINT_SLICES_INVALID"); }
            int ports = 0;
            for (JsonNode port : slice.path("ports")) {
                if (port.path("port").isIntegralNumber() && port.path("port").intValue() == service.targetPort()
                        && "TCP".equals(port.path("protocol").asText("TCP")) && http1(port.path("appProtocol"))) { ports++; }
                else { throw fail("ENDPOINT_SLICES_INVALID"); }
            }
            if (ports != 1 || !slice.path("endpoints").isArray() || slice.path("endpoints").size() > 1000) { throw fail("ENDPOINT_SLICES_INVALID"); }
            for (JsonNode endpoint : slice.path("endpoints")) {
                if (!endpoint.path("conditions").path("ready").isBoolean() || !endpoint.path("conditions").path("ready").booleanValue()
                        || endpoint.path("conditions").path("terminating").asBoolean(false)) { continue; }
                JsonNode target = endpoint.path("targetRef"); String uid = target.path("uid").asText("");
                if (!"Pod".equals(target.path("kind").asText()) || !service.namespace().equals(target.path("namespace").asText(service.namespace()))
                        || !token(uid, 128) || !endpoint.path("addresses").isArray() || endpoint.path("addresses").isEmpty()) { throw fail("ENDPOINT_SLICES_INVALID"); }
                pods.add(uid);
            }
        }
        return Set.copyOf(pods);
    }

    private ProofRequest request(GatewayUrlExposureBO row, PublicationBinding binding, KubernetesSnapshot actual, Stage stage) {
        if (actual.observedAt().isAfter(clock.instant())) { throw fail("OBSERVATION_TIME_INVALID"); }
        ObjectNode inventory = mapper.createObjectNode();
        actual.routeLists().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ObjectNode part = inventory.putObject(entry.getKey()).put("resourceVersion", entry.getValue().resourceVersion());
            part.set("items", mapper.valueToTree(sorted(entry.getValue().items())));
        });
        String inventoryDigest = canonical.contentDigest(inventory);
        ObjectNode material = mapper.createObjectNode().put("stage", stage.name()).put("owner", row.getOwnerTenantId())
                .put("exposure", row.getId()).put("version", row.getVersion()).put("desiredGeneration", row.getDesiredGeneration())
                .put("desiredState", row.getDesiredState().wireValue()).put("contentSha256", row.getContentSha256())
                .put("observedAt", actual.observedAt().toString()).put("routeInventoryDigest", inventoryDigest)
                .put("routeInventoryComplete", actual.routeInventoryComplete()).put("hostname", row.getHostname())
                .put("httpMethod", row.getHttpMethod()).put("pathMatchType", row.getPathMatchType()).put("pathValue", row.getPathValue());
        material.set("binding", mapper.valueToTree(binding)); material.set("route", actual.route().document()); material.set("gateway", actual.gateway());
        material.set("services", mapper.valueToTree(sorted(actual.services()))); material.set("grants", mapper.valueToTree(sorted(actual.referenceGrants())));
        ObjectNode endpoints = material.putObject("endpointSlices");
        actual.endpointSlices().entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().namespace() + "/" + entry.getKey().name()))
                .forEach(entry -> endpoints.put(entry.getKey().namespace() + "/" + entry.getKey().name(), endpointSliceDigest(entry.getValue())));
        return new ProofRequest(stage, row.getOwnerTenantId(), row.getId(), row.getDesiredGeneration(), row.getDesiredState(),
                row.getContentSha256(), row.getHostname(), row.getHttpMethod(), row.getPathMatchType(), row.getPathValue(),
                binding, actual.route().uid(), actual.route().resourceVersion(), actual.route().metadataGeneration(),
                inventoryDigest, canonical.contentDigest(material), actual.observedAt());
    }

    @Nullable
    private DependencyAttestation dependencies(ProofProvider provider, @Nullable ProofRequest request) {
        if (request == null) { return null; }
        try {
            var proof = provider.verifyDependencies(request).orElse(null);
            return proof != null && proof.requestDigest().equals(request.requestDigest()) && current(proof.verifiedAt(), proof.validUntil(), request.observedAt())
                    && token(proof.evidenceRef(), 256) && proof.tlsTrustVerified() && proof.authorizationVerified()
                    && proof.networkAdmissionVerified() && proof.publicationDomainComplete() ? proof : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    @Nullable
    private DataplaneAttestation dataplane(ProofProvider provider, ProofRequest request) {
        try {
            var proof = provider.verifyDataplane(request).orElse(null);
            return proof != null && proof.requestDigest().equals(request.requestDigest())
                    && proof.ruleSetDigest().equals(request.routeInventoryDigest()) && token(proof.evidenceRef(), 256)
                    && current(proof.verifiedAt(), proof.validUntil(), request.observedAt()) ? proof : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    private boolean current(Instant verified, Instant until, Instant observed) {
        Instant now = clock.instant();
        return verified != null && until != null && !verified.isBefore(observed) && !verified.isAfter(now) && until.isAfter(now);
    }

    private static boolean validTargets(DataplaneAttestation proof) {
        if (!proof.allEntryPathsVerified() || proof.targetIds().isEmpty() || proof.targetIds().size() > 4096
                || !proof.targetIds().containsAll(proof.configurationVerified()) || !proof.targetIds().containsAll(proof.requestVerified())
                || !proof.targetIds().containsAll(proof.isolated())) { return false; }
        for (String target : proof.targetIds()) {
            if (!token(target, 256) || !proof.isolated().contains(target)
                    && !(proof.configurationVerified().contains(target) && proof.requestVerified().contains(target))) { return false; }
        }
        return true;
    }

    private static String noAlternateRoute(GatewayUrlExposureBO row, PublicationBinding binding, KubernetesSnapshot actual) {
        if (!actual.routeLists().containsKey(binding.routeNamespace())) { return "Unknown"; }
        for (var list : actual.routeLists().entrySet()) {
            if (!token(list.getValue().resourceVersion(), 128)) { return "Unknown"; }
            for (JsonNode route : list.getValue().items()) {
                if (!"HTTPRoute".equals(route.path("kind").asText()) || !list.getKey().equals(route.path("metadata").path("namespace").asText())) { return "Unknown"; }
                if (row.getRouteUid().equals(route.path("metadata").path("uid").asText())) { continue; }
                boolean reachable = false;
                for (BindingVO physical : binding.publicationDomain().bindings()) {
                    reachable |= parent(route, physical.gatewayNamespace(), physical.gatewayName(), physical.listenerName());
                }
                if (!reachable) { continue; }
                boolean host = route.path("spec").path("hostnames").isMissingNode() || route.path("spec").path("hostnames").isEmpty();
                for (JsonNode name : route.path("spec").path("hostnames")) {
                    String pattern = name.asText("");
                    if (!token(pattern, 253)) { return "Unknown"; }
                    host |= hostMatches(pattern, row.getHostname()) || hostMatches(row.getHostname(), pattern);
                }
                if (!host) { continue; }
                if (!route.path("spec").path("rules").isArray()) { return "Unknown"; }
                for (JsonNode rule : route.path("spec").path("rules")) {
                    JsonNode matches = rule.path("matches");
                    if (matches.isMissingNode() || matches.isEmpty()) { return "False"; }
                    for (JsonNode match : matches) {
                        String method = match.path("method").asText("*");
                        if (!"*".equals(method) && !"*".equals(row.getHttpMethod()) && !method.equals(row.getHttpMethod())) { continue; }
                        String type = match.path("path").path("type").asText("PathPrefix");
                        String value = match.path("path").path("value").asText("/");
                        if (!Set.of("Exact", "PathPrefix").contains(type) || !value.startsWith("/") || value.contains("%")
                                || value.contains("//") || value.contains("\\") || value.contains("?") || value.contains("#")
                                || java.util.Arrays.stream(value.split("/", -1)).anyMatch(segment -> ".".equals(segment) || "..".equals(segment))) { return "Unknown"; }
                        if ("Exact".equals(type) && (value.equals(row.getPathValue())
                                || "PATH_PREFIX".equals(row.getPathMatchType()) && segmentPrefix(row.getPathValue(), value))
                                || "PathPrefix".equals(type) && (segmentPrefix(value, row.getPathValue())
                                || "PATH_PREFIX".equals(row.getPathMatchType()) && segmentPrefix(row.getPathValue(), value))) { return "False"; }
                    }
                }
            }
        }
        return "True";
    }

    private void requireBinding(GatewayUrlExposureBO row, @Nullable PublicationBinding binding) {
        requireControl(row);
        if (binding == null) { throw fail("PUBLICATION_BINDING_UNAVAILABLE"); }
        canonical.validateBinding(binding);
        if (binding.ownerTenantId() != row.getOwnerTenantId() || binding.gatewayGroupId() != row.getGatewayGroupId()
                || !binding.routeId().equals(row.getRouteId()) || !binding.publicationDomain().clusterId().equals(row.getClusterId())
                || !binding.routeNamespace().equals(row.getRouteNamespace()) || !binding.gateway().namespace().equals(row.getGatewayNamespace())
                || !binding.gateway().name().equals(row.getGatewayName()) || !binding.gateway().uid().equals(row.getGatewayUid())
                || !binding.gateway().listenerName().equals(row.getListenerName()) || !binding.approvedMethods().contains(row.getHttpMethod())
                || binding.approvedHostnames().stream().noneMatch(host -> hostMatches(host, row.getHostname()))
                || "PATH_PREFIX".equals(row.getPathMatchType()) && !binding.approvedPathPrefixes().contains(row.getPathValue())
                || !GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID.equals(row.getCompilerProfileId())) { throw fail("PUBLICATION_BINDING_CHANGED"); }
    }

    private static void requireControl(GatewayUrlExposureBO row) {
        if (row == null || row.getId() == null || row.getId() <= 0 || row.getOwnerTenantId() == null || row.getOwnerTenantId() <= 0
                || row.getVersion() == null || row.getVersion() < 0 || row.getDesiredGeneration() == null || row.getDesiredGeneration() <= 0
                || row.getDesiredState() == null || row.getObservedGeneration() == null || row.getObservedState() == null) { throw fail("OBSERVATION_CONTROL_INVALID"); }
    }

    private static void requireRoute(GatewayUrlExposureBO row, RouteSnapshot route) {
        if (route == null || !Objects.equals(row.getRouteUid(), route.uid())) { throw fail("ROUTE_UID_CONFLICT"); }
        if (route.target().ownerTenantId() != row.getOwnerTenantId() || route.target().exposureId() != row.getId()
                || !route.target().namespace().equals(row.getRouteNamespace())
                || !identity(route.document(), "HTTPRoute", row.getRouteNamespace(), row.getRouteName(), route.uid(), route.resourceVersion())
                || !route.document().path("metadata").path("generation").isIntegralNumber()
                || route.document().path("metadata").path("generation").longValue() != route.metadataGeneration()) { throw fail("ROUTE_OBSERVATION_INVALID"); }
    }

    private static boolean gatewayProgrammed(GatewayUrlExposureBO row, @Nullable JsonNode gateway) {
        if (gateway == null) { return false; }
        JsonNode rawGeneration = gateway.path("metadata").path("generation");
        long generation = rawGeneration.isIntegralNumber() ? rawGeneration.longValue() : -1;
        JsonNode listener = unique(gateway.path("status").path("listeners"), "name", row.getListenerName());
        return generation > 0 && "True".equals(condition(gateway.path("status").path("conditions"), "Programmed", generation))
                && "True".equals(condition(gateway.path("status").path("conditions"), "Accepted", generation))
                && listener != null && "True".equals(condition(listener.path("conditions"), "Programmed", generation))
                && "True".equals(condition(listener.path("conditions"), "Accepted", generation))
                && "True".equals(condition(listener.path("conditions"), "ResolvedRefs", generation));
    }

    private static boolean allowedNamespace(JsonNode listener, GatewayUrlExposureBO row) {
        JsonNode allowed = listener.path("allowedRoutes");
        if (!allowed.path("kinds").isArray() || allowed.path("kinds").size() != 1
                || !"HTTPRoute".equals(allowed.path("kinds").get(0).path("kind").asText())
                || !"gateway.networking.k8s.io".equals(allowed.path("kinds").get(0).path("group").asText())) { return false; }
        JsonNode namespaces = allowed.path("namespaces");
        if ("Same".equals(namespaces.path("from").asText("Same"))) { return row.getRouteNamespace().equals(row.getGatewayNamespace()); }
        JsonNode selector = namespaces.path("selector");
        return "Selector".equals(namespaces.path("from").asText()) && selector.path("matchLabels").size() == 1
                && row.getRouteNamespace().equals(selector.path("matchLabels").path("kubernetes.io/metadata.name").asText())
                && (selector.path("matchExpressions").isMissingNode() || selector.path("matchExpressions").isEmpty());
    }

    private static boolean exactGrant(JsonNode grant, String sourceNamespace, String service) {
        JsonNode from = grant.path("spec").path("from"); JsonNode to = grant.path("spec").path("to");
        return from.isArray() && from.size() == 1 && to.isArray() && to.size() == 1
                && "gateway.networking.k8s.io".equals(from.get(0).path("group").asText()) && "HTTPRoute".equals(from.get(0).path("kind").asText())
                && sourceNamespace.equals(from.get(0).path("namespace").asText()) && to.get(0).path("group").asText("").isEmpty()
                && "Service".equals(to.get(0).path("kind").asText()) && service.equals(to.get(0).path("name").asText());
    }

    private static ParentStatus parentStatus(GatewayUrlExposureBO row, JsonNode route) {
        if (!exactManagedParent(row, route)) { return new ParentStatus("Unknown", "Unknown"); }
        JsonNode found = null;
        for (JsonNode status : route.path("status").path("parents")) {
            if (CONTROLLER.equals(status.path("controllerName").asText()) && exactParentRef(row, status.path("parentRef"))) {
                if (found != null) { return new ParentStatus("Unknown", "Unknown"); }
                found = status;
            }
        }
        long generation = route.path("metadata").path("generation").asLong(-1);
        return found == null ? new ParentStatus("Unknown", "Unknown") : new ParentStatus(
                condition(found.path("conditions"), "Accepted", generation), condition(found.path("conditions"), "ResolvedRefs", generation));
    }

    private static String condition(JsonNode values, String type, long generation) {
        JsonNode found = unique(values, "type", type);
        if (found == null || !found.path("observedGeneration").isIntegralNumber()
                || found.path("observedGeneration").longValue() != generation || generation <= 0) { return "Unknown"; }
        String status = found.path("status").asText("Unknown");
        return Set.of("True", "False", "Unknown").contains(status) ? status : "Unknown";
    }
    private static boolean managedParent(GatewayUrlExposureBO row, JsonNode route) { return parent(route, row.getGatewayNamespace(), row.getGatewayName(), row.getListenerName()); }
    private static boolean exactManagedParent(GatewayUrlExposureBO row, JsonNode route) {
        JsonNode parents = route.path("spec").path("parentRefs");
        return parents.isArray() && parents.size() == 1 && exactParentRef(row, parents.get(0));
    }
    private static boolean exactParentRef(GatewayUrlExposureBO row, JsonNode reference) {
        return parentRef(reference, row.getRouteNamespace(), row.getGatewayNamespace(), row.getGatewayName(), row.getListenerName())
                && row.getListenerName().equals(reference.path("sectionName").asText()) && reference.path("port").isMissingNode();
    }
    private static boolean parent(JsonNode route, String namespace, String name, String listener) {
        for (JsonNode reference : route.path("spec").path("parentRefs")) {
            if (parentRef(reference, route.path("metadata").path("namespace").asText(), namespace, name, listener)) { return true; }
        }
        return false;
    }
    private static boolean parentRef(JsonNode ref, String routeNamespace, String namespace, String name, String listener) {
        return "gateway.networking.k8s.io".equals(ref.path("group").asText("gateway.networking.k8s.io"))
                && "Gateway".equals(ref.path("kind").asText("Gateway")) && namespace.equals(ref.path("namespace").asText(routeNamespace))
                && name.equals(ref.path("name").asText()) && (ref.path("sectionName").isMissingNode() || listener.equals(ref.path("sectionName").asText()))
                && (ref.path("port").isMissingNode() || ref.path("port").asInt(-1) == 443);
    }

    private ObjectNode base(GatewayUrlExposureBO row, RouteSnapshot route, boolean attached) {
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", 1);
        ObjectNode value = result.putObject("kubernetesRoute").put("kind", "HTTPRoute").put("namespace", row.getRouteNamespace())
                .put("name", row.getRouteName()).put("uid", route.uid()).put("resourceVersion", route.resourceVersion())
                .put("metadataGeneration", Long.toString(route.metadataGeneration())).put("attached", attached);
        ParentStatus parent = parentStatus(row, route.document());
        if (attached && !"Unknown".equals(parent.accepted()) && !"Unknown".equals(parent.resolvedRefs())) {
            value.put("controllerName", CONTROLLER).put("conditionObservedGeneration", Long.toString(route.metadataGeneration()));
        } else { value.putNull("controllerName"); value.putNull("conditionObservedGeneration"); }
        return result;
    }

    private void writeConditions(ObjectNode json, GatewayUrlExposureBO row, List<Condition> values) {
        var array = json.putArray("conditions");
        for (Condition condition : values) {
            array.addObject().put("type", condition.type()).put("status", condition.status()).put("reason", condition.reason())
                    .put("message", "Current publication evidence evaluated").put("observedGeneration", Long.toString(row.getDesiredGeneration()))
                    .put("lastTransitionTime", clock.instant().toString());
        }
    }
    private ObservationResult result(GatewayUrlExposureBO row, ObjectNode observation, boolean synced, @Nullable String error) {
        try { if (mapper.writeValueAsBytes(observation).length > 65_536) { throw fail("OBSERVATION_TOO_LARGE"); } }
        catch (java.io.IOException invalid) { throw fail("OBSERVATION_INVALID"); }
        return new ObservationResult(row.getVersion(), row.getDesiredGeneration(), row.getRouteUid(),
                synced ? row.getDesiredState().wireValue() : row.getObservedState(), synced ? row.getDesiredGeneration() : row.getObservedGeneration(),
                synced ? "SYNCED" : "PENDING", observation, synced ? null : error);
    }
    private static void dependency(com.fasterxml.jackson.databind.node.ArrayNode values, JsonNode object) {
        if (values.size() >= 32) { throw fail("OBSERVATION_TOO_LARGE"); }
        values.addObject().put("kind", object.path("kind").asText()).put("namespace", object.path("metadata").path("namespace").asText())
                .put("name", object.path("metadata").path("name").asText()).put("uid", object.path("metadata").path("uid").asText())
                .put("resourceVersion", object.path("metadata").path("resourceVersion").asText());
    }
    private static boolean identity(@Nullable JsonNode value, String kind, String namespace, String name, String uid, @Nullable String rv) {
        return value != null && kind.equals(value.path("kind").asText()) && namespace.equals(value.path("metadata").path("namespace").asText())
                && name.equals(value.path("metadata").path("name").asText()) && uid.equals(value.path("metadata").path("uid").asText())
                && token(value.path("metadata").path("resourceVersion").asText(""), 128)
                && (rv == null || rv.equals(value.path("metadata").path("resourceVersion").asText()));
    }
    @Nullable private static JsonNode object(List<JsonNode> values, String kind, String namespace, String name) {
        JsonNode found = null;
        for (JsonNode value : values) {
            if (kind.equals(value.path("kind").asText()) && namespace.equals(value.path("metadata").path("namespace").asText())
                    && name.equals(value.path("metadata").path("name").asText())) {
                if (found != null) { throw fail("DEPENDENCY_DUPLICATED"); } found = value;
            }
        }
        return found;
    }
    @Nullable private static JsonNode unique(JsonNode array, String field, String expected) {
        if (!array.isArray()) { return null; } JsonNode found = null;
        for (JsonNode value : array) { if (expected.equals(value.path(field).asText())) { if (found != null) { return null; } found = value; } }
        return found;
    }
    private static boolean hostMatches(String pattern, String host) {
        return pattern.isEmpty() || pattern.equals(host) || pattern.startsWith("*.") && host.endsWith(pattern.substring(1));
    }
    private static boolean segmentPrefix(String prefix, String value) {
        while (prefix.length() > 1 && prefix.endsWith("/")) { prefix = prefix.substring(0, prefix.length() - 1); }
        return "/".equals(prefix) || value.equals(prefix) || value.startsWith(prefix + "/");
    }
    private static boolean http1(JsonNode value) { return value.isMissingNode() || value.isNull() || "http".equals(value.asText()); }
    private static boolean token(String value, int maximum) { return value != null && !value.isEmpty() && value.length() <= maximum && value.chars().allMatch(c -> c >= 0x21 && c <= 0x7e); }
    private static List<JsonNode> sorted(List<JsonNode> values) { return values.stream().sorted(Comparator.comparing(value -> value.path("metadata").path("namespace").asText() + "/" + value.path("metadata").path("name").asText())).toList(); }
    private String targetDigest(Set<String> values) { return canonical.contentDigest(mapper.valueToTree(values.stream().sorted().toList())); }
    private static Set<String> proofTargets(Set<String> values) {
        if (values == null || values.size() > 4096 || values.stream().anyMatch(value -> !token(value, 256))) { throw fail("DATAPLANE_EVIDENCE_INVALID"); }
        return Set.copyOf(values);
    }
    private static CheckFailure fail(String code) { return new CheckFailure(code); }
    private static String code(RuntimeException failure, String fallback) { return failure instanceof CheckFailure known ? known.getMessage() : fallback; }
    private static final class CheckFailure extends IllegalArgumentException { private CheckFailure(String code) { super(code); } }
    private record Condition(String type, String status, String reason) { }
    private record ParentStatus(String accepted, String resolvedRefs) { }

    public record PreAttachResult(boolean ready, @Nullable String lastErrorCode) { }
    public record ObservationResult(long expectedVersion, long evaluatedGeneration, @Nullable String verifiedUid,
            String observedState, long observedGeneration, String reconcileStatus, JsonNode observation, @Nullable String lastErrorCode) {
        public ObservationResult { observation = observation.deepCopy(); }
        @Override public JsonNode observation() { return observation.deepCopy(); }
        public boolean synced() { return "SYNCED".equals(reconcileStatus); }
    }
    @FunctionalInterface public interface SnapshotProvider { KubernetesSnapshot collect(GatewayUrlExposureBO row, @Nullable PublicationBinding binding, RouteSnapshot route); }
    public record KubernetesSnapshot(RouteSnapshot route, @Nullable JsonNode gateway, List<JsonNode> services, List<JsonNode> referenceGrants,
            Map<ServiceKey, ListSnapshot> endpointSlices, Map<String, ListSnapshot> routeLists, boolean routeInventoryComplete, Instant observedAt) {
        public KubernetesSnapshot {
            Objects.requireNonNull(route, "route"); gateway = gateway == null ? null : gateway.deepCopy();
            services = services.stream().<JsonNode>map(JsonNode::deepCopy).toList(); referenceGrants = referenceGrants.stream().<JsonNode>map(JsonNode::deepCopy).toList();
            endpointSlices = Map.copyOf(endpointSlices); routeLists = Map.copyOf(routeLists); Objects.requireNonNull(observedAt, "observedAt");
        }
        @Override public JsonNode gateway() { return gateway == null ? null : gateway.deepCopy(); }
        @Override public List<JsonNode> services() { return services.stream().<JsonNode>map(JsonNode::deepCopy).toList(); }
        @Override public List<JsonNode> referenceGrants() { return referenceGrants.stream().<JsonNode>map(JsonNode::deepCopy).toList(); }
        public static KubernetesSnapshot minimal(RouteSnapshot route, Instant observedAt) { return new KubernetesSnapshot(route, null, List.of(), List.of(), Map.of(), Map.of(), false, observedAt); }
    }
    public enum Stage { PRE_ATTACH, OBSERVE }
    public record ProofRequest(Stage stage, long ownerTenantId, long exposureId, long desiredGeneration, GatewayUrlExposureStateEnum desiredState,
            String contentSha256, String hostname, String httpMethod, String pathMatchType, String pathValue,
            PublicationBinding binding, String routeUid, String routeResourceVersion, long routeMetadataGeneration,
            String routeInventoryDigest, String requestDigest, Instant observedAt) { }
    public interface ProofProvider {
        Optional<DependencyAttestation> verifyDependencies(ProofRequest request);
        Optional<DataplaneAttestation> verifyDataplane(ProofRequest request);
        static ProofProvider unavailable() { return new ProofProvider() {
            @Override public Optional<DependencyAttestation> verifyDependencies(ProofRequest request) { return Optional.empty(); }
            @Override public Optional<DataplaneAttestation> verifyDataplane(ProofRequest request) { return Optional.empty(); }
        }; }
    }
    public record DependencyAttestation(String requestDigest, Instant verifiedAt, Instant validUntil, String evidenceRef,
            boolean tlsTrustVerified, boolean authorizationVerified, boolean networkAdmissionVerified, boolean publicationDomainComplete) { }
    public record DataplaneAttestation(String requestDigest, Instant verifiedAt, Instant validUntil, String evidenceRef, String ruleSetDigest,
            Set<String> targetIds, Set<String> configurationVerified, Set<String> requestVerified, Set<String> isolated,
            boolean allEntryPathsVerified, boolean noAlternateRouteVerified) {
        public DataplaneAttestation {
            targetIds = proofTargets(targetIds); configurationVerified = proofTargets(configurationVerified);
            requestVerified = proofTargets(requestVerified); isolated = proofTargets(isolated);
        }
    }
}
