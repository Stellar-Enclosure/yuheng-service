package top.egon.cola.component.yuheng.test.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.AuthorizationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BackendReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.ExposureInput;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GatewayReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GrantReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuntimeRoute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayRuleWireCompatibilityTest {

    private static final String HOSTNAME = "public.apps.example.com";
    private static final String PATH = "/api/orders";
    private static final String ROUTE_NAMESPACE = "yuheng-routes";
    private static final String BACKEND_NAMESPACE = "orders";

    private final ObjectMapper json = new ObjectMapper();
    private final GatewayHttpRouteCompiler compiler = new GatewayHttpRouteCompiler(json);

    @Test
    void frozenReleaseBindsInertRouteAndApprovedParentToTheExactBackend() throws Exception {
        var release = compiler.compile(input(GatewayProtocol.HTTP, true, draft()), binding());

        JsonNode wire = json.readTree(json.writeValueAsBytes(release.routeObject()));
        assertThat(wire.path("apiVersion").asText()).isEqualTo("gateway.networking.k8s.io/v1");
        assertThat(wire.path("kind").asText()).isEqualTo("HTTPRoute");
        assertThat(wire.at("/metadata/namespace").asText()).isEqualTo(ROUTE_NAMESPACE);
        assertThat(wire.at("/spec/parentRefs")).isEqualTo(json.createArrayNode());
        assertThat(wire.at("/spec/hostnames/0").asText()).isEqualTo(HOSTNAME);
        assertThat(wire.at("/spec/rules/0/matches/0/path/value").asText()).isEqualTo(PATH);
        assertThat(wire.at("/spec/rules/0/matches/0/method").asText()).isEqualTo("GET");
        assertThat(wire.at("/spec/rules/0/backendRefs/0").at("/port").asInt()).isEqualTo(80);
        assertThat(wire.at("/spec/rules/0/backendRefs/0").at("/name").asText()).isEqualTo("orders-public");
        assertThat(wire.at("/spec/rules/0/filters/0/requestHeaderModifier/remove"))
                .isEqualTo(json.valueToTree(List.of("x-envoy-retry-on", "x-envoy-retry-grpc-on", "x-envoy-max-retries",
                        "x-envoy-retriable-header-names", "x-envoy-retriable-status-codes",
                        "x-envoy-hedge-on-per-try-timeout", "x-envoy-upstream-rq-per-try-timeout-ms")));
        assertThat(wire.at("/spec/rules/0").has("retry")).isFalse();

        JsonNode approvedParent = release.approvedParent();
        assertThat(approvedParent.path("namespace").asText()).isEqualTo("edge-system");
        assertThat(approvedParent.path("name").asText()).isEqualTo("public-apps");
        assertThat(approvedParent.path("sectionName").asText()).isEqualTo("https-api");
        assertThat(release.snapshotEntry().at("/routeObject/name").asText()).isEqualTo(wire.at("/metadata/name").asText());
        assertThat(release.snapshotEntry().path("contentSha256").asText()).isEqualTo(compiler.contentDigest(release.canonicalContent()));
    }

    @Test
    void transportThatRequiresAnAdapterOrGatewayRetryFailsClosed() {
        assertThatThrownBy(() -> compiler.compile(input(GatewayProtocol.RPC, true, draft()), binding()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("routeCapability");
        assertThatThrownBy(() -> compiler.compile(input(GatewayProtocol.HTTP, false, draft()), binding()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("routeCapability");

        var retry = new LinkedHashMap<>(draft());
        retry.put("transportPolicy", Map.of("retryEnabled", true));
        assertThatThrownBy(() -> compiler.compile(input(GatewayProtocol.HTTP, true, retry), binding()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transportPolicy");
    }

    private static Map<String, Object> draft() {
        return Map.of("host", HOSTNAME, "httpMethod", "GET", "pathPattern", PATH,
                "accessZones", List.of("PUBLIC"), "priority", 0);
    }

    private static ExposureInput input(GatewayProtocol protocol, boolean external, Map<String, Object> draft) {
        var route = new GatewayRuntimeRoute("orders-read", "orders-get", HOSTNAME, "GET", PATH,
                Set.of(AccessZone.PUBLIC), 0, true);
        return new ExposureInput(7, 800001, "820001", 810001, 1, route, protocol, external,
                draft, List.of(), List.of());
    }

    private static PublicationBinding binding() {
        var gateway = new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true);
        var domain = new PublicationDomainVO("cluster", "public-https", "topology-1",
                List.of(new BindingVO(gateway.namespace(), gateway.name(), gateway.listenerName())), true);
        var grant = new GrantReference(BACKEND_NAMESPACE, "allow-orders-public", "grant-uid", "4",
                ROUTE_NAMESPACE, "orders-public");
        var backend = new BackendReference(BACKEND_NAMESPACE, "orders-public", "service-uid", "11", 80, 8080, 1,
                "HTTP_1_1", grant, "b".repeat(64), Set.of("pod-uid"), true);
        var authorization = new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE,
                "https://" + HOSTNAME + PATH, "1", "a".repeat(64), true, List.of());
        return new PublicationBinding(7, 800001, "orders-read", "orders-get", "prod", "logical-prod", "binding-1", 1,
                domain, gateway, ROUTE_NAMESPACE, Set.of(HOSTNAME), Set.of("GET"), Set.of(),
                List.of(backend), authorization);
    }
}
