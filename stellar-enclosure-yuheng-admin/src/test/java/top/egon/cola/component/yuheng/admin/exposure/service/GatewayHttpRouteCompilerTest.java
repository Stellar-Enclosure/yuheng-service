package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.AuthorizationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BackendReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.ExposureInput;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GatewayReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GrantReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PolicyReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.ResourceReference;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuntimePolicy;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuntimeRoute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayHttpRouteCompilerTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final GatewayHttpRouteCompiler compiler = new GatewayHttpRouteCompiler(json);

    @Test void nativeCompilerAdvertisesOnlyTheApprovedFrozenNorthProfile() throws Exception {
        Class<?> type = Class.forName("top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler");
        assertThat(type.getField("SCHEMA_VERSION").get(null)).isEqualTo("cilium-exposure-v2");
        assertThat(type.getField("CAPABILITY_PROFILE_ID").get(null)).isEqualTo("cilium-1.20.5-gapi-1.6.3-edge-h1-gamma-v2");
    }

    @Test void emitsOneInertHttpRouteAndSeparateExactParentWithWeightedH1ServiceRefs() {
        var compiled = compiler.compile(input(), binding());
        JsonNode object = compiled.routeObject();
        assertThat(object.path("apiVersion").asText()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(object.path("kind").asText()).isEqualTo("HTTPRoute");
        assertThat(object.at("/metadata/name").asText()).isEqualTo("yuheng-exposure-810001");
        assertThat(object.at("/metadata/namespace").asText()).isEqualTo("yuheng-routes");
        assertThat(object.at("/spec/parentRefs").isArray()).isTrue();
        assertThat(object.at("/spec/parentRefs").size()).isZero();
        assertThat(object.at("/spec/rules").size()).isEqualTo(1);
        assertThat(object.at("/spec/rules/0/matches/0/path/type").asText()).isEqualTo("Exact");
        assertThat(object.at("/spec/rules/0/matches/0/method").asText()).isEqualTo("GET");
        JsonNode backends = object.at("/spec/rules/0/backendRefs");
        assertThat(backends.size()).isEqualTo(2);
        assertThat(backends.get(0).path("name").asText()).isEqualTo("orders-public");
        assertThat(backends.get(0).path("port").asInt()).isEqualTo(80);
        assertThat(backends.get(0).path("weight").asInt()).isEqualTo(95);
        assertThat(backends.get(1).path("weight").asInt()).isEqualTo(5);
        assertThat(compiled.binding().backends()).allSatisfy(backend -> assertThat(backend.targetPort()).isEqualTo(8080));
        assertThat(compiled.approvedParent().path("namespace").asText()).isEqualTo("edge-system");
        assertThat(compiled.approvedParent().path("name").asText()).isEqualTo("public-apps");
        assertThat(compiled.approvedParent().path("sectionName").asText()).isEqualTo("https-api");
        assertThat(compiled.snapshotEntry().path("expectedDesiredGeneration").asText()).isEqualTo("1");
        assertThat(compiled.snapshotEntry().path("requestedState").asText()).isEqualTo("OPEN");
    }

    @Test void fixedRetryHeaderRemovalCannotBeOverriddenAndNoTlsGrpcMirrorRetryFieldsAreGenerated() {
        var result = compiler.compile(input(), binding());
        JsonNode rule = result.routeObject().at("/spec/rules/0");
        assertThat(rule.path("filters").size()).isEqualTo(1);
        var actual = new ArrayList<String>();
        rule.at("/filters/0/requestHeaderModifier/remove").forEach(value -> actual.add(value.asText()));
        assertThat(actual).containsExactlyElementsOf(GatewayHttpRouteCompiler.REMOVED_RETRY_HEADERS);
        JsonNode object = result.routeObject();
        assertThat(fieldNames(object)).containsExactlyInAnyOrder("apiVersion", "kind", "metadata", "spec");
        assertThat(object.path("kind").asText()).isEqualTo("HTTPRoute");
        assertThat(fieldNames(object.path("metadata"))).containsExactlyInAnyOrder("namespace", "name", "labels");
        assertThat(fieldNames(object.path("spec"))).containsExactlyInAnyOrder("parentRefs", "hostnames", "rules");
        assertThat(fieldNames(rule)).containsExactlyInAnyOrder("matches", "filters", "backendRefs");
        assertThat(fieldNames(rule.at("/filters/0"))).containsExactlyInAnyOrder("type", "requestHeaderModifier");
        assertThat(rule.at("/filters/0/type").asText()).isEqualTo("RequestHeaderModifier");
        assertThat(fieldNames(rule.at("/filters/0/requestHeaderModifier"))).containsExactly("remove");
        rule.path("backendRefs").forEach(backend -> {
            assertThat(fieldNames(backend)).containsExactlyInAnyOrder("group", "kind", "namespace", "name", "port", "weight");
            assertThat(backend.path("group").asText()).isEmpty();
            assertThat(backend.path("kind").asText()).isEqualTo("Service");
        });
        assertThat(result.routeObject().at("/metadata/labels").size()).isEqualTo(3);
    }

    @Test void rawKubernetesRedirectRewriteMirrorRetryAndUnknownDraftFieldsFailClosed() {
        for (String field : List.of("kind", "metadata", "spec", "parentRefs", "backendRefs", "filters",
                "requestRedirect", "urlRewrite", "mirror", "retry")) {
            Map<String, Object> draft = new LinkedHashMap<>(draft(route("/orders")));
            draft.put(field, Map.of("unsafe", true));
            rejected(() -> compiler.compile(input(route("/orders"), draft, List.of(), List.of()), binding()));
        }
    }

    @Test void legacyNonZeroPriorityAndTransportSemanticsAreNotSilentlyDiscarded() {
        for (Map<String, Object> transport : List.of(Map.<String, Object>of("retryEnabled", true),
                Map.<String, Object>of("connectTimeoutMs", 1000), Map.<String, Object>of("profile", "OPENAI_HTTP"))) {
            var draft = new LinkedHashMap<>(draft(route("/orders")));
            draft.put("transportPolicy", transport);
            rejected(() -> compiler.compile(input(route("/orders"), draft, List.of(), List.of()), binding()));
        }
        var draft = new LinkedHashMap<>(draft(route("/orders")));
        draft.put("transportPolicy", Map.of("retryEnabled", false, "bodyLogEnabled", false));
        compiler.compile(input(route("/orders"), draft, List.of(), List.of()), binding());
        draft.put("priority", 1);
        rejected(() -> compiler.compile(input(route("/orders"), draft, List.of(), List.of()), binding()));
    }

    @Test void canonicalYamlProvenanceAndUnsetLegacyTransportDoNotChangeRoutingContent() {
        var ordinary = input();
        Map<String, Object> draft = new LinkedHashMap<>(ordinary.canonicalDraft());
        draft.put("yuhengRouteSource", "YAML");
        Map<String, Object> unset = new LinkedHashMap<>();
        unset.put("profile", null); unset.put("connectTimeoutMs", null); unset.put("retryEnabled", false);
        draft.put("transportPolicy", unset);
        var imported = compiler.compile(input(ordinary.route(), draft, List.of(), List.of()), binding());
        assertThat(imported.contentSha256()).isEqualTo(compiler.compile(ordinary, binding()).contentSha256());
        assertThat(imported.routeObject().toString()).doesNotContain("yuhengRouteSource", "connectTimeoutMs");
        draft.put("yuhengRouteSource", "UNTRUSTED");
        rejected(() -> compiler.compile(input(ordinary.route(), draft, List.of(), List.of()), binding()));
    }

    @Test void rpcPrivateDisabledAndForeignOwnerOrOperationCannotUseApprovedPhysicalBinding() {
        var valid = input();
        rejected(() -> compiler.compile(new ExposureInput(7, 800001, "820001", 810001, 1, valid.route(),
                GatewayProtocol.RPC, true, valid.canonicalDraft(), List.of(), List.of()), binding()));
        rejected(() -> compiler.compile(new ExposureInput(7, 800001, "820001", 810001, 1, valid.route(),
                GatewayProtocol.HTTP, false, valid.canonicalDraft(), List.of(), List.of()), binding()));
        rejected(() -> compiler.compile(valid, mutate(binding(), PublicationBinding.class, n -> n.put("ownerTenantId", 8))));
        rejected(() -> compiler.compile(valid, mutate(binding(), PublicationBinding.class, n -> n.put("operationId", "other-op"))));
        var disabled = new GatewayRuntimeRoute("orders-read", "operation-1", "api.example.com", "GET", "/orders", Set.of(AccessZone.PUBLIC), 0, false);
        rejected(() -> compiler.compile(input(disabled, draft(disabled), List.of(), List.of()), binding()));
        var internal = new GatewayRuntimeRoute("orders-read", "operation-1", "api.example.com", "GET", "/orders", Set.of(AccessZone.INTERNAL), 0, true);
        rejected(() -> compiler.compile(input(internal, draft(internal), List.of(), List.of()), binding()));
    }

    @Test void onlyAnExplicitApprovedFinitePrefixCanUsePathPrefixAndExactKeepsTrailingSlash() {
        var draft = new LinkedHashMap<>(draft(route("/orders"))); draft.put("pathMatchType", "PATH_PREFIX");
        rejected(() -> compiler.compile(input(route("/orders"), draft, List.of(), List.of()), binding()));
        var approved = mutate(binding(), PublicationBinding.class, n -> n.putArray("approvedPathPrefixes").add("/orders"));
        var result = compiler.compile(input(route("/orders"), draft, List.of(), List.of()), approved);
        assertThat(result.routeObject().at("/spec/rules/0/matches/0/path/type").asText()).isEqualTo("PathPrefix");
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class, n -> n.putArray("approvedPathPrefixes").add("/"))));
        assertThat(compiler.compile(input(route("/orders/"), draft(route("/orders/")), List.of(), List.of()), binding()).pathValue()).isEqualTo("/orders/");
        var regex = new LinkedHashMap<>(draft(route("/orders"))); regex.put("pathMatchType", "REGULAR_EXPRESSION");
        rejected(() -> compiler.compile(input(route("/orders"), regex, List.of(), List.of()), binding()));
    }

    @Test void reservedEncodingIsPreservedUnreservedEncodingNormalizedAndAmbiguousPathsRejected() {
        var route = route("/ord%65rs/%2fpart");
        assertThat(compiler.compile(input(route, draft(route), List.of(), List.of()), binding()).pathValue()).isEqualTo("/orders/%2Fpart");
        for (String path : List.of("/../orders", "/%2e%2e/orders", "/a//b", "/orders?owner=7", "/orders#fragment",
                "/orders/{id}", "/orders/**", "/%GG", "//evil", "/a\\b", "/%0d%0a")) {
            var invalid = route(path);
            rejected(() -> compiler.compile(input(invalid, draft(invalid), List.of(), List.of()), binding()));
        }
    }

    @Test void unapprovedHostOrMethodAndFalseGatewayCapabilityCannotCompile() {
        var otherHost = new GatewayRuntimeRoute("orders-read", "operation-1", "other.example.com", "GET", "/orders", Set.of(AccessZone.PUBLIC), 0, true);
        rejected(() -> compiler.compile(input(otherHost, draft(otherHost), List.of(), List.of()), binding()));
        var post = new GatewayRuntimeRoute("orders-read", "operation-1", "api.example.com", "POST", "/orders", Set.of(AccessZone.PUBLIC), 0, true);
        rejected(() -> compiler.compile(input(post, draft(post), List.of(), List.of()), binding()));
        for (String field : List.of("https443Verified", "forwardedHeadersVerified")) {
            rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                    n -> ((ObjectNode) n.path("gateway")).put(field, false))));
        }
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("publicationDomain")).put("complete", false))));
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("gateway")).put("listenerName", "other-listener"))));
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> n.put("routeNamespace", "apps-prod"))));
    }

    @Test void explicitlyApprovedAnyMethodOmitsMethodWithoutInventingAnotherRoute() {
        var route = new GatewayRuntimeRoute("orders-read", "operation-1", "api.example.com", "*", "/orders", Set.of(AccessZone.PUBLIC), 0, true);
        var binding = mutate(binding(), PublicationBinding.class, n -> n.putArray("approvedMethods").add("*"));
        assertThat(compiler.compile(input(route, draft(route), List.of(), List.of()), binding)
                .routeObject().at("/spec/rules/0/matches/0").has("method")).isFalse();
    }

    @Test void everyBackendMustHaveExactPortsH1UniqueUidAndDisjointReadyPodIncarnations() {
        for (String field : List.of("port", "targetPort")) {
            rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                    n -> ((ObjectNode) n.path("backends").get(0)).put(field, 443))));
        }
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("backends").get(0)).put("upstreamProtocol", "HTTPS"))));
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("backends").get(0)).put("networkAdmissionVerified", false))));
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("backends").get(1)).put("uid", n.path("backends").get(0).path("uid").asText()))));
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("backends").get(1)).putArray("readyEndpointIds").add("pod-stable"))));
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("backends").get(1)).putArray("readyEndpointIds"))));
    }

    @Test void crossNamespaceGrantIsExactNamedOwnerAuthorizationAndWeightsHavePositiveBoundedTotal() {
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("backends").get(0)).putNull("grant"))));
        for (String field : List.of("fromNamespace", "namespace", "serviceName")) {
            rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                    n -> ((ObjectNode) n.path("backends").get(0).path("grant")).put(field, "foreign"))));
        }
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class, n -> n.path("backends").forEach(b -> ((ObjectNode) b).put("weight", 0)))));
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.path("backends").get(0)).put("weight", -1))));
        var paused = mutate(binding(), PublicationBinding.class, n -> ((ObjectNode) n.path("backends").get(1)).put("weight", 0));
        compiler.validateBinding(paused);
    }

    @Test void onlyPinnedApplicationAuthenticationPoliciesAreRetainedAndRetryCannotBecomeApplicationFallback() {
        Map<String, Object> config = Map.of("profile", GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE, "resource", "https://api.example.com/orders");
        var policy = new GatewayRuntimePolicy("auth-1", "AUTHENTICATION", "OPERATION", config);
        var reference = new PolicyReference("auth-1", "AUTHENTICATION", "OPERATION", compiler.contentDigest(json.valueToTree(config)), "APPLICATION");
        var binding = withPolicies(List.of(reference));
        var result = compiler.compile(input(route("/orders"), draft(route("/orders")), List.of(policy), List.of()), binding);
        assertThat(result.canonicalContent().path("applicationPolicies").get(0).path("owner").asText()).isEqualTo("APPLICATION");
        rejected(() -> compiler.compile(input(), binding));
        rejected(() -> compiler.compile(input(route("/orders"), draft(route("/orders")), List.of(policy), List.of()), binding()));
        var changed = new GatewayRuntimePolicy("auth-1", "AUTHENTICATION", "OPERATION", Map.of("profile", "different"));
        rejected(() -> compiler.compile(input(route("/orders"), draft(route("/orders")), List.of(changed), List.of()), binding));
        rejected(() -> compiler.validateBinding(withPolicies(List.of(new PolicyReference("retry-1", "RETRY", "ROUTE", "a".repeat(64), "APPLICATION")))));
    }

    @Test void contentHashIsStableAcrossRuntimeIdsGenerationBackendOrderAndSetIteration() {
        var first = compiler.compile(input(), binding());
        var next = new ExposureInput(7, 800001, "820002", 810002, 9, input().route(), GatewayProtocol.HTTP,
                true, input().canonicalDraft(), List.of(), List.of());
        var reordered = binding(List.of(backend("orders-public-canary", 5, "pod-canary"), backend("orders-public", 95, "pod-stable")));
        assertThat(compiler.compile(next, reordered).contentSha256()).isEqualTo(first.contentSha256());
        assertThat(compiler.compile(next, reordered).snapshotEntry().path("expectedDesiredGeneration").asText()).isEqualTo("9");
        var changedWeight = binding(List.of(backend("orders-public", 100, "pod-stable"), backend("orders-public-canary", 0, "pod-canary")));
        assertThat(compiler.compile(input(), changedWeight).contentSha256()).isNotEqualTo(first.contentSha256());
        var changedUid = mutate(binding(), PublicationBinding.class, n -> ((ObjectNode) n.path("gateway")).put("uid", "new-gateway-uid"));
        assertThat(compiler.compile(input(), changedUid).contentSha256()).isNotEqualTo(first.contentSha256());
        var changedGrant = mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.at("/backends/0/grant")).put("resourceVersion", "5"));
        assertThat(compiler.compile(input(), changedGrant).contentSha256()).isNotEqualTo(first.contentSha256());
        assertThat(compiler.compile(input(), changedGrant).dependencies().at("/0/service/grant/resourceVersion").asText())
                .isEqualTo("5");
        rejected(() -> compiler.validateBinding(mutate(binding(), PublicationBinding.class,
                n -> ((ObjectNode) n.at("/backends/0/grant")).remove("resourceVersion"))));
        assertThat(first.contentSha256()).matches("[0-9a-f]{64}");
    }

    @Test void logicalResourcesRequireFixedVersionsDigestsAndUniqueKindIdentity() {
        var refs = List.of(new ResourceReference("AGENT", "agent-1", "2", "b".repeat(64)));
        var compiled = compiler.compile(input(route("/orders"), draft(route("/orders")), List.of(), refs), binding());
        assertThat(compiled.snapshotEntry().path("resourceRefs").get(0).path("version").asText()).isEqualTo("2");
        rejected(() -> compiler.compile(input(route("/orders"), draft(route("/orders")), List.of(),
                List.of(new ResourceReference("AGENT", "agent-1", "active", "b".repeat(64)))), binding()));
        rejected(() -> compiler.compile(input(route("/orders"), draft(route("/orders")), List.of(),
                List.of(refs.getFirst(), refs.getFirst())), binding()));
    }

    @Test void inputSnapshotsAndEveryJsonOutputAccessorAreDefensiveAndPreviewUsesIdenticalChecks() {
        var draft = new LinkedHashMap<>(draft(route("/orders")));
        var input = input(route("/orders"), draft, List.of(), List.of());
        draft.put("spec", Map.of("unsafe", true));
        var compiled = compiler.compile(input, binding());
        ((ObjectNode) compiled.routeObject().path("spec")).putArray("parentRefs").addObject().put("name", "foreign");
        ((ObjectNode) compiled.snapshotEntry()).put("requestedState", "CLOSED");
        assertThat(compiled.routeObject().at("/spec/parentRefs").size()).isZero();
        assertThat(compiled.snapshotEntry().path("requestedState").asText()).isEqualTo("OPEN");
        compiler.validate(input.route(), input.operationProtocol(), true, input.canonicalDraft(), List.of(), List.of(), binding());
        var bad = mutate(binding(), PublicationBinding.class, n -> ((ObjectNode) n.path("backends").get(0)).put("targetPort", 9090));
        rejected(() -> compiler.validate(input.route(), input.operationProtocol(), true, input.canonicalDraft(), List.of(), List.of(), bad));
    }

    private ExposureInput input() { return input(route("/orders"), draft(route("/orders")), List.of(), List.of()); }
    private ExposureInput input(GatewayRuntimeRoute route, Map<String, Object> draft, List<GatewayRuntimePolicy> policies, List<ResourceReference> refs) {
        return new ExposureInput(7, 800001, "820001", 810001, 1, route, GatewayProtocol.HTTP, true, draft, policies, refs);
    }
    private GatewayRuntimeRoute route(String path) {
        return new GatewayRuntimeRoute("orders-read", "operation-1", "api.example.com", "GET", path, Set.of(AccessZone.PUBLIC), 0, true);
    }
    private Map<String, Object> draft(GatewayRuntimeRoute route) {
        return Map.of("host", route.host(), "httpMethod", route.httpMethod(), "pathPattern", route.pathPattern(),
                "accessZones", route.accessZones().stream().map(Enum::name).toList(), "priority", route.priority());
    }
    private PublicationBinding binding() { return binding(List.of(backend("orders-public", 95, "pod-stable"), backend("orders-public-canary", 5, "pod-canary"))); }
    private PublicationBinding binding(List<BackendReference> backends) {
        return new PublicationBinding(7, 800001, "orders-read", "operation-1", "prod", "logical-prod", "binding-1", 1,
                new PublicationDomainVO("cluster-main", "public-https-main", "topology-1", List.of(new BindingVO("edge-system", "public-apps", "https-api")), true),
                new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true), "yuheng-routes",
                Set.of("api.example.com"), Set.of("GET"), Set.of(), backends,
                new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE, "https://api.example.com/orders", "1", "a".repeat(64), true, List.of()));
    }
    private BackendReference backend(String name, int weight, String endpoint) {
        return new BackendReference("apps-prod", name, name + "-uid", "11", 80, 8080, weight, "HTTP_1_1",
                new GrantReference("apps-prod", "allow-" + name, "grant-" + name + "-uid", "4", "yuheng-routes", name),
                "c".repeat(64), Set.of(endpoint), true);
    }
    private PublicationBinding withPolicies(List<PolicyReference> policies) {
        var b = binding();
        return new PublicationBinding(b.ownerTenantId(), b.gatewayGroupId(), b.routeId(), b.operationId(), b.environment(),
                b.logicalNamespace(), b.bindingId(), b.bindingVersion(), b.publicationDomain(), b.gateway(), b.routeNamespace(),
                b.approvedHostnames(), b.approvedMethods(), b.approvedPathPrefixes(), b.backends(),
                new AuthorizationBinding(b.authorization().profile(), b.authorization().resourceUri(), b.authorization().version(),
                        b.authorization().contentSha256(), b.authorization().resourceServerVerified(), policies));
    }
    private <T> T mutate(T original, Class<T> type, Consumer<ObjectNode> change) {
        ObjectNode node = json.valueToTree(original); change.accept(node); return json.convertValue(node, type);
    }
    private List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>(); object.fieldNames().forEachRemaining(names::add); return names;
    }
    private void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("YUHENG_EXPOSURE_COMPILE_INVALID:");
    }
}
