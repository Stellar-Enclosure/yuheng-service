package top.egon.cola.component.yuheng.test.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import top.egon.cola.component.yuheng.test.tianquan.shoubing.UnifiedIdentityLiveClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Black-box probes of the Cilium northbound listener and the in-cluster RPC driver fixture. */
final class GatewayLiveTopologyIT {

    static final int GAMMA_SAMPLE_COUNT = 10_000;

    void verifyHttpProvidersLifecycle() throws Exception {
        String bearer = GatewayLiveEnvironment.approvedToken("public-token-file");
        try (var environment = new GatewayLiveEnvironment()) {
            for (String framework : List.of("mvc", "webflux")) {
                URI publicUrl = GatewayLiveEnvironment.northbound(GatewayLiveEnvironment.required(framework + "-provider-url"));
                assertThat(publicUrl.getRawPath()).matches("/api/providers/[A-Za-z0-9_-]{1,64}");
                var result = environment.probe(publicUrl, "GET", bearer);
                assertThat(result.status()).isEqualTo(200);
                assertThat(result.version()).isEqualTo(HttpClient.Version.HTTP_2);
                var identity = result.json();
                assertThat(identity.path("framework").textValue()).isEqualTo(framework);
                assertThat(identity.path("requestId").textValue()).isEqualTo(publicUrl.getRawPath().substring("/api/providers/".length()));
                assertThat(identity.path("providerId").textValue()).isNotBlank();

                assertThat(environment.probe(publicUrl, "GET", null).status()).isEqualTo(401);
                assertThat(environment.probe(publicUrl, "POST", bearer).status()).isEqualTo(404);
                assertThat(environment.probe(publicUrl.resolve(publicUrl.getRawPath() + "/"), "GET", bearer).status()).isEqualTo(404);
                assertThat(environment.probe(publicUrl.resolve(publicUrl.getRawPath() + "/unpublished"), "GET", bearer).status()).isEqualTo(404);
            }
        }
    }

    void verifyRpcServiceLifecycle() throws Exception {
        URI single = GatewayLiveEnvironment.eastWest(GatewayLiveEnvironment.required("rpc-driver-origin"));
        var comparisons = Arrays.stream(GatewayLiveEnvironment.required("rpc-driver-comparison-origins").split(",", -1))
                .map(GatewayLiveEnvironment::eastWest).toList();
        assertThat(comparisons).hasSizeBetween(2, 16).doesNotHaveDuplicates().doesNotContain(single);
        String phase = GatewayLiveEnvironment.required("gamma-phase");
        boolean weighted = isWeighted(phase);
        String run = GatewayLiveEnvironment.required("gamma-run-id");
        String generation = GatewayLiveEnvironment.required("gamma-generation");
        assertThat(run).matches("[A-Za-z0-9_-]{1,64}");
        assertThat(generation).matches("[1-9][0-9]{0,18}");
        String bearer = GatewayLiveEnvironment.approvedToken("rpc-driver-token-file");
        var stable = providerIds(GatewayLiveEnvironment.required("rpc-stable-provider"));
        var canary = providerIds(GatewayLiveEnvironment.required("rpc-canary-provider"));
        assertThat(stable).doesNotContainAnyElementsOf(canary);

        verifyRpcWindow(List.of(single), "single", phase, generation, run, bearer, stable, canary, weighted);
        verifyRpcWindow(comparisons, "comparison", phase, generation, run, bearer, stable, canary, weighted);
    }

