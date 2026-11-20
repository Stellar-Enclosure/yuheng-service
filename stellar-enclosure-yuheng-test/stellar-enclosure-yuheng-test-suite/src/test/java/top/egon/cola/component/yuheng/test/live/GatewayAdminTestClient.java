package top.egon.cola.component.yuheng.test.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class GatewayAdminTestClient {

    private static final String API = "/api/v1/yuheng/admin";

    private final URI baseUri;

    private final String bearerToken;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public static URI reviewedServiceOrigin(String value) {
        return GatewayLiveEnvironment.eastWest(value);
    }

    public GatewayAdminTestClient(URI baseUri, String bearerToken) {
        this(
                baseUri,
                bearerToken,
                new ObjectMapper(),
                HttpClient.newBuilder()
                        .proxy(HttpClient.Builder.NO_PROXY)
                        .connectTimeout(Duration.ofSeconds(3))
                        .build()
        );
    }

    GatewayAdminTestClient(
            URI baseUri,
            String bearerToken,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken is required");
        }
        this.bearerToken = bearerToken;
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper"
        );
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public JsonNode createApplication(Object request)
            throws IOException, InterruptedException {
        return post(API + "/applications", request);
    }

    public JsonNode createCredential(String applicationId)
            throws IOException, InterruptedException {
        return post(
                API + "/applications/" + segment(applicationId)
                        + "/credentials",
                java.util.Map.of()
        );
    }

    public JsonNode applicationCatalog(String applicationId)
            throws IOException, InterruptedException {
        return get(API + "/applications/" + segment(applicationId)
                + "/catalog");
    }

    public JsonNode createManualInterfaceGroup(
            String applicationId,
            Object request) throws IOException, InterruptedException {
        return post(
                API + "/applications/" + segment(applicationId)
                        + "/manual-interface-groups",
                request
        );
    }

    public JsonNode createManualOperation(
            String interfaceGroupId,
            Object request) throws IOException, InterruptedException {
        return post(
                API + "/interface-groups/" + segment(interfaceGroupId)
                        + "/manual-operations",
                request
        );
    }

    public JsonNode createGroup(Object request)
            throws IOException, InterruptedException {
        return post(API + "/yuheng-groups", request);
    }

    public JsonNode putRoute(
            String groupId,
            String routeId,
            Object request) throws IOException, InterruptedException {
        return put(draft(groupId) + "/routes/" + segment(routeId), request);
    }

    public JsonNode getDraft(String groupId)
            throws IOException, InterruptedException {
        return get(draft(groupId));
    }

    public JsonNode putPolicy(
            String groupId,
            String policyId,
            Object request) throws IOException, InterruptedException {
        return put(
                draft(groupId) + "/policies/" + segment(policyId),
                request
        );
    }

    public JsonNode validateDraft(String groupId)
            throws IOException, InterruptedException {
        return post(draft(groupId) + "/validate", java.util.Map.of());
    }

    public JsonNode release(String groupId, Object request)
            throws IOException, InterruptedException {
        return post(
                API + "/yuheng-groups/" + segment(groupId) + "/releases",
                request
        );
    }

    public JsonNode rollback(String groupId, Object request)
            throws IOException, InterruptedException {
        return post(
                API + "/yuheng-groups/" + segment(groupId) + "/rollback",
                request
        );
    }

    public JsonNode runtimeConsistency(String groupId)
            throws IOException, InterruptedException {
        return get(API + "/yuheng-groups/" + segment(groupId)
                + "/runtime-consistency");
    }

    public JsonNode engineNodes(String groupId)
            throws IOException, InterruptedException {
        return get(API + "/yuheng-groups/" + segment(groupId)
                + "/engine-nodes");
    }

    public JsonNode providerInstances(
            String env,
            String namespace,
            String protocol,
            String serviceName) throws IOException, InterruptedException {
        return providerInstances(
                env,
                namespace,
                protocol,
                serviceName,
                null,
                null
        );
    }

    public JsonNode providerInstances(
            String env,
            String namespace,
            String protocol,
            String serviceName,
            String group,
            String version) throws IOException, InterruptedException {
        String scope = group == null || version == null
                ? ""
                : "&group=" + query(group) + "&version=" + query(version);
        return get(API + "/providers/instances?env=" + query(env)
                + "&namespace=" + query(namespace)
                + "&protocol=" + query(protocol)
                + "&serviceName=" + query(serviceName)
                + scope);
    }

    public JsonNode traces(
            String env,
            String namespace,
            String traceId) throws IOException, InterruptedException {
        return get(API + "/observability/traces?env=" + query(env)
                + "&namespace=" + query(namespace)
                + "&traceId=" + query(traceId));
    }

    public ExposureExchange getExposure(String exposureId) throws IOException, InterruptedException {
        String path = exposurePath(exposureId);
        return exposureExchange(exposureId, send("GET", path, null, Map.of()), path, false);
    }

    public ExposureExchange replaceExposureState(String exposureId, String etag, String idempotencyKey,
            GatewayUrlExposureStateEnum state, String reason) throws IOException, InterruptedException {
        String path = exposurePath(exposureId);
        if (!matchesExposureEtag(exposureId, etag) || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._-]{1,128}")
                || reason == null || reason.isBlank() || reason.trim().length() > 1024) {
            throw new IllegalArgumentException("Invalid exposure state precondition");
        }
        var headers = Map.of("If-Match", etag, "Idempotency-Key", idempotencyKey);
        var body = Map.of("state", Objects.requireNonNull(state, "state").name(), "changeReason", reason);
        return exposureExchange(exposureId, send("PUT", path + "/state", body, headers), path, true);
    }

    private ExposureExchange exposureExchange(String exposureId, HttpResponse<String> response, String path, boolean mutation)
            throws IOException {
        String etag = response.headers().firstValue("ETag").orElse(null);
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        int status = response.statusCode();
        JsonNode body = responseBody(response);
        if (status >= 200 && status < 300) {
            if (!(status == 200 || mutation && status == 202)
                    || response.headers().allValues("ETag").size() != 1 || !matchesExposureEtag(exposureId, etag)
                    || !exposureId.equals(body.path("exposureId").textValue())
                    || !body.path("desiredGeneration").asText().matches("(0|[1-9][0-9]{0,18})")
                    || !body.path("desiredGeneration").isTextual()
                    || !"no-store".equals(response.headers().firstValue("Cache-Control").orElse(""))
                    || mutation && !response.headers().allValues("Location").equals(java.util.List.of(path))
                    || status == 202 && !"2".equals(retryAfter)) {
                throw new IOException("Exposure response did not satisfy the management API contract");
            }
        }
        return new ExposureExchange(status, etag, retryAfter, body);
    }

    private static String exposurePath(String exposureId) {
        if (exposureId == null || !exposureId.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Invalid exposure ID");
        }
        Long.parseLong(exposureId);
        return API + "/exposures/" + exposureId;
    }

    private static boolean matchesExposureEtag(String exposureId, String value) {
        return value != null && value.matches("\"exposure-" + exposureId + "-v(0|[1-9][0-9]{0,18})\"");
    }

    public record ExposureExchange(int status, String etag, String retryAfter, JsonNode body) {
    }

    JsonNode get(String path) throws IOException, InterruptedException {
        return exchange("GET", path, null);
    }

    JsonNode post(String path, Object body)
            throws IOException, InterruptedException {
        return exchange("POST", path, body);
    }

    JsonNode put(String path, Object body)
            throws IOException, InterruptedException {
        return exchange("PUT", path, body);
    }

    private JsonNode exchange(
            String method,
            String path,
            Object body) throws IOException, InterruptedException {
        var response = send(method, path, body, Map.of());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(method + " " + baseUri.resolve(path) + " returned "
                    + response.statusCode() + ": " + response.body());
        }
        return responseBody(response);
    }

    private JsonNode responseBody(HttpResponse<String> response) throws IOException {
        if (response.body() == null || response.body().isBlank()) {
            return MissingNode.getInstance();
        }
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, Object body, Map<String, String> headers)
            throws IOException, InterruptedException {
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
        var request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .method(method, publisher);
        headers.forEach(request::header);
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String draft(String groupId) {
        return API + "/yuheng-groups/" + segment(groupId) + "/draft";
    }

    private String segment(String value) {
        return encode(value, "path segment");
    }

    private String query(String value) {
        return encode(value, "query value");
    }

    private String encode(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
