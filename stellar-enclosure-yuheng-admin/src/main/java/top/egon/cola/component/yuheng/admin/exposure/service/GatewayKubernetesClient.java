package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Fixed publisher infrastructure transport. It never retries a mutation or emits API bodies/tokens in errors. */
@Slf4j
public class GatewayKubernetesClient implements AutoCloseable {

    public static final String EXPOSURE_ANNOTATION = "stellar.io/exposure-id";
    public static final String GENERATION_ANNOTATION = "stellar.io/desired-generation";
    public static final String STATE_ANNOTATION = "stellar.io/desired-state";
    private static final String GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_VERSION = GROUP + "/v1";
    private static final int MAX_TOKEN_BYTES = 16_384;
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 20;
    private static final int MAX_ITEMS = 10_000;
    private static final int MAX_WATCH_EVENTS = 1_000;
    private static final int MAX_WATCH_LINE_BYTES = 256 * 1024;

    private final URI apiServer;
    private final Path tokenFile;
    private final ObjectMapper json;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final AccessScope authority;
    private final HttpClient client;

    public GatewayKubernetesClient(URI apiServer, Path caFile, Path tokenFile, ObjectMapper mapper,
                                   Duration timeout, int maxResponseBytes, AccessScope authority) {
        if (apiServer == null || !"https".equals(apiServer.getScheme()) || apiServer.getHost() == null
                || apiServer.getRawUserInfo() != null || apiServer.getRawQuery() != null || apiServer.getRawFragment() != null
                || apiServer.getPort() == 0 || apiServer.getPort() > 65535
                || !(apiServer.getRawPath().isEmpty() || "/".equals(apiServer.getRawPath()))
                || timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(30)) > 0
                || maxResponseBytes < 1 || maxResponseBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("YUHENG_KUBERNETES_TRANSPORT_INVALID");
        }
        this.apiServer = apiServer.resolve("/");
        this.tokenFile = Objects.requireNonNull(tokenFile, "tokenFile");
        this.json = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.authority = Objects.requireNonNull(authority, "authority");
        try {
            var certificates = CertificateFactory.getInstance("X.509");
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            byte[] material;
            try (var input = Files.newInputStream(Objects.requireNonNull(caFile, "caFile"))) {
                material = input.readNBytes(1_048_577);
                if (material.length > 1_048_576) throw new IllegalArgumentException();
            }
            var parsed = certificates.generateCertificates(new java.io.ByteArrayInputStream(material));
            if (parsed.isEmpty()) throw new IllegalArgumentException();
            int index = 0;
            for (var certificate : parsed) store.setCertificateEntry("kubernetes-" + index++, certificate);
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trust.getTrustManagers(), null);
            var parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client = HttpClient.newBuilder().sslContext(ssl).sslParameters(parameters).connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER).proxy(new DirectProxySelector())
                    .version(HttpClient.Version.HTTP_1_1).build();
        } catch (Exception failure) {
            throw new IllegalStateException("YUHENG_KUBERNETES_CA_UNAVAILABLE");
        }
    }

    public AccessScope scope() { return authority; }

    public Optional<RouteSnapshot> getRoute(RouteTarget target) {
        requireRoute(target);
        try {
            return Optional.of(route(target, parse(exchange("GET", routePath(target), null, null, deadline()).body())));
        } catch (Failure failure) {
            if (failure.kind() == Kind.NOT_FOUND) return Optional.empty();
            throw failure;
        }
    }

    /** The caller supplies a freshly committed DB fence. CREATE always records it on an inert object. */
    public RouteSnapshot createInert(RouteTarget target, ControlFence current, JsonNode verifiedCompiledRoute) {
        requireRoute(target);
        Objects.requireNonNull(current, "current");
        ObjectNode object = compiled(target, verifiedCompiledRoute);
        object.withObject("/spec").putArray("parentRefs");
        ownedAnnotations(object, target, current);
        var response = exchange("POST", routeCollection(target.namespace()), object, "application/json", deadline());
        if (response.status() != 201) throw new Failure(Kind.INVALID_RESPONSE, response.status());
        RouteSnapshot result = route(target, parse(response.body()));
        requireReturnedFence(result, current);
        if (!result.document().at("/spec/parentRefs").isArray() || !result.document().at("/spec/parentRefs").isEmpty()) {
            throw new Failure(Kind.INVALID_RESPONSE);
        }
        return result;
    }

    /**
     * DB and Kubernetes are separate consistency domains. The coordinator must read the latest DB
     * state after its cluster read; tests below fence only the observed Kubernetes object.
     * Closed/terminal calls do not require an artifact and still stamp the current control fence.
     */
    public RouteSnapshot patchDesired(RouteSnapshot observed, ControlFence current,
                                      JsonNode verifiedCompiledRoute, JsonNode approvedParent) {
        Objects.requireNonNull(observed, "observed"); Objects.requireNonNull(current, "current");
        RouteTarget target = observed.target(); requireRoute(target);
        RouteSnapshot verified = route(target, observed.document());
        if (!sameSnapshot(observed, verified) || current.desiredGeneration() < observed.controlGeneration()
                || observed.controlState() == GatewayUrlExposureStateEnum.WITHDRAWN
                    && current.desiredState() != GatewayUrlExposureStateEnum.WITHDRAWN
                || observed.controlState() != null && observed.controlState() != current.desiredState()
                    && current.desiredGeneration() <= observed.controlGeneration()) {
            throw new Failure(Kind.CONFLICT);
        }
        ObjectNode desired;
        if (current.desiredState() == GatewayUrlExposureStateEnum.OPEN) {
            ObjectNode artifact = compiled(target, verifiedCompiledRoute);
            validateParent(approvedParent);
            desired = (ObjectNode) artifact.path("spec").deepCopy();
            desired.putArray("parentRefs").add(approvedParent.deepCopy());
        } else {
            desired = (ObjectNode) observed.document().path("spec").deepCopy();
            desired.putArray("parentRefs");
        }
        ObjectNode annotations = (ObjectNode) observed.document().path("metadata").path("annotations").deepCopy();
        annotations.put(EXPOSURE_ANNOTATION, Long.toString(target.exposureId()));
        annotations.put(GENERATION_ANNOTATION, Long.toString(current.desiredGeneration()));
        annotations.put(STATE_ANNOTATION, current.desiredState().wireValue());
        ArrayNode patch = json.createArrayNode();
        test(patch, "/metadata/uid", json.getNodeFactory().textNode(observed.uid()));
        test(patch, "/metadata/resourceVersion", json.getNodeFactory().textNode(observed.resourceVersion()));
        test(patch, "/metadata/annotations/" + pointer(EXPOSURE_ANNOTATION),
                json.getNodeFactory().textNode(Long.toString(target.exposureId())));
        test(patch, "/metadata/annotations/" + pointer(GENERATION_ANNOTATION),
                json.getNodeFactory().textNode(Long.toString(observed.controlGeneration())));
        if (observed.controlState() != null) test(patch, "/metadata/annotations/" + pointer(STATE_ANNOTATION),
                json.getNodeFactory().textNode(observed.controlState().wireValue()));
        patch.addObject().put("op", "replace").put("path", "/metadata/annotations").set("value", annotations);
        patch.addObject().put("op", "replace").put("path", "/spec").set("value", desired);
        var response = exchange("PATCH", routePath(target), patch, "application/json-patch+json", deadline());
        if (response.status() != 200) throw new Failure(Kind.INVALID_RESPONSE, response.status());
        RouteSnapshot result = route(target, parse(response.body()));
        if (!observed.uid().equals(result.uid())) throw new Failure(Kind.INVALID_RESPONSE);
        requireReturnedFence(result, current);
        if (!result.document().path("spec").equals(desired)) throw new Failure(Kind.INVALID_RESPONSE);
        return result;
    }

    public JsonNode getGateway(GatewayKey key) {
        if (!authority.gateways().contains(Objects.requireNonNull(key, "key"))) throw outsideScope();
        return get("/apis/" + GATEWAY_VERSION + "/namespaces/" + key.namespace() + "/gateways/" + key.name(),
                GATEWAY_VERSION, "Gateway", key.namespace(), key.name());
    }

    public JsonNode getService(ServiceKey key) {
        requireService(key);
        return get("/api/v1/namespaces/" + key.namespace() + "/services/" + key.name(),
                "v1", "Service", key.namespace(), key.name());
    }

    public JsonNode getReferenceGrant(GrantKey key) {
        if (!authority.grants().contains(Objects.requireNonNull(key, "key"))) throw outsideScope();
        return get("/apis/" + GATEWAY_VERSION + "/namespaces/" + key.namespace() + "/referencegrants/" + key.name(),
                GATEWAY_VERSION, "ReferenceGrant", key.namespace(), key.name());
    }

    public ListSnapshot listHttpRoutes(String namespace) {
        if (!authority.routeNamespace().equals(namespace)) throw outsideScope();
        return list(routeCollection(namespace), GATEWAY_VERSION, "HTTPRoute", namespace, null);
    }

    public ListSnapshot listEndpointSlices(ServiceKey service) {
        requireService(service);
        return list("/apis/discovery.k8s.io/v1/namespaces/" + service.namespace() + "/endpointslices",
                "discovery.k8s.io/v1", "EndpointSlice", service.namespace(), service.name());
    }

    /** One finite, bounded watch batch; no background thread, reconnect or hidden relist is started. */
    public WatchResult watchHttpRoutes(String namespace, String resourceVersion, Consumer<RouteEvent> observer) {
        if (!authority.routeNamespace().equals(namespace)) throw outsideScope();
        opaque(resourceVersion, 256);
        Objects.requireNonNull(observer, "observer");
        String query = "?watch=true&allowWatchBookmarks=true&resourceVersion=" + query(resourceVersion)
                + "&timeoutSeconds=" + Math.max(1, timeout.toSeconds() / 2);
        byte[] bytes = exchange("GET", routeCollection(namespace) + query, null, null, deadline()).body();
        int start = 0; int count = 0; String latest = resourceVersion;
        List<RouteEvent> events = new ArrayList<>();
        for (int index = 0; index <= bytes.length; index++) {
            if (index != bytes.length && bytes[index] != '\n') continue;
            int end = index;
            if (end > start && bytes[end - 1] == '\r') end--;
            if (end - start > MAX_WATCH_LINE_BYTES) throw new Failure(Kind.INVALID_RESPONSE);
            if (end > start) {
                JsonNode event = parse(java.util.Arrays.copyOfRange(bytes, start, end));
                String type = text(event, "type");
                JsonNode object = event.get("object");
                if ("ERROR".equals(type)) {
                    int code = object != null && object.path("code").isIntegralNumber() && object.path("code").canConvertToInt()
                            ? object.path("code").intValue() : 0;
                    throw statusFailure(object, "GET", code, null, null);
                }
                if (type == null || !Set.of("ADDED", "MODIFIED", "DELETED", "BOOKMARK").contains(type) || object == null) {
                    throw new Failure(Kind.INVALID_RESPONSE);
                }
                if (!GATEWAY_VERSION.equals(text(object, "apiVersion")) || !"HTTPRoute".equals(text(object, "kind"))) {
                    throw new Failure(Kind.INVALID_RESPONSE);
                }
                latest = text(object.path("metadata"), "resourceVersion"); opaque(latest, 256);
                if (!"BOOKMARK".equals(type)) verifyObject(object, GATEWAY_VERSION, "HTTPRoute", namespace, null);
                if (++count > MAX_WATCH_EVENTS) throw new Failure(Kind.INVALID_RESPONSE);
                events.add(new RouteEvent(type, latest, object));
            }
            start = index + 1;
        }
        // Malformed or incomplete batches never deliver a partly accepted event sequence.
        events.forEach(observer);
        return new WatchResult(latest, count);
    }

    private ListSnapshot list(String path, String version, String kind, String namespace, String serviceName) {
        long end = deadline(); String next = null; String resourceVersion = null;
        Set<String> seen = new LinkedHashSet<>(); List<JsonNode> items = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            String request = path + "?limit=" + PAGE_SIZE;
            if (serviceName != null) request += "&labelSelector=" + query("kubernetes.io/service-name=" + serviceName);
            if (next != null) request += "&continue=" + query(next);
            JsonNode body = parse(exchange("GET", request, null, null, end).body());
            if (!version.equals(text(body, "apiVersion")) || !(kind + "List").equals(text(body, "kind"))
                    || !body.path("items").isArray() || body.path("items").size() > PAGE_SIZE) throw new Failure(Kind.INVALID_RESPONSE);
            String current = text(body.path("metadata"), "resourceVersion"); opaque(current, 256);
            if (resourceVersion != null && !resourceVersion.equals(current)) throw new Failure(Kind.INVALID_RESPONSE);
            resourceVersion = current;
            for (JsonNode item : body.path("items")) {
                verifyObject(item, version, kind, namespace, null);
                if (serviceName != null && !serviceName.equals(text(item.path("metadata").path("labels"), "kubernetes.io/service-name"))) {
                    throw new Failure(Kind.INVALID_RESPONSE);
                }
                if (!seen.add(text(item.path("metadata"), "uid")) || items.size() >= MAX_ITEMS) throw new Failure(Kind.INVALID_RESPONSE);
                items.add(item.deepCopy());
            }
            JsonNode continuation = body.path("metadata").get("continue");
            if (continuation != null && !continuation.isTextual()) throw new Failure(Kind.INVALID_RESPONSE);
            next = continuation == null ? "" : continuation.textValue();
            if (next.isEmpty()) return new ListSnapshot(resourceVersion, items);
            opaque(next, 4096);
        }
        throw new Failure(Kind.INVALID_RESPONSE);
    }

    private JsonNode get(String path, String version, String kind, String namespace, String name) {
        JsonNode object = parse(exchange("GET", path, null, null, deadline()).body());
        verifyObject(object, version, kind, namespace, name);
        return object.deepCopy();
    }

    private RouteSnapshot route(RouteTarget target, JsonNode object) {
        verifyObject(object, GATEWAY_VERSION, "HTTPRoute", target.namespace(), target.name());
        JsonNode metadata = object.path("metadata");
        JsonNode labels = metadata.path("labels");
        JsonNode annotations = metadata.path("annotations");
        if (!"yuheng".equals(text(labels, "app.kubernetes.io/managed-by"))
                || !Long.toString(target.exposureId()).equals(text(labels, EXPOSURE_ANNOTATION))
                || !Long.toString(target.ownerTenantId()).equals(text(labels, "stellar.io/owner-tenant-id"))
                || !Long.toString(target.exposureId()).equals(text(annotations, EXPOSURE_ANNOTATION))
                || !object.path("spec").isObject()) throw new Failure(Kind.INVALID_RESPONSE);
        long generation = positiveDecimal(text(annotations, GENERATION_ANNOTATION));
        JsonNode value = annotations.get(STATE_ANNOTATION);
        GatewayUrlExposureStateEnum state = null;
        if (value != null) {
            if (!value.isTextual()) throw new Failure(Kind.INVALID_RESPONSE);
            try { state = GatewayUrlExposureStateEnum.fromWire(value.textValue()); }
            catch (RuntimeException invalid) { throw new Failure(Kind.INVALID_RESPONSE); }
        }
        JsonNode kubernetesGeneration = metadata.get("generation");
        if (kubernetesGeneration == null || !kubernetesGeneration.isIntegralNumber() || !kubernetesGeneration.canConvertToLong()
                || kubernetesGeneration.longValue() < 1) throw new Failure(Kind.INVALID_RESPONSE);
        return new RouteSnapshot(target, text(metadata, "uid"), text(metadata, "resourceVersion"),
                kubernetesGeneration.longValue(), generation, state, object);
    }

    private ObjectNode compiled(RouteTarget target, JsonNode value) {
        if (value == null || !value.isObject() || !GATEWAY_VERSION.equals(text(value, "apiVersion"))
                || !"HTTPRoute".equals(text(value, "kind"))
                || !target.namespace().equals(text(value.path("metadata"), "namespace"))
                || !target.name().equals(text(value.path("metadata"), "name"))
                || value.has("status") || value.path("metadata").has("uid") || value.path("metadata").has("resourceVersion")
                || !value.path("spec").isObject() || !value.at("/spec/parentRefs").isArray()
                || !value.at("/spec/parentRefs").isEmpty()) throw outsideScope();
        if (!fields(value).equals(Set.of("apiVersion", "kind", "metadata", "spec"))
                || !fields(value.path("metadata")).equals(Set.of("namespace", "name", "labels"))
                || !fields(value.path("spec")).equals(Set.of("parentRefs", "hostnames", "rules"))
                || value.at("/spec/hostnames").size() != 1 || !value.at("/spec/hostnames/0").isTextual()
                || !value.at("/spec/rules").isArray() || value.at("/spec/rules").size() != 1) throw outsideScope();
        ObjectNode object = (ObjectNode) value.deepCopy();
        JsonNode rule = object.at("/spec/rules/0");
        if (!fields(rule).equals(Set.of("matches", "filters", "backendRefs"))
                || !rule.path("matches").isArray() || rule.path("matches").size() != 1
                || !rule.path("filters").isArray() || rule.path("filters").size() != 1
                || !fields(rule.at("/filters/0")).equals(Set.of("type", "requestHeaderModifier"))
                || !"RequestHeaderModifier".equals(text(rule.at("/filters/0"), "type"))
                || !fields(rule.at("/filters/0/requestHeaderModifier")).equals(Set.of("remove"))
                || !json.valueToTree(GatewayHttpRouteCompiler.REMOVED_RETRY_HEADERS).equals(rule.at("/filters/0/requestHeaderModifier/remove"))
                || !rule.path("backendRefs").isArray() || rule.path("backendRefs").isEmpty() || rule.path("backendRefs").size() > 16) throw outsideScope();
        JsonNode match = rule.at("/matches/0");
        if (!(fields(match).equals(Set.of("method", "path")) || fields(match).equals(Set.of("path")))
                || !fields(match.path("path")).equals(Set.of("type", "value"))
                || text(match.path("path"), "type") == null
                || !Set.of("Exact", "PathPrefix").contains(text(match.path("path"), "type"))
                || text(match.path("path"), "value") == null || !text(match.path("path"), "value").startsWith("/")) throw outsideScope();
        long weight = 0; Set<ServiceKey> targets = new LinkedHashSet<>();
        for (JsonNode backend : rule.path("backendRefs")) {
            if (!fields(backend).equals(Set.of("group", "kind", "namespace", "name", "port", "weight"))
                    || !"".equals(text(backend, "group")) || !"Service".equals(text(backend, "kind"))) throw outsideScope();
            int port = gatewayInteger(backend.get("port"));
            int targetWeight = gatewayInteger(backend.get("weight"));
            if (port != 80 || targetWeight < 0 || targetWeight > 1_000_000) throw outsideScope();
            ServiceKey key = new ServiceKey(text(backend, "namespace"), text(backend, "name"));
            if (!authority.services().contains(key) || !targets.add(key)) throw outsideScope();
            ((ObjectNode) backend).put("port", port).put("weight", targetWeight);
            weight += targetWeight;
        }
        if (weight == 0) throw outsideScope();
        JsonNode labels = object.path("metadata").path("labels");
        if (!"yuheng".equals(text(labels, "app.kubernetes.io/managed-by"))
                || !Long.toString(target.exposureId()).equals(text(labels, EXPOSURE_ANNOTATION))
                || !Long.toString(target.ownerTenantId()).equals(text(labels, "stellar.io/owner-tenant-id"))) throw outsideScope();
        return object;
    }

    private static int gatewayInteger(JsonNode value) {
        if (value == null || !value.isNumber()) throw outsideScope();
        try {
            return value.decimalValue().intValueExact();
        } catch (ArithmeticException invalid) {
            throw outsideScope();
        }
    }

    private void validateParent(JsonNode parent) {
        if (parent == null || !parent.isObject() || !GROUP.equals(text(parent, "group"))
                || !"Gateway".equals(text(parent, "kind")) || parent.has("port") || parent.size() != 5
                || !authority.gateways().contains(new GatewayKey(text(parent, "namespace"), text(parent, "name"), text(parent, "sectionName")))) throw outsideScope();
        dnsLabel(text(parent, "sectionName"));
    }

    private void ownedAnnotations(ObjectNode object, RouteTarget target, ControlFence current) {
        object.withObject("/metadata").withObject("/annotations")
                .put(EXPOSURE_ANNOTATION, Long.toString(target.exposureId()))
                .put(GENERATION_ANNOTATION, Long.toString(current.desiredGeneration()))
                .put(STATE_ANNOTATION, current.desiredState().wireValue());
    }

    private void requireReturnedFence(RouteSnapshot result, ControlFence expected) {
        if (result.controlGeneration() != expected.desiredGeneration() || result.controlState() != expected.desiredState()) {
            throw new Failure(Kind.INVALID_RESPONSE);
        }
    }

    private boolean sameSnapshot(RouteSnapshot a, RouteSnapshot b) {
        return a.target().equals(b.target()) && a.uid().equals(b.uid()) && a.resourceVersion().equals(b.resourceVersion())
                && a.metadataGeneration() == b.metadataGeneration() && a.controlGeneration() == b.controlGeneration()
                && a.controlState() == b.controlState();
    }

    private void verifyObject(JsonNode object, String version, String kind, String namespace, String name) {
        if (object == null || !object.isObject() || !version.equals(text(object, "apiVersion"))
                || !kind.equals(text(object, "kind")) || !namespace.equals(text(object.path("metadata"), "namespace"))
                || name != null && !name.equals(text(object.path("metadata"), "name"))) throw new Failure(Kind.INVALID_RESPONSE);
        opaque(text(object.path("metadata"), "uid"), 128);
        opaque(text(object.path("metadata"), "resourceVersion"), 256);
        try { dnsName(text(object.path("metadata"), "name")); }
        catch (IllegalArgumentException invalid) { throw new Failure(Kind.INVALID_RESPONSE); }
    }

    private Response exchange(String method, String path, JsonNode body, String contentType, long deadline) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        var subscriber = new BoundedBodySubscriber(maxResponseBytes);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new Failure(Kind.UNAVAILABLE);
            // Exactly one read of the raw projected Kubernetes credential for this physical request.
            if (!Files.isRegularFile(tokenFile)) throw new Failure(Kind.UNAVAILABLE);
            String token;
            try (var input = Files.newInputStream(tokenFile)) {
                byte[] raw = input.readNBytes(MAX_TOKEN_BYTES + 1);
                token = new String(raw, StandardCharsets.US_ASCII);
                if (raw.length == 0 || raw.length > MAX_TOKEN_BYTES || !token.matches("[A-Za-z0-9._~+/-]+=*")) throw new Failure(Kind.UNAVAILABLE);
            }
            var request = HttpRequest.newBuilder(apiServer.resolve(path)).timeout(Duration.ofNanos(remaining))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json");
            if (body == null) request.GET();
            else {
                byte[] bytes = json.writeValueAsBytes(body);
                if (bytes.length > maxResponseBytes) throw new Failure(Kind.INVALID_RESPONSE);
                request.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
            }
            pending = client.sendAsync(request.build(), ignored -> subscriber);
            var response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (!"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())
                    || response.headers().firstValue("Content-Encoding").filter(v -> !"identity".equalsIgnoreCase(v)).isPresent()) {
                throw new Failure(Kind.INVALID_RESPONSE, response.statusCode());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw statusFailure(parse(response.body()), method, response.statusCode(), path, response.headers().firstValue("Retry-After").orElse(null));
            }
            if (response.statusCode() != 200 && !("POST".equals(method) && response.statusCode() == 201)) throw new Failure(Kind.INVALID_RESPONSE, response.statusCode());
            return new Response(response.statusCode(), response.body());
        } catch (Failure failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new Failure(Kind.UNAVAILABLE);
        } catch (IOException | ExecutionException | TimeoutException | RuntimeException unavailable) {
            throw new Failure(Kind.UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            subscriber.cancel();
        }
    }

    private Failure statusFailure(JsonNode status, String method, int code, String path, String retryAfter) {
        if (status == null || !status.isObject() || !"v1".equals(text(status, "apiVersion"))
                || !"Status".equals(text(status, "kind")) || !"Failure".equals(text(status, "status"))
                || !status.path("code").isIntegralNumber() || !status.path("code").canConvertToInt()
                || status.path("code").intValue() != code || text(status, "reason") == null) return new Failure(Kind.INVALID_RESPONSE, code);
        if (code == 404 && path != null) {
            JsonNode details = status.get("details");
            String clean = path.substring(0, path.indexOf('?') < 0 ? path.length() : path.indexOf('?'));
            String[] segments = clean.split("/");
            if ("GET".equals(method) && segments.length > 6 && !Set.of("httproutes", "endpointslices").contains(segments[segments.length - 1])) {
                String expectedResource = segments[segments.length - 2];
                String expectedGroup = clean.startsWith("/api/") ? "" : clean.split("/")[2];
                if (details == null || !details.isObject()
                        || !segments[segments.length - 1].equals(text(details, "name"))
                        || !expectedResource.equals(text(details, "kind"))
                        || !(expectedGroup.isEmpty() ? text(details, "group") == null || "".equals(text(details, "group"))
                            : expectedGroup.equals(text(details, "group")))) {
                    return new Failure(Kind.INVALID_RESPONSE, code);
                }
            }
        }
        Kind kind = switch (code) {
            case 401 -> Kind.UNAUTHENTICATED;
            case 403 -> Kind.FORBIDDEN;
            case 404 -> "NotFound".equals(text(status, "reason")) ? Kind.NOT_FOUND : Kind.INVALID_RESPONSE;
            case 409 -> Kind.CONFLICT;
            case 422 -> "PATCH".equals(method) ? Kind.CONFLICT : Kind.INVALID_RESPONSE;
            case 410 -> Kind.GONE;
            case 429 -> Kind.THROTTLED;
            default -> code >= 500 ? Kind.UNAVAILABLE : Kind.INVALID_RESPONSE;
        };
        Duration retry = null;
        if (code == 429 && retryAfter != null && retryAfter.matches("[1-9][0-9]{0,3}")) {
            retry = Duration.ofSeconds(Math.min(3600, Long.parseLong(retryAfter)));
        }
        return new Failure(kind, code, retry);
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode result = json.readTree(body);
            if (result == null || !result.isObject()) throw new Failure(Kind.INVALID_RESPONSE);
            return result;
        } catch (IOException | RuntimeException invalid) { throw new Failure(Kind.INVALID_RESPONSE); }
    }

    private void requireRoute(RouteTarget target) {
        if (target == null || !authority.routeNamespace().equals(target.namespace())) throw outsideScope();
    }
    private void requireService(ServiceKey key) {
        if (!authority.services().contains(Objects.requireNonNull(key, "key"))) throw outsideScope();
    }
    private long deadline() { return System.nanoTime() + timeout.toNanos(); }
    private static String routeCollection(String namespace) { return "/apis/" + GATEWAY_VERSION + "/namespaces/" + namespace + "/httproutes"; }
    private static String routePath(RouteTarget target) { return routeCollection(target.namespace()) + "/" + target.name(); }
    private static String query(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String pointer(String value) { return value.replace("~", "~0").replace("/", "~1"); }
    private static Set<String> fields(JsonNode object) {
        Set<String> names = new LinkedHashSet<>(); object.fieldNames().forEachRemaining(names::add); return names;
    }
    private void test(ArrayNode patch, String path, JsonNode value) { patch.addObject().put("op", "test").put("path", path).set("value", value); }
    private static String text(JsonNode object, String field) { JsonNode node = object == null ? null : object.get(field); return node != null && node.isTextual() ? node.textValue() : null; }
    private static void opaque(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) throw new Failure(Kind.INVALID_RESPONSE);
    }
    private static long positiveDecimal(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new Failure(Kind.INVALID_RESPONSE);
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { throw new Failure(Kind.INVALID_RESPONSE); }
    }
    private static void dnsLabel(String value) {
        if (value == null || !value.matches("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?")) throw outsideScope();
    }
    private static void dnsName(String value) {
        if (value == null || value.length() > 253) throw outsideScope();
        for (String part : value.split("\\.", -1)) dnsLabel(part);
    }
    private static IllegalArgumentException outsideScope() { return new IllegalArgumentException("YUHENG_KUBERNETES_SCOPE_INVALID"); }

    @Override @PreDestroy public void close() { client.shutdownNow(); }

    public record AccessScope(String clusterId, String routeNamespace, Set<GatewayKey> gateways,
                              Set<ServiceKey> services, Set<GrantKey> grants) {
        public AccessScope {
            if (clusterId == null || clusterId.isBlank() || clusterId.length() > 128) throw outsideScope();
            dnsLabel(routeNamespace); gateways = Set.copyOf(gateways); services = Set.copyOf(services); grants = Set.copyOf(grants);
        }
    }
    public record GatewayKey(String namespace, String name, String listenerName) {
        public GatewayKey(String namespace, String name) { this(namespace, name, "https-api"); }
        public GatewayKey { dnsLabel(namespace); dnsName(name); dnsLabel(listenerName); }
    }
    public record ServiceKey(String namespace, String name) { public ServiceKey { dnsLabel(namespace); dnsName(name); } }
    public record GrantKey(String namespace, String name) { public GrantKey { dnsLabel(namespace); dnsName(name); } }
    public record RouteTarget(long ownerTenantId, long exposureId, String namespace) {
        public RouteTarget { if (ownerTenantId <= 0 || exposureId <= 0) throw outsideScope(); dnsLabel(namespace); }
        public String name() { return "yuheng-exposure-" + exposureId; }
    }
    public record ControlFence(long dbVersion, long desiredGeneration, GatewayUrlExposureStateEnum desiredState) {
        public ControlFence { if (dbVersion < 0 || desiredGeneration <= 0 || desiredState == null) throw outsideScope(); }
    }
    public record RouteSnapshot(RouteTarget target, String uid, String resourceVersion, long metadataGeneration,
                                long controlGeneration, GatewayUrlExposureStateEnum controlState, JsonNode document) {
        public RouteSnapshot { Objects.requireNonNull(target, "target"); document = Objects.requireNonNull(document, "document").deepCopy(); }
        @Override public JsonNode document() { return document.deepCopy(); }
    }
    public record ListSnapshot(String resourceVersion, List<JsonNode> items) {
        public ListSnapshot { items = items.stream().<JsonNode>map(JsonNode::deepCopy).toList(); }
        @Override public List<JsonNode> items() { return items.stream().<JsonNode>map(JsonNode::deepCopy).toList(); }
    }
    public record RouteEvent(String type, String resourceVersion, JsonNode object) {
        public RouteEvent { object = object.deepCopy(); }
        @Override public JsonNode object() { return object.deepCopy(); }
    }
    public record WatchResult(String resourceVersion, int events) { }
    public enum Kind { UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, GONE, THROTTLED, UNAVAILABLE, INVALID_RESPONSE }
    public static final class Failure extends RuntimeException {
        private final Kind kind; private final int status; private final Duration retryAfter;
        public Failure(Kind kind) { this(kind, 0, null); }
        public Failure(Kind kind, int status) { this(kind, status, null); }
        private Failure(Kind kind, int status, Duration retryAfter) {
            super("YUHENG_KUBERNETES_" + Objects.requireNonNull(kind, "kind")); this.kind = kind; this.status = status; this.retryAfter = retryAfter;
        }
        public Kind kind() { return kind; }
        public int status() { return status; }
        public Optional<Duration> retryAfter() { return Optional.ofNullable(retryAfter); }
    }
    private record Response(int status, byte[] body) { }

    private static final class DirectProxySelector extends ProxySelector {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private BoundedBodySubscriber(int maxBytes) { this.maxBytes = maxBytes; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            try {
                for (ByteBuffer chunk : chunks) {
                    if ((long) bytes.size() + chunk.remaining() > maxBytes) { cancel(); body.completeExceptionally(new IOException("bounded response")); return; }
                    byte[] value = new byte[chunk.remaining()]; chunk.get(value); bytes.write(value);
                }
                subscription.request(1);
            } catch (IOException failure) { cancel(); body.completeExceptionally(failure); }
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
        private void cancel() { if (subscription != null) subscription.cancel(); }
    }
}