    private void verifyRpcWindow(List<URI> drivers, String mode, String phase, String generation, String run, String bearer,
            Set<String> stable, Set<String> canary, boolean weighted) throws Exception {
        Map<String, Integer> counts = new TreeMap<>();
        Map<Integer, Integer> failures = new TreeMap<>();
        var invocations = new HashSet<String>();
        String samplePrefix = "gamma-" + run + "-" + phase + "-" + mode;
        try (var environment = new GatewayLiveEnvironment()) {
            for (int sample = 0; sample < GAMMA_SAMPLE_COUNT; sample++) {
                URI driver = drivers.get(sample % drivers.size());
                String message = samplePrefix + "-" + sample;
                var result = environment.probe(driver.resolve("/test/rpc/echo?message=" + message), "GET", bearer);
                if (result.status() != 200) {
                    failures.merge(result.status(), 1, Integer::sum);
                    continue;
                }
                var response = result.json();
                assertThat(response.path("message").textValue()).isEqualTo(message);
                String provider = response.path("providerId").textValue();
                assertThat(stable.contains(provider) || canary.contains(provider)).as("approved provider").isTrue();
                String callId = response.path("invocationId").textValue();
                assertThat(callId).isNotBlank();
                assertThat(invocations.add(callId)).as("distinct invocation ID per sample").isTrue();
                counts.merge(provider, 1, Integer::sum);
            }
            reportGammaWindow(run, phase, generation, "grpc-" + mode, "Echo", stable, canary, counts, failures);

            for (int sample = 0; sample < 100; sample++) {
                URI driver = drivers.get(sample % drivers.size());
                String orderId = samplePrefix + "-order-" + sample;
                var order = environment.probe(driver.resolve("/test/rpc/orders?orderId=" + orderId), "GET", bearer);
                assertThat(order.status()).isEqualTo(200);
                assertThat(order.json().path("providerId").textValue()).isIn(stable);
                assertThat(order.json().path("orderId").textValue()).isEqualTo(orderId);
            }
            if (weighted) {
                URI driver = drivers.getFirst();
                assertThat(environment.probe(driver.resolve("/test/rpc/fail?code=reviewed"), "GET", bearer).status()).isEqualTo(500);
                assertThat(environment.probe(driver.resolve("/test/rpc/slow?orderId=deadline&delayMillis=10000"), "GET", bearer).status()).isEqualTo(500);
            }
        }
    }

    void verifyHttpWeightLifecycle() throws Exception {
        String phase = GatewayLiveEnvironment.required("gamma-phase");
        isWeighted(phase);
        String run = GatewayLiveEnvironment.required("gamma-run-id");
        assertThat(run).matches("[A-Za-z0-9_-]{1,64}");
        var north = GatewayLiveEnvironment.northbound(GatewayLiveEnvironment.required("gamma-http-north-url"));
        assertThat(north.getRawPath()).matches("/api/providers/[A-Za-z0-9_-]{1,64}");
        var internal = GatewayLiveEnvironment.eastWest(GatewayLiveEnvironment.required("gamma-http-internal-origin"))
                .resolve(north.getRawPath());
        verifyHttpWeights(internal, "internal-http", HttpClient.Version.HTTP_1_1, phase, run);
        verifyHttpWeights(north, "north-http", HttpClient.Version.HTTP_2, phase, run);
    }

    private void verifyHttpWeights(URI url, String role, HttpClient.Version version, String phase, String run) throws Exception {
        String bearer = GatewayLiveEnvironment.approvedToken("gamma-" + role + "-token-file");
        String generation = GatewayLiveEnvironment.required("gamma-" + role + "-generation");
        assertThat(generation).matches("[1-9][0-9]{0,18}");
        var stable = providerIds(GatewayLiveEnvironment.required("gamma-" + role + "-stable-providers"));
        var canary = providerIds(GatewayLiveEnvironment.required("gamma-" + role + "-canary-providers"));
        assertThat(stable).doesNotContainAnyElementsOf(canary);
        try (var first = new GatewayLiveEnvironment(); var second = new GatewayLiveEnvironment()) {
            sampleHttp(url, role, version, phase, generation, run, bearer, stable, canary, List.of(first));
            sampleHttp(url, role, version, phase, generation, run, bearer, stable, canary, List.of(first, second));
        }
    }

