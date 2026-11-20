package top.egon.cola.component.yuheng.test.live;

import com.fasterxml.jackson.databind.JsonNode;
import top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

/** Client-only access to the separately provisioned Cilium acceptance environment. */
final class GatewayLiveEnvironment implements AutoCloseable {

    static final String ENABLED = "yuheng.live.cilium.test";
    private static final String PREFIX = "yuheng.live.cilium.";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_2).followRedirects(HttpClient.Redirect.NEVER).build();

    static String required(String name) {
        String value = System.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing approved Cilium fixture property " + name);
        }
        return value;
    }

    static URI northbound(String value) {
        URI uri = URI.create(value);
        var host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || host.endsWith(".svc.cluster.local")
                || !host.matches("[a-z](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                || uri.getPort() != -1 && uri.getPort() != 443
                || uri.getRawPath().isEmpty() || !clean(uri)) {
            throw new IllegalArgumentException("Expected an explicit public Cilium HTTPS endpoint");
        }
        return uri;
    }

    static URI eastWest(String value) {
        URI uri = URI.create(value);
        var host = uri.getHost();
        if (!"http".equals(uri.getScheme()) || host == null
                || !host.matches("[a-z](?:[a-z0-9-]*[a-z0-9])?\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.svc\\.cluster\\.local")
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getPort() == 19099
                || !uri.getRawPath().isEmpty() || !clean(uri)) {
            throw new IllegalArgumentException("Expected a Kubernetes Service DNS name and its approved port");
        }
        String service = host.substring(0, host.indexOf('.'));
        if (service.equals("stable") || service.equals("canary") || service.endsWith("-stable") || service.endsWith("-canary")) {
            throw new IllegalArgumentException("Expected a stable Service front door");
        }
        return uri;
    }

    private static boolean clean(URI uri) {
        return uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.equals(uri.normalize());
    }

    static String approvedToken(String property) throws Exception {
        byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(Path.of(required(property)), null, 16_384);
        try {
            String value = new String(bytes, StandardCharsets.UTF_8).strip();
            if (!value.matches("[A-Za-z0-9._~+/=-]+")) {
                throw new IllegalArgumentException("Invalid reviewed OAuth fixture credential");
            }
            return value;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    Probe probe(URI uri, String method, String bearer) throws Exception {
        return probe(uri, method, bearer, null);
    }

    Probe probe(URI uri, String method, String bearer, String traceId) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).method(method, HttpRequest.BodyPublishers.noBody());
        if ("http".equals(uri.getScheme())) {
            request.version(HttpClient.Version.HTTP_1_1);
        }
        if (traceId != null) {
            if (!traceId.matches("[0-9a-f]{32}") || traceId.equals("0".repeat(32))) {
                throw new IllegalArgumentException("Expected a W3C trace identifier");
            }
            request.header("traceparent", "00-" + traceId + "-0000000000000001-01");
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        var body = new ByteArrayOutputStream();
        var result = http.send(request.build(), UnifiedIdentityLiveClient.boundedBody(body));
        return new Probe(result.statusCode(), result.version(), result.headers(), body.toString(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        http.close();
    }

    record Probe(int status, HttpClient.Version version, HttpHeaders headers, String body) {
        JsonNode json() throws Exception {
            return UnifiedIdentityLiveClient.strictJson().readTree(body);
        }
    }
}
