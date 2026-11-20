package top.egon.cola.component.yuheng.test.live;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum.CLOSED;
import static top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum.OPEN;
import static top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum.WITHDRAWN;

/** Runs against an operator-provisioned Cilium fixture and its authoritative Yuheng observer. */
class GatewayExposureConvergenceLiveIT {

    private static final String PREFIX = "yuheng.live.cilium.exposure.";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @Test
    @EnabledIfSystemProperty(named = PREFIX + "enabled", matches = "true")
    void closedGenerationFencesStaleOpenAndRejectsNewPublicRequests() throws Exception {
        var fixture = ReviewedExposure.load("reversible");
        var initial = fixture.requireOpen();

        String routeUid = routeUid(initial.body());
        var closes = racedClose(fixture, initial.etag());
        var close = closes.stream().filter(reply -> reply.status() == 202).findFirst()
                .orElseThrow(() -> new AssertionError("Competing commands did not close the approved fixture"));
        String generation = close.body().path("desiredGeneration").textValue();
        try {
            assertThat(closes).extracting(GatewayAdminTestClient.ExposureExchange::status)
                    .containsExactlyInAnyOrder(202, 412);
            var stale = fixture.replace(initial.etag(), OPEN, UUID.randomUUID().toString());
            assertThat(stale.status()).isEqualTo(412);

            fixture.awaitSynced(CLOSED, generation, routeUid);
            fixture.expectPublic(404);
        } finally {
            fixture.reopen(generation, routeUid);
            fixture.expectPublic(200);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = PREFIX + "withdrawal-enabled", matches = "true")
    void terminalWithdrawalCannotBeReopenedOnTheDedicatedFixture() throws Exception {
        var fixture = ReviewedExposure.load("disposable");
        var initial = fixture.requireOpen();
        String key = UUID.randomUUID().toString();
        var withdraw = fixture.replace(initial.etag(), WITHDRAWN, key);
        assertThat(withdraw.status()).isEqualTo(202);
        String generation = withdraw.body().path("desiredGeneration").textValue();
        String routeUid = routeUid(initial.body());
        var converged = fixture.awaitSynced(WITHDRAWN, generation, routeUid);
        assertThat(converged.body().path("terminal").booleanValue()).isTrue();
        fixture.expectPublic(404);

        var replay = fixture.replace(initial.etag(), WITHDRAWN, key);
        assertThat(replay.status()).isEqualTo(withdraw.status());
        assertThat(replay.body().path("desiredGeneration").textValue()).isEqualTo(generation);
        assertThat(fixture.replace(converged.etag(), OPEN, UUID.randomUUID().toString()).status()).isEqualTo(409);
        fixture.awaitSynced(WITHDRAWN, generation, routeUid);
        fixture.expectPublic(404);
    }

    @Test
    @EnabledIfSystemProperty(named = PREFIX + "partition-enabled", matches = "true")
    void reachableStaleTargetKeepsClosedGenerationUnconfirmed() throws Exception {
        var fixture = ReviewedExposure.load("partitioned");
        var staleTarget = fixture.targets().stream()
                .filter(target -> target.id().equals(ReviewedExposure.property("partitioned.stale-target-id")))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Stale target must be in the reviewed inventory"));
        String generation = ReviewedExposure.property("partitioned.generation");
        assertThat(generation).matches("[1-9][0-9]{0,18}");
        String bearer = ReviewedExposure.token(fixture.bearerFile());
        for (int check = 0; check < 3; check++) {
            assertThat(staleTarget.status(fixture.publicUrl(), bearer)).isEqualTo(200);
            var observation = fixture.admin().getExposure(fixture.id());
            assertThat(observation.status()).isEqualTo(200);
            fixture.requireApprovedExposure(observation.body());
            assertThat(observation.body().path("desiredState").textValue()).isEqualTo(CLOSED.wireValue());
            assertThat(observation.body().path("desiredGeneration").textValue()).isEqualTo(generation);
            assertThat(observation.body().path("reconcileStatus").textValue()).isIn("PENDING", "DEGRADED");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = PREFIX + "websocket-drain-enabled", matches = "true")
    void detachRejectsANewUpgradeWhileTheEstablishedFlowDrains() throws Exception {
        var fixture = ReviewedExposure.load("websocket");
        var initial = fixture.currentOpen();
        String uid = routeUid(initial.body());
        URI wss = URI.create("wss" + fixture.publicUrl().toASCIIString().substring("https".length()));
        var headers = Map.of("Authorization", "Bearer " + ReviewedExposure.token(fixture.bearerFile()));
        var generation = new AtomicReference<String>();
        byte[] binary = {0x11, 0x22};
        byte[] ping = {0x33};
        try (var client = new GatewayWebSocketTestClient()) {
            try {
                var transcript = client.exchange(wss, headers, "before-close", binary, ping, REQUEST_TIMEOUT, () -> {
                    var closed = fixture.replace(initial.etag(), CLOSED, UUID.randomUUID().toString());
                    assertThat(closed.status()).isEqualTo(202);
                    generation.set(closed.body().path("desiredGeneration").textValue());
                    fixture.awaitSynced(CLOSED, generation.get(), uid);
                    fixture.expectPublic(404);
                    assertThatThrownBy(() -> client.exchange(wss, headers, "new-flow", binary, ping, REQUEST_TIMEOUT))
                            .hasCauseInstanceOf(WebSocketHandshakeException.class)
                            .satisfies(failure -> assertThat(((WebSocketHandshakeException) failure.getCause()).getResponse()
                                    .statusCode()).isEqualTo(404));
                });
                assertThat(transcript.textFrames()).contains("before-close");
                assertThat(transcript.binaryFrames()).anyMatch(frame -> Arrays.equals(frame, binary));
                assertThat(transcript.closeCode()).isEqualTo(1000);
            } finally {
                if (generation.get() != null) {
                    fixture.reopen(generation.get(), uid);
                }
            }
        }
    }

    private static List<GatewayAdminTestClient.ExposureExchange> racedClose(ReviewedExposure fixture, String etag) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            List<java.util.concurrent.Future<GatewayAdminTestClient.ExposureExchange>> replies = new ArrayList<>();
            for (int request = 0; request < 2; request++) {
                replies.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Competing CLOSED commands did not start");
                    }
                    return fixture.replace(etag, CLOSED, UUID.randomUUID().toString());
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(replies.get(0).get(20, TimeUnit.SECONDS), replies.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private static String routeUid(JsonNode exposure) {
        String uid = exposure.path("kubernetesRoute").path("uid").textValue();
        assertThat(uid).isNotBlank();
        return uid;
    }

    static List<ListenerTargetVO> approvedTargets(JsonNode inventory) {
        if (!inventory.isArray() || inventory.size() < 2 || inventory.size() > 128) {
            throw new IllegalArgumentException("Expected the complete reviewed serving target inventory");
        }
        var targets = new ArrayList<ListenerTargetVO>();
        var identifiers = new HashSet<String>();
        var addresses = new HashSet<InetAddress>();
        for (JsonNode item : inventory) {
            String id = item.path("id").asText();
            String ip = item.path("ip").asText();
            var port = item.path("port");
            if (!item.isObject() || item.size() != 3 || !item.path("id").isTextual() || !item.path("ip").isTextual()
                    || !id.matches("[a-z0-9][a-z0-9-]{0,62}") || !identifiers.add(id)
                    || !ip.matches("[0-9A-Fa-f:.]{1,45}") || !port.isIntegralNumber() || !port.canConvertToInt()
                    || port.intValue() < 1 || port.intValue() > 65_535) {
                throw new IllegalArgumentException("Invalid reviewed listener target");
            }
            InetAddress address = InetAddress.ofLiteral(ip);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress() || !addresses.add(address)) {
                throw new IllegalArgumentException("Expected a unique routable listener address");
            }
            targets.add(new ListenerTargetVO(id, address, port.intValue()));
        }
        return List.copyOf(targets);
    }

    record ListenerTargetVO(String id, InetAddress address, int port) {
        int status(URI url, String bearer) throws IOException {
            int timeoutMillis = Math.toIntExact(REQUEST_TIMEOUT.toMillis());
            try (var tcp = new Socket(Proxy.NO_PROXY)) {
                tcp.connect(new InetSocketAddress(address, port), timeoutMillis);
                var ssl = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (var connection = (SSLSocket) ssl.createSocket(tcp, url.getHost(), port, true)) {
                    var parameters = connection.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(List.of(new SNIHostName(url.getHost())));
                    connection.setSSLParameters(parameters);
                    connection.setSoTimeout(timeoutMillis);
                    connection.startHandshake();
                    String path = URI.create(url.toASCIIString()).getRawPath();
                    String request = "GET " + path + " HTTP/1.1\r\nHost: " + url.getHost()
                            + "\r\nAuthorization: Bearer " + bearer + "\r\nConnection: close\r\n\r\n";
                    connection.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    var input = connection.getInputStream();
                    var status = new String(input.readNBytes(12), StandardCharsets.US_ASCII);
                    int separator = input.read();
                    if (!status.matches("HTTP/1\\.[01] [1-5][0-9]{2}") || separator != ' ' && separator != '\r') {
                        throw new IOException("Unexpected HTTP response from the reviewed listener");
                    }
                    return Integer.parseInt(status.substring(9));
                }
            }
        }
    }

    private record ReviewedExposure(String id, URI publicUrl, Path bearerFile, GatewayAdminTestClient admin,
                                    List<ListenerTargetVO> targets) {
        static ReviewedExposure load(String use) throws Exception {
            URI adminOrigin = GatewayAdminTestClient.reviewedServiceOrigin(property("admin-origin"));
            URI publicUrl = GatewayLiveEnvironment.northbound(property(use + ".public-url"));
            if (publicUrl.getRawPath().isEmpty() || "/".equals(publicUrl.getRawPath())) {
                throw new IllegalArgumentException("Provide the exact approved fixture path");
            }
            String id = property(use + ".id");
            assertThat(id).matches("[1-9][0-9]{0,18}");
            byte[] inventory = UnifiedIdentityLiveClient.protectedBytes(Path.of(property(use + ".targets-file")), null, 32_768);
            var targets = approvedTargets(UnifiedIdentityLiveClient.strictJson().readTree(inventory));
            return new ReviewedExposure(id, publicUrl, Path.of(property("public-token-file")),
                    new GatewayAdminTestClient(adminOrigin, token(Path.of(property("admin-token-file")))), targets);
        }

        private static String property(String name) {
            String value = System.getProperty(PREFIX + name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing reviewed Cilium fixture property " + name);
            }
            return value;
        }

        private static String token(Path file) throws Exception {
            byte[] bytes = UnifiedIdentityLiveClient.protectedBytes(file, null, 16_384);
            try {
                var value = new String(bytes, StandardCharsets.UTF_8).strip();
                if (!value.matches("[A-Za-z0-9._~+/=-]+")) {
                    throw new IllegalArgumentException("Invalid OAuth bearer fixture file");
                }
                return value;
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }

        GatewayAdminTestClient.ExposureExchange requireOpen() throws Exception {
            var synced = currentOpen();
            expectPublic(200);
            return synced;
        }

        GatewayAdminTestClient.ExposureExchange currentOpen() throws Exception {
            var initial = admin.getExposure(id);
            assertThat(initial.status()).isEqualTo(200);
            requireApprovedRoute(initial.body());
            return awaitSynced(OPEN, initial.body().path("desiredGeneration").textValue(), routeUid(initial.body()));
        }

        void reopen(String closedGeneration, String expectedUid) throws Exception {
            var current = admin.getExposure(id);
            assertThat(current.status()).isEqualTo(200);
            requireApprovedExposure(current.body());
            assertThat(current.body().path("desiredGeneration").textValue()).isEqualTo(closedGeneration);
            assertThat(current.body().path("desiredState").textValue()).isEqualTo(CLOSED.wireValue());
            var reopened = replace(current.etag(), OPEN, UUID.randomUUID().toString());
            assertThat(reopened.status()).isEqualTo(202);
            awaitSynced(OPEN, reopened.body().path("desiredGeneration").textValue(), expectedUid);
        }

        void requireApprovedExposure(JsonNode body) {
            assertThat(body.path("exposureId").textValue()).isEqualTo(id);
            assertThat(body.path("method").textValue()).isEqualTo("GET");
            assertThat(body.path("url").textValue()).isEqualTo(publicUrl.toASCIIString());
        }

        void requireApprovedRoute(JsonNode body) {
            requireApprovedExposure(body);
            var route = body.path("kubernetesRoute");
            assertThat(route.path("name").textValue()).isEqualTo("yuheng-exposure-" + id);
            assertThat(route.path("namespace").textValue()).isEqualTo("yuheng-routes");
            assertThat(route.path("kind").textValue()).isEqualTo("HTTPRoute");
        }

        GatewayAdminTestClient.ExposureExchange replace(String etag, GatewayUrlExposureStateEnum target, String key) throws Exception {
            return admin.replaceExposureState(id, etag, key, target, "Cilium exposure acceptance fixture " + target.wireValue());
        }

        GatewayAdminTestClient.ExposureExchange awaitSynced(GatewayUrlExposureStateEnum target, String generation,
                String expectedUid) throws Exception {
            assertThat(generation).matches("[1-9][0-9]{0,18}");
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (System.nanoTime() < deadline) {
                var observed = admin.getExposure(id);
                assertThat(observed.status()).isEqualTo(200);
                JsonNode body = observed.body();
                requireApprovedExposure(body);
                assertThat(body.path("desiredState").textValue()).isEqualTo(target.wireValue());
                assertThat(body.path("desiredGeneration").textValue()).isEqualTo(generation);
                if ("SYNCED".equals(body.path("reconcileStatus").textValue())) {
                    requireApprovedRoute(body);
                    assertThat(routeUid(body)).isEqualTo(expectedUid);
                    assertThat(body.path("observedState").textValue()).isEqualTo(target.wireValue());
                    assertThat(body.path("observedGeneration").textValue()).isEqualTo(generation);
                    var route = body.path("kubernetesRoute");
                    assertThat(route.path("attached").isBoolean()).isTrue();
                    assertThat(route.path("attached").booleanValue()).isEqualTo(target == OPEN);
                    if (target == OPEN) {
                        assertThat(route.path("controllerName").textValue()).isEqualTo(GatewayHttpRouteCompiler.CONTROLLER_NAME);
                        assertThat(route.path("metadataGeneration").textValue()).isNotBlank()
                                .isEqualTo(route.path("conditionObservedGeneration").textValue());
                        requireConditions(body, generation, "Accepted", "ResolvedRefs", "Programmed", "DataplaneConverged");
                    } else {
                        requireConditions(body, generation, "Detached", "NoAlternateRoute", "DataplaneConverged");
                    }
                    return observed;
                }
                Thread.sleep(Duration.ofSeconds(1));
            }
            throw new AssertionError("No current observer receipt confirmed exposure generation " + generation);
        }

        void expectPublic(int status) throws Exception {
            String bearer = token(bearerFile);
            try (var http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(REQUEST_TIMEOUT).build()) {
                var request = HttpRequest.newBuilder(publicUrl).timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer " + bearer).GET().build();
                assertThat(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(status);
            }
            for (var target : targets) {
                assertThat(target.status(publicUrl, bearer)).as("reviewed listener %s", target.id()).isEqualTo(status);
            }
        }

        private static void requireConditions(JsonNode body, String generation, String... expected) {
            assertThat(body.path("conditions").isArray()).isTrue();
            for (String type : List.of(expected)) {
                assertThat(body.path("conditions").valueStream()
                        .filter(node -> type.equals(node.path("type").textValue())).toList()).singleElement()
                        .satisfies(condition -> {
                            assertThat(condition.path("status").textValue()).isEqualTo("True");
                            assertThat(condition.path("observedGeneration").textValue()).isEqualTo(generation);
                        });
            }
        }
    }
}
