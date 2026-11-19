package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.AccessScope;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ControlFence;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.Failure;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.GatewayKey;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.GrantKey;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.Kind;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.RouteTarget;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient.ServiceKey;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real bounded HTTPS and production TLS constructor; only the synthetic API server is a fixture. */
class GatewayKubernetesClientTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final RouteTarget TARGET = new RouteTarget(7, 810001, "yuheng-routes");
    private static final GatewayKey GATEWAY = new GatewayKey("edge-system", "public-apps", "https-api");
    private static final ServiceKey SERVICE = new ServiceKey("apps-prod", "orders-public");
    private static final GrantKey GRANT = new GrantKey("apps-prod", "allow-orders-public");
    private static final AccessScope SCOPE = new AccessScope("cluster-main", "yuheng-routes", Set.of(GATEWAY), Set.of(SERVICE), Set.of(GRANT));
    private static Fixture api;

    @BeforeAll static void start(@TempDir Path directory) throws Exception { api = new Fixture(directory); }
    @AfterAll static void stop() { if (api != null) api.close(); }
    @BeforeEach void reset() throws Exception { api.reset(); }

    @Test void publisherOwnsANarrowKubernetesInfrastructureClient() throws Exception {
        Class<?> client = Class.forName("top.egon.cola.component.yuheng.admin.exposure.service.GatewayKubernetesClient");
        assertThat(AutoCloseable.class).isAssignableFrom(client);
        assertThat(Class.forName(client.getName() + "$AccessScope").isRecord()).isTrue();
        assertThat(List.of(client.getMethods()).stream().map(java.lang.reflect.Method::getName))
                .doesNotContain("delete", "getSecret", "getGatewayClass", "exchange");
    }

    @Test void actualHttpsCreateIsInertAndReadsRawProjectedTokenAgainForEachRequest() throws Exception {
        api.current = null;
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            var created = client.createInert(TARGET, new ControlFence(0, 1, GatewayUrlExposureStateEnum.OPEN), compiled());
            assertThat(created.uid()).isEqualTo("route-uid");
            assertThat(created.controlGeneration()).isEqualTo(1);
            assertThat(created.document().at("/spec/parentRefs").size()).isZero();
            assertThat(created.document().at("/metadata/annotations/stellar.io~1desired-state").asText()).isEqualTo("OPEN");
            Files.writeString(api.token, "api-rotated");
            assertThat(client.getRoute(TARGET)).isPresent();
            assertThat(api.requests).extracting(Request::authorization).containsExactly("Bearer api-first", "Bearer api-rotated");
            assertThat(api.requests.getFirst().method()).isEqualTo("POST");
            assertThat(api.requests.getFirst().path()).isEqualTo("/apis/gateway.networking.k8s.io/v1/namespaces/yuheng-routes/httproutes");
            assertThat(api.requests.getFirst().contentType()).isEqualTo("application/json");
            assertThat(api.requests.getFirst().body().at("/metadata/annotations/stellar.io~1exposure-id").asText()).isEqualTo("810001");
            Files.writeString(api.token, "api-rotated\n");
            failure(() -> client.getRoute(TARGET), Kind.UNAVAILABLE);
            assertThat(api.requests).hasSize(2);
        }
    }

    @Test void sendsJsonbCanonicalWholeNumbersAsIntegralKubernetesFieldsAndRejectsFractions() {
        api.current = null;
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            ObjectNode frozen = compiled();
            var backend = (ObjectNode) frozen.at("/spec/rules/0/backendRefs/0");
            backend.put("port", new java.math.BigDecimal("8E+1"));
            backend.put("weight", new java.math.BigDecimal("1E+2"));
            var fence = new ControlFence(0, 1, GatewayUrlExposureStateEnum.OPEN);
            client.createInert(TARGET, fence, frozen);
            var sent = api.requests.getFirst().body().at("/spec/rules/0/backendRefs/0");
            assertThat(sent.path("port").isIntegralNumber()).isTrue();
            assertThat(sent.path("port").intValue()).isEqualTo(80);
            assertThat(sent.path("weight").isIntegralNumber()).isTrue();
            assertThat(sent.path("weight").intValue()).isEqualTo(100);
            backend.put("port", new java.math.BigDecimal("80.5"));
            assertThatThrownBy(() -> client.createInert(TARGET, fence, frozen)).isInstanceOf(IllegalArgumentException.class);
            assertThat(api.requests).hasSize(1);
        }
    }

    @Test void closedFenceAdvancesResourceVersionEvenWithAlreadyEmptyParentsAndNeedsNoArtifact() {
        api.current.withObject("/spec").putArray("parentRefs");
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            var before = client.getRoute(TARGET).orElseThrow();
            var closed = client.patchDesired(before, new ControlFence(2, 2, GatewayUrlExposureStateEnum.CLOSED), null, null);
            assertThat(closed.resourceVersion()).isNotEqualTo(before.resourceVersion());
            assertThat(closed.controlGeneration()).isEqualTo(2);
            assertThat(closed.controlState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
            assertThat(closed.document().at("/spec/parentRefs").size()).isZero();
            Request patch = api.requests.get(1);
            assertThat(patch.method()).isEqualTo("PATCH");
            assertThat(patch.contentType()).isEqualTo("application/json-patch+json");
            assertThat(patch.body().get(0).path("path").asText()).isEqualTo("/metadata/uid");
            assertThat(patch.body().get(1).path("path").asText()).isEqualTo("/metadata/resourceVersion");
            assertThat(patch.body().toString()).contains("/metadata/annotations/stellar.io~1desired-generation");
            int count = api.requests.size();
            failure(() -> client.patchDesired(before, new ControlFence(1, 1, GatewayUrlExposureStateEnum.OPEN), compiled(), parent()), Kind.CONFLICT);
            assertThat(api.requests).hasSize(count + 1);
            var repeat = client.patchDesired(closed, new ControlFence(3, 2, GatewayUrlExposureStateEnum.CLOSED), null, null);
            assertThat(repeat.controlState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
            assertThat(repeat.resourceVersion()).isEqualTo(closed.resourceVersion());
        }
    }

    @Test void openAttachIsExactAndTerminalOrFutureGenerationCannotRegressLocally() {
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            var before = client.getRoute(TARGET).orElseThrow();
            var opened = client.patchDesired(before, new ControlFence(1, 1, GatewayUrlExposureStateEnum.OPEN), compiled(), parent());
            assertThat(opened.document().at("/spec/parentRefs/0")).isEqualTo(parent());
            var terminal = client.patchDesired(opened, new ControlFence(2, 2, GatewayUrlExposureStateEnum.WITHDRAWN), null, null);
            int count = api.requests.size();
            failure(() -> client.patchDesired(terminal, new ControlFence(4, 3, GatewayUrlExposureStateEnum.OPEN), compiled(), parent()), Kind.CONFLICT);
            failure(() -> client.patchDesired(terminal, new ControlFence(4, 1, GatewayUrlExposureStateEnum.CLOSED), null, null), Kind.CONFLICT);
            assertThat(api.requests).hasSize(count);
        }
    }

    @Test void uidOrRvTestFailureAnd422DoNotRetryOrAdoptAnUnexpectedReplacement() {
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            var observed = client.getRoute(TARGET).orElseThrow();
            api.current.withObject("/metadata").put("uid", "replacement-uid");
            api.patchConflictCode = 422;
            failure(() -> client.patchDesired(observed, new ControlFence(2, 2, GatewayUrlExposureStateEnum.CLOSED), null, null), Kind.CONFLICT);
            assertThat(api.requests.stream().filter(request -> request.method().equals("PATCH"))).hasSize(1);
            assertThat(api.current.at("/metadata/uid").asText()).isEqualTo("replacement-uid");
            assertThat(api.current.at("/metadata/annotations/stellar.io~1desired-generation").asText()).isEqualTo("1");
        }
    }

    @Test void unknownCreateCommitRequiresExplicitReadAndNeverBlindlyPostsAgain() {
        api.current = null;
        api.script = request -> {
            Reply committed = api.normal(request);
            return "POST".equals(request.method()) ? reply(503, status(503, "ServiceUnavailable", request)) : committed;
        };
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            failure(() -> client.createInert(TARGET, new ControlFence(0, 1, GatewayUrlExposureStateEnum.OPEN), compiled()), Kind.UNAVAILABLE);
            assertThat(client.getRoute(TARGET).orElseThrow().uid()).isEqualTo("route-uid");
            assertThat(api.requests.stream().filter(request -> "POST".equals(request.method()))).hasSize(1);
        }
    }

    @Test void fixedScopeRejectsForeignNamesListenersBackendReferencesAndArbitraryMetadataBeforeNetwork() {
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            assertThatThrownBy(() -> client.getService(new ServiceKey("foreign", "orders-public"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.getRoute(new RouteTarget(7, 810001, "foreign"))).isInstanceOf(IllegalArgumentException.class);
            ObjectNode bad = compiled(); bad.withObject("/metadata").put("resourceVersion", "borrowed");
            assertThatThrownBy(() -> client.createInert(TARGET, new ControlFence(0, 1, GatewayUrlExposureStateEnum.OPEN), bad)).isInstanceOf(IllegalArgumentException.class);
            ObjectNode foreignBackend = compiled();
            ((ObjectNode) foreignBackend.at("/spec/rules/0/backendRefs/0")).put("name", "unapproved");
            assertThatThrownBy(() -> client.createInert(TARGET, new ControlFence(0, 1, GatewayUrlExposureStateEnum.OPEN), foreignBackend)).isInstanceOf(IllegalArgumentException.class);
            assertThat(api.requests).isEmpty();
            var observed = client.getRoute(TARGET).orElseThrow();
            ObjectNode foreignListener = parent().put("sectionName", "other");
            assertThatThrownBy(() -> client.patchDesired(observed, new ControlFence(1, 1, GatewayUrlExposureStateEnum.OPEN), compiled(), foreignListener)).isInstanceOf(IllegalArgumentException.class);
            assertThat(api.requests).hasSize(1);
        }
    }

    @Test void onlyProvenMatchingKubernetesNotFoundBecomesAbsenceAndErrorsStaySanitized() {
        api.current = null;
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            assertThat(client.getRoute(TARGET)).isEmpty();
            for (String field : List.of("name", "group", "kind")) {
                api.script = request -> {
                    ObjectNode foreign = status(404, "NotFound", request);
                    foreign.withObject("/details").put(field, "foreign");
                    return reply(404, foreign);
                };
                failure(() -> client.getRoute(TARGET), Kind.INVALID_RESPONSE);
            }
            api.script = request -> new Reply(404, "not-json-private-details".getBytes(StandardCharsets.UTF_8), Map.of());
            failure(() -> client.getRoute(TARGET), Kind.INVALID_RESPONSE);
            for (var item : Map.of(401, Kind.UNAUTHENTICATED, 403, Kind.FORBIDDEN, 409, Kind.CONFLICT,
                    410, Kind.GONE, 429, Kind.THROTTLED, 503, Kind.UNAVAILABLE).entrySet()) {
                api.script = request -> new Reply(item.getKey(), reply(item.getKey(), status(item.getKey(), "FailureReason", request)).body(), Map.of("Retry-After", "2"));
                assertThatThrownBy(() -> client.getRoute(TARGET)).isInstanceOfSatisfying(Failure.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(item.getValue());
                    assertThat(failure.getMessage()).doesNotContain("private-details", "api-first");
                    assertThat(failure.getCause()).isNull();
                    if (item.getKey() == 429) assertThat(failure.retryAfter()).contains(Duration.ofSeconds(2));
                });
            }
        }
    }

    @Test void gatewayServiceGrantPathsAreReadOnlyAndReferenceGrantUsesTheApprovedV1Api() {
        api.script = request -> {
            String kind = request.path().contains("/gateways/") ? "Gateway"
                    : request.path().contains("/services/") ? "Service" : "ReferenceGrant";
            String version = kind.equals("Service") ? "v1" : "gateway.networking.k8s.io/v1";
            String[] parts = request.path().split("/");
            return reply(200, resource(version, kind, parts[parts.length - 3], parts[parts.length - 1], kind + "-uid", "20"));
        };
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            client.getGateway(GATEWAY); client.getService(SERVICE); client.getReferenceGrant(GRANT);
            assertThat(api.requests).extracting(Request::method).containsExactly("GET", "GET", "GET");
            assertThat(api.requests.get(2).path()).isEqualTo("/apis/gateway.networking.k8s.io/v1/namespaces/apps-prod/referencegrants/allow-orders-public");
            assertThat(client.scope().clusterId()).isEqualTo("cluster-main");
        }
    }

    @Test void completePagedListsUseOpaqueContinuationAndConsistentResourceVersion() {
        api.script = request -> {
            ObjectNode list = JSON.createObjectNode().put("apiVersion", "gateway.networking.k8s.io/v1").put("kind", "HTTPRouteList");
            list.putObject("metadata").put("resourceVersion", "list-rv").put("continue", request.query().contains("continue=") ? "" : "opaque/+&");
            ObjectNode item = route();
            item.withObject("/metadata").put("name", request.query().contains("continue=") ? "yuheng-exposure-810002" : TARGET.name())
                    .put("uid", request.query().contains("continue=") ? "route-2" : "route-1");
            list.putArray("items").add(item);
            return reply(200, list);
        };
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            var list = client.listHttpRoutes("yuheng-routes");
            assertThat(list.resourceVersion()).isEqualTo("list-rv");
            assertThat(list.items()).hasSize(2);
            assertThat(api.requests.get(1).query()).contains("continue=opaque%2F%2B%26");
            ((ObjectNode) list.items().getFirst()).put("privateMutation", true);
            assertThat(list.items().getFirst().has("privateMutation")).isFalse();
        }
    }

    @Test void changedPagedSnapshot410AndEndlessContinuationCannotYieldPartialProof() {
        for (String scenario : List.of("changed", "gone", "endless")) {
            api.requests.clear();
            api.script = request -> {
                boolean second = request.query().contains("continue=");
                if (second && scenario.equals("gone")) return reply(410, status(410, "Expired", request));
                ObjectNode list = JSON.createObjectNode().put("apiVersion", "gateway.networking.k8s.io/v1").put("kind", "HTTPRouteList");
                list.putObject("metadata").put("resourceVersion", second && scenario.equals("changed") ? "different" : "rv")
                        .put("continue", "again");
                list.putArray("items");
                return reply(200, list);
            };
            try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
                failure(() -> client.listHttpRoutes("yuheng-routes"), scenario.equals("gone") ? Kind.GONE : Kind.INVALID_RESPONSE);
                assertThat(api.requests.size()).isLessThanOrEqualTo(20);
            }
        }
    }

    @Test void endpointListsAreBoundToNamedServiceAndRejectForeignSliceLabels() {
        api.script = request -> {
            ObjectNode list = JSON.createObjectNode().put("apiVersion", "discovery.k8s.io/v1").put("kind", "EndpointSliceList");
            list.putObject("metadata").put("resourceVersion", "slices-rv");
            ObjectNode slice = resource("discovery.k8s.io/v1", "EndpointSlice", "apps-prod", "orders-abc", "slice-uid", "30");
            slice.withObject("/metadata").putObject("labels").put("kubernetes.io/service-name", request.query().contains("wrong") ? "other" : SERVICE.name());
            list.putArray("items").add(slice);
            return reply(200, list);
        };
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            assertThat(client.listEndpointSlices(SERVICE).items()).hasSize(1);
            assertThat(api.requests.getFirst().query()).contains("labelSelector=kubernetes.io%2Fservice-name%3Dorders-public");
            api.script = request -> {
                ObjectNode list = JSON.createObjectNode().put("apiVersion", "discovery.k8s.io/v1").put("kind", "EndpointSliceList");
                list.putObject("metadata").put("resourceVersion", "rv");
                ObjectNode slice = resource("discovery.k8s.io/v1", "EndpointSlice", "apps-prod", "orders-abc", "slice-uid", "30");
                slice.withObject("/metadata").putObject("labels").put("kubernetes.io/service-name", "other");
                list.putArray("items").add(slice); return reply(200, list);
            };
            failure(() -> client.listEndpointSlices(SERVICE), Kind.INVALID_RESPONSE);
        }
    }

    @Test void finiteWatchDeliversOnlyCompleteValidatedBatchAndExpiredWatchRequiresRelist() {
        api.script = request -> {
            ObjectNode event = JSON.createObjectNode().put("type", "MODIFIED"); event.set("object", route());
            ObjectNode bookmark = JSON.createObjectNode().put("type", "BOOKMARK");
            bookmark.set("object", resource("gateway.networking.k8s.io/v1", "HTTPRoute", "yuheng-routes", TARGET.name(), "route-uid", "12"));
            return new Reply(200, (event + "\n" + bookmark + "\n").getBytes(StandardCharsets.UTF_8), Map.of());
        };
        try (var client = client(Duration.ofSeconds(3), 64 * 1024)) {
            var events = new ArrayList<GatewayKubernetesClient.RouteEvent>();
            var result = client.watchHttpRoutes("yuheng-routes", "10", events::add);
            assertThat(result.events()).isEqualTo(2); assertThat(result.resourceVersion()).isEqualTo("12");
            assertThat(api.requests.getFirst().query()).contains("watch=true", "timeoutSeconds=1", "resourceVersion=10");
            api.script = request -> new Reply(200, (JSON.createObjectNode().put("type", "ERROR").set("object", status(410, "Expired", request)) + "\n").getBytes(StandardCharsets.UTF_8), Map.of());
            failure(() -> client.watchHttpRoutes("yuheng-routes", "old", events::add), Kind.GONE);
            events.clear();
            api.script = request -> new Reply(200, (JSON.createObjectNode().put("type", "MODIFIED").set("object", route()) + "\n{\"broken\":").getBytes(StandardCharsets.UTF_8), Map.of());
            failure(() -> client.watchHttpRoutes("yuheng-routes", "10", events::add), Kind.INVALID_RESPONSE);
            assertThat(events).isEmpty();
        }
    }

    @Test void redirectMalformedDuplicateOversizeAndStalledBodiesAreBoundedWithoutFollowingOrLeaking() {
        try (var client = client(Duration.ofMillis(800), 2048)) {
            client.getRoute(TARGET);
            api.script = request -> new Reply(302, "{}".getBytes(StandardCharsets.UTF_8), Map.of("Location", api.uri() + "/followed"));
            failure(() -> client.getRoute(TARGET), Kind.INVALID_RESPONSE);
            assertThat(api.requests.stream().filter(request -> request.path().equals("/followed"))).isEmpty();
            for (String body : List.of("{\"kind\":\"HTTPRoute\",\"kind\":\"Other\"}", "{} {}", "malformed", "x".repeat(8192))) {
                api.script = request -> new Reply(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
                assertThatThrownBy(() -> client.getRoute(TARGET)).isInstanceOf(Failure.class);
            }
            api.stall = true;
            long start = System.nanoTime();
            failure(() -> client.getRoute(TARGET), Kind.UNAVAILABLE);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test void hostnameAndOriginValidationUseProductionTlsChecksWithNoHttpOrSkipVerifyConstructor() {
        assertThatThrownBy(() -> new GatewayKubernetesClient(URI.create("http://127.0.0.1:1234"), api.ca, api.token, JSON, Duration.ofSeconds(1), 4096, SCOPE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayKubernetesClient(URI.create(api.uri() + "/arbitrary"), api.ca, api.token, JSON, Duration.ofSeconds(1), 4096, SCOPE))
                .isInstanceOf(IllegalArgumentException.class);
        try (var wrongHost = new GatewayKubernetesClient(URI.create("https://localhost:" + api.server.getAddress().getPort()),
                api.ca, api.token, JSON, Duration.ofSeconds(2), 4096, SCOPE)) {
            failure(() -> wrongHost.getRoute(TARGET), Kind.UNAVAILABLE);
        }
    }

    private GatewayKubernetesClient client(Duration timeout, int max) { return new GatewayKubernetesClient(api.uri(), api.ca, api.token, JSON, timeout, max, SCOPE); }
    private void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, Kind kind) {
        assertThatThrownBy(action).isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.kind()).isEqualTo(kind));
    }
    private static ObjectNode parent() {
        return JSON.createObjectNode().put("group", "gateway.networking.k8s.io").put("kind", "Gateway")
                .put("namespace", "edge-system").put("name", "public-apps").put("sectionName", "https-api");
    }
    private static ObjectNode compiled() {
        ObjectNode object = JSON.createObjectNode().put("apiVersion", "gateway.networking.k8s.io/v1").put("kind", "HTTPRoute");
        object.putObject("metadata").put("namespace", TARGET.namespace()).put("name", TARGET.name()).putObject("labels")
                .put("app.kubernetes.io/managed-by", "yuheng").put("stellar.io/exposure-id", "810001").put("stellar.io/owner-tenant-id", "7");
        ObjectNode spec = object.putObject("spec"); spec.putArray("parentRefs"); spec.putArray("hostnames").add("api.example.com");
        ObjectNode rule = spec.putArray("rules").addObject();
        rule.putArray("matches").addObject().put("method", "GET").putObject("path").put("type", "Exact").put("value", "/orders");
        rule.putArray("filters").addObject().put("type", "RequestHeaderModifier").putObject("requestHeaderModifier")
                .set("remove", JSON.valueToTree(GatewayHttpRouteCompiler.REMOVED_RETRY_HEADERS));
        rule.putArray("backendRefs").addObject().put("group", "").put("kind", "Service").put("namespace", SERVICE.namespace())
                .put("name", SERVICE.name()).put("port", 80).put("weight", 100);
        return object;
    }
    private static ObjectNode route() {
        ObjectNode object = compiled();
        object.withObject("/metadata").put("uid", "route-uid").put("resourceVersion", "10").put("generation", 1)
                .putObject("annotations").put(GatewayKubernetesClient.EXPOSURE_ANNOTATION, "810001")
                .put(GatewayKubernetesClient.GENERATION_ANNOTATION, "1").put(GatewayKubernetesClient.STATE_ANNOTATION, "OPEN");
        return object;
    }
    private static ObjectNode resource(String version, String kind, String namespace, String name, String uid, String rv) {
        var object = JSON.createObjectNode().put("apiVersion", version).put("kind", kind);
        object.putObject("metadata").put("namespace", namespace).put("name", name).put("uid", uid).put("resourceVersion", rv);
        return object;
    }
    private static ObjectNode status(int code, String reason, Request request) {
        var object = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Status").put("status", "Failure")
                .put("code", code).put("reason", reason).put("message", "private-details must not escape");
        if (code == 404) {
            String[] parts = request.path().split("/");
            object.putObject("details").put("name", parts[parts.length - 1]).put("kind", parts[parts.length - 2])
                    .put("group", request.path().startsWith("/api/") ? "" : "gateway.networking.k8s.io");
        }
        return object;
    }
    private static Reply reply(int code, JsonNode body) {
        try { return new Reply(code, JSON.writeValueAsBytes(body), Map.of()); }
        catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    private record Request(String method, String path, String query, String authorization, String contentType, JsonNode body) { }
    private record Reply(int code, byte[] body, Map<String, String> headers) { }

    private static final class Fixture implements AutoCloseable {
        private final HttpsServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(4);
        private final Path ca; private final Path token;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final AtomicLong rv = new AtomicLong(10);
        private final CountDownLatch releaseStall = new CountDownLatch(1);
        private volatile ObjectNode current;
        private volatile Function<Request, Reply> script;
        private volatile boolean stall;
        private volatile int patchConflictCode = 409;

        private Fixture(Path directory) throws Exception {
            Path store = directory.resolve("server.p12"); ca = directory.resolve("ca.pem"); token = directory.resolve("token");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            command(keytool, "-genkeypair", "-alias", "api", "-storetype", "PKCS12", "-keystore", store.toString(),
                    "-storepass", "fixture-password", "-keypass", "fixture-password", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "2", "-dname", "CN=Kubernetes Fixture", "-ext", "SAN=IP:127.0.0.1", "-noprompt");
            command(keytool, "-exportcert", "-rfc", "-alias", "api", "-keystore", store.toString(),
                    "-storepass", "fixture-password", "-file", ca.toString());
            var keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) { keys.load(input, "fixture-password".toCharArray()); }
            var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keys, "fixture-password".toCharArray());
            var ssl = SSLContext.getInstance("TLS"); ssl.init(factory.getKeyManagers(), null, null);
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(ssl)); server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                            exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery(),
                            exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Content-Type"),
                            body.length == 0 ? null : JSON.readTree(body));
                    requests.add(request);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (stall) {
                        exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                        try { releaseStall.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        return;
                    }
                    Reply response = script.apply(request);
                    response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                    exchange.sendResponseHeaders(response.code(), response.body().length); exchange.getResponseBody().write(response.body());
                } catch (java.io.IOException ignored) {
                    // Bounded clients may cancel a deliberately oversized or stalled response.
                }
            });
            reset(); server.start();
        }
        private void reset() throws Exception {
            requests.clear(); rv.set(10); current = route(); script = this::normal; stall = false; patchConflictCode = 409;
            Files.writeString(token, "api-first");
        }
        private synchronized Reply normal(Request request) {
            if ("GET".equals(request.method())) return current == null ? reply(404, status(404, "NotFound", request)) : reply(200, current);
            if ("POST".equals(request.method())) {
                if (current != null) return reply(409, status(409, "AlreadyExists", request));
                current = (ObjectNode) request.body().deepCopy();
                current.withObject("/metadata").put("uid", "route-uid").put("resourceVersion", Long.toString(rv.incrementAndGet())).put("generation", 1);
                return reply(201, current);
            }
            if ("PATCH".equals(request.method())) {
                if (current == null) return reply(404, status(404, "NotFound", request));
                ObjectNode candidate = current.deepCopy();
                for (JsonNode operation : request.body()) {
                    String path = operation.path("path").asText();
                    if ("test".equals(operation.path("op").asText())) {
                        if (!candidate.at(path).equals(operation.path("value"))) return reply(patchConflictCode, status(patchConflictCode, "Conflict", request));
                    } else if ("/spec".equals(path)) {
                        candidate.set("spec", operation.path("value").deepCopy());
                    } else if ("/metadata/annotations".equals(path)) {
                        candidate.withObject("/metadata").set("annotations", operation.path("value").deepCopy());
                    } else throw new IllegalStateException("unexpected fixture patch path");
                }
                if (!candidate.equals(current)) {
                    boolean changedSpec = !candidate.path("spec").equals(current.path("spec"));
                    candidate.withObject("/metadata").put("resourceVersion", Long.toString(rv.incrementAndGet()));
                    if (changedSpec) candidate.withObject("/metadata").put("generation", current.at("/metadata/generation").asLong() + 1);
                }
                current = candidate; return reply(200, current);
            }
            return reply(405, status(405, "MethodNotAllowed", request));
        }
        private URI uri() { return URI.create("https://127.0.0.1:" + server.getAddress().getPort()); }
        private static void command(String... command) throws Exception {
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("fixture keytool timed out"); }
            if (process.exitValue() != 0) throw new IllegalStateException("fixture keytool failed");
        }
        @Override public void close() { releaseStall.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
