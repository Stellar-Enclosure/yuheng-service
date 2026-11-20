package top.egon.cola.component.yuheng.test.live;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayAdminTestClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsBearerAuthenticatedTypedAdminRequests() throws Exception {
        AtomicReference<RequestCapture> request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, request));
        server.start();
        GatewayAdminTestClient client = new GatewayAdminTestClient(
                baseUri(),
                "test-token"
        );

        assertThat(client.createApplication(Map.of(
                "applicationCode", "orders"
        )).required("id").asText()).isEqualTo("created-id");

        assertThat(request.get()).satisfies(capture -> {
            assertThat(capture.method()).isEqualTo("POST");
            assertThat(capture.path())
                    .isEqualTo("/api/v1/yuheng/admin/applications");
            assertThat(capture.authorization())
                    .isEqualTo("Bearer test-token");
            assertThat(capture.body()).contains("\"applicationCode\":\"orders\"");
        });

        client.runtimeConsistency("group/one");
        assertThat(request.get().path()).isEqualTo(
                "/api/v1/yuheng/admin/yuheng-groups/group%2Fone/"
                        + "runtime-consistency"
        );

        client.getDraft("group/one");
        assertThat(request.get().method()).isEqualTo("GET");
        assertThat(request.get().path()).isEqualTo(
                "/api/v1/yuheng/admin/yuheng-groups/group%2Fone/draft"
        );

        client.providerInstances(
                "test",
                "yuheng-live",
                "HTTP",
                "orders",
                "default",
                "1.0.0-live"
        );
        assertThat(request.get().query()).isEqualTo(
                "env=test&namespace=yuheng-live&protocol=HTTP"
                        + "&serviceName=orders&group=default"
                        + "&version=1.0.0-live"
        );
    }

    @Test
    void reportsMethodUriStatusAndBodyForAdminFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "denied".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        GatewayAdminTestClient client = new GatewayAdminTestClient(
                baseUri(),
                "test-token"
        );

        assertThatThrownBy(() -> client.validateDraft("group-1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("POST")
                .hasMessageContaining("403")
                .hasMessageContaining("denied");
    }

    @Test
    void exposureMethodsReturnTheActualFenceAndConvergenceOutcome() throws Exception {
        var received = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/yuheng/admin/exposures/123", exchange -> {
            var headers = exchange.getRequestHeaders();
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.set(String.join("\n", exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    headers.getFirst("Authorization"), String.valueOf(headers.getFirst("If-Match")),
                    String.valueOf(headers.getFirst("Idempotency-Key")), body));
            String generation = "GET".equals(exchange.getRequestMethod()) ? "4" : "5";
            byte[] json = ("{\"exposureId\":\"123\",\"desiredGeneration\":\"" + generation + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            String rowVersion = "GET".equals(exchange.getRequestMethod()) ? "34" : "35";
            exchange.getResponseHeaders().set("ETag", "\"exposure-123-v" + rowVersion + "\"");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("PUT".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Location", "/api/v1/yuheng/admin/exposures/123");
                exchange.getResponseHeaders().set("Retry-After", "2");
            }
            exchange.sendResponseHeaders("GET".equals(exchange.getRequestMethod()) ? 200 : 202, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        var client = new GatewayAdminTestClient(baseUri(), "fixture-token");

        var current = client.getExposure("123");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.etag()).isEqualTo("\"exposure-123-v34\"");
        assertThat(received.get()).startsWith("GET\n/api/v1/yuheng/admin/exposures/123\nBearer fixture-token");
        var next = client.replaceExposureState("123", current.etag(), "step52-closed", GatewayUrlExposureStateEnum.CLOSED, "staging closure");
        assertThat(next.status()).isEqualTo(202);
        assertThat(next.etag()).isEqualTo("\"exposure-123-v35\"");
        assertThat(next.retryAfter()).isEqualTo("2");
        assertThat(next.body().path("desiredGeneration").textValue()).isEqualTo("5");
        assertThat(received.get()).contains(
                "PUT\n/api/v1/yuheng/admin/exposures/123/state\nBearer fixture-token\n\"exposure-123-v34\"\nstep52-closed",
                "\"state\":\"CLOSED\"", "\"changeReason\":\"staging closure\"");
    }

    @Test
    void exposureMutationRejectsForeignPreconditionsAndReturnsRealStaleStatus() throws Exception {
        var callCount = new java.util.concurrent.atomic.AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            callCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"code\":\"YUHENG_EXPOSURE_PRECONDITION_FAILED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(412, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var client = new GatewayAdminTestClient(baseUri(), "fixture-token");

        assertThatThrownBy(() -> client.replaceExposureState("123", "\"exposure-999-v4\"", "step52-stale",
                GatewayUrlExposureStateEnum.CLOSED, "staging closure")).isInstanceOf(IllegalArgumentException.class);
        assertThat(callCount).hasValue(0);
        var stale = client.replaceExposureState("123", "\"exposure-123-v3\"", "step52-stale",
                GatewayUrlExposureStateEnum.CLOSED, "staging closure");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().path("code").textValue()).isEqualTo("YUHENG_EXPOSURE_PRECONDITION_FAILED");
        assertThat(callCount).hasValue(1);
    }

    private URI baseUri() {
        return URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
    }

    private void respond(
            HttpExchange exchange,
            AtomicReference<RequestCapture> capture) throws IOException {
        capture.set(new RequestCapture(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                )
        ));
        byte[] body = "{\"id\":\"created-id\"}".getBytes(
                StandardCharsets.UTF_8
        );
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record RequestCapture(
            String method,
            String path,
            String query,
            String authorization,
            String body
    ) {
    }
}