    private void sampleHttp(URI url, String role, HttpClient.Version version, String phase, String generation,
            String run, String bearer, Set<String> stable, Set<String> canary, List<GatewayLiveEnvironment> clients) throws Exception {
        String mode = clients.size() == 1 ? "single" : "comparison";
        String samplePrefix = String.join("-", "gamma", run, phase, role, mode);
        String requestId = url.getRawPath().substring("/api/providers/".length());
        Map<String, Integer> counts = new TreeMap<>();
        Map<Integer, Integer> failures = new TreeMap<>();
        for (int sample = 0; sample < GAMMA_SAMPLE_COUNT; sample++) {
            String traceId = UnifiedIdentityLiveClient.sha256((samplePrefix + '-' + sample).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
            var result = clients.get(sample % clients.size()).probe(url, "GET", bearer, traceId);
            assertThat(result.version()).isEqualTo(version);
            if (result.status() != 200) {
                failures.merge(result.status(), 1, Integer::sum);
                continue;
            }
            var response = result.json();
            assertThat(response.path("requestId").textValue()).isEqualTo(requestId);
            String provider = response.path("providerId").textValue();
            assertThat(stable.contains(provider) || canary.contains(provider)).as("approved HTTP provider").isTrue();
            counts.merge(provider, 1, Integer::sum);
        }
        reportGammaWindow(run, phase, generation, role + '-' + mode, "GET", stable, canary, counts, failures);
    }

    private void reportGammaWindow(String run, String phase, String generation, String mode, String method, Set<String> stable,
            Set<String> canary, Map<String, Integer> counts, Map<Integer, Integer> failures) {
        int stableCount = stable.stream().mapToInt(id -> counts.getOrDefault(id, 0)).sum();
        int canaryCount = canary.stream().mapToInt(id -> counts.getOrDefault(id, 0)).sum();
        int failed = failures.values().stream().mapToInt(Integer::intValue).sum();
        System.out.printf(Locale.ROOT, "GAMMA run=%s phase=%s generation=%s mode=%s method=%s attempts=%d stable=%d canary=%d providers=%s httpFailures=%s%n",
                run, phase, generation, mode, method, GAMMA_SAMPLE_COUNT, stableCount, canaryCount, counts, failures);
        var window = requireGammaWindow(phase, GAMMA_SAMPLE_COUNT, stableCount, canaryCount, failed);
        System.out.printf(Locale.ROOT, "GAMMA run=%s phase=%s generation=%s mode=%s canary95=[%.8f,%.8f]%n",
                run, phase, generation, mode, window.canaryLower95(), window.canaryUpper95());
    }

    static Set<String> providerIds(String value) {
        Set<String> ids = new HashSet<>();
        for (String id : value.split(",", -1)) {
            if (!id.matches("[a-z0-9][a-z0-9-]{0,62}") || !ids.add(id)) {
                throw new IllegalArgumentException("Expected distinct reviewed provider IDs");
            }
        }
        return Set.copyOf(ids);
    }

    private static boolean isWeighted(String phase) {
        return switch (phase) {
            case "weighted" -> true;
            case "stable-before", "stable-after" -> false;
            default -> throw new IllegalArgumentException("Unrecognized GAMMA sampling phase");
        };
    }

    static GammaWindowVO requireGammaWindow(String phase, int total, int stable, int canary, int failed) {
        boolean weighted = isWeighted(phase);
        assertThat(total).isEqualTo(GAMMA_SAMPLE_COUNT);
        assertThat(stable).isBetween(0, total);
        assertThat(canary).isBetween(0, total);
        assertThat(stable + canary + failed).isEqualTo(total);
        assertThat(failed).as("failed samples").isZero();
        if (weighted) {
            requireGammaWeight("stable", "canary", total, Map.of("stable", stable, "canary", canary));
        } else {
            assertThat(canary).as("canary responses in a 100/0 window").isZero();
        }
        double proportion = (double) canary / total;
        double z = 1.95996398454;
        double adjustment = z * z / total;
        double center = (proportion + adjustment / 2) / (1 + adjustment);
        double margin = z * Math.sqrt((proportion * (1 - proportion) + adjustment / 4) / total) / (1 + adjustment);
        return new GammaWindowVO(canary == 0 ? 0 : Math.max(0, center - margin), Math.min(1, center + margin));
    }

    record GammaWindowVO(double canaryLower95, double canaryUpper95) { }

    static void requireGammaWeight(String stable, String canary, int total, Map<String, Integer> counts) {
        assertThat(total).isEqualTo(GAMMA_SAMPLE_COUNT);
        assertThat(stable).isNotEqualTo(canary);
        assertThat(counts.keySet()).isEqualTo(Set.of(stable, canary));
        assertThat(counts.values()).allMatch(count -> count > 0);
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(total);
        assertThat((double) counts.get(canary) / total).isBetween(0.04, 0.06);
    }
}
