package top.egon.cola.component.yuheng.admin.routing.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteTransportPolicyValidatorTest {
    private final GatewayRouteTransportPolicyValidator validator = new GatewayRouteTransportPolicyValidator();

    @Test
    void acceptsExactAndPrefixHttpWithNoApplicationTransportOverrides() {
        var route = route();

        assertThat(validator.validate(route, GatewayProtocol.HTTP)).isEmpty();
        route.put("pathMatchType", "PATH_PREFIX");
        route.put("transportPolicy", Map.of("retryEnabled", false, "bodyLogEnabled", false));

        assertThat(validator.validate(route, GatewayProtocol.HTTP)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"connectTimeoutMs", "transportProtocol", "retryEnabled", "bodyLogEnabled",
            "tls", "grpcRoute", "profile", "responseMode", "websocketIdleTimeoutMs"})
    void rejectsLegacyTransportOverridesForAnOtherwiseValidPublicRoute(String field) {
        var route = route();
        route.put("transportPolicy", Map.of(field, "connectTimeoutMs".equals(field) ? 1000L : true));

        assertThat(validator.validate(route, GatewayProtocol.HTTP))
                .containsExactly(new GatewayTransportValidationIssue("transportPolicy." + field, "TRANSPORT_OPTION_RETIRED",
                        "The Cilium HTTPRoute profile does not accept this application proxy option"));
    }

    @Test
    void rejectsNorthboundRpcAndExplicitRoutePriority() {
        var route = route();
        route.put("priority", 10);

        assertThat(validator.validate(route, GatewayProtocol.RPC)).extracting(GatewayTransportValidationIssue::code)
                .containsExactly("ROUTE_PROTOCOL_UNSUPPORTED", "ROUTE_PRIORITY_UNSUPPORTED");
    }

    @Test
    void reportsMissingHostAndNonStringMethodWithoutWildcardFallback() {
        var route = route();
        route.remove("host");
        route.put("httpMethod", List.of("GET"));

        assertThat(validator.validate(route, GatewayProtocol.HTTP)).containsExactly(
                new GatewayTransportValidationIssue("host", "ROUTE_HOST_REQUIRED", "Host is required"),
                new GatewayTransportValidationIssue("httpMethod", "ROUTE_METHOD_INVALID", "HTTP Method must be a string"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/orders/{id}", "/orders/**", "/orders?query=1", "/orders/%2e", "//orders"})
    void rejectsOldProxyPatternsAndAmbiguousHttpPaths(String path) {
        var route = route();
        route.put("pathPattern", path);

        assertThat(validator.validate(route, GatewayProtocol.HTTP)).singleElement()
                .satisfies(issue -> assertThat(issue.path()).isEqualTo("pathPattern"));
    }

    private Map<String, Object> route() {
        return new LinkedHashMap<>(Map.of("host", "api.example.com", "httpMethod", "POST",
                "pathPattern", "/orders", "accessZones", List.of("PUBLIC"), "priority", 0));
    }
}
