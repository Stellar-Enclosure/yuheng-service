package top.egon.cola.component.yuheng.edge;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory.NameConfig;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.filter.EdgeResourceAuthorizationGatewayFilterFactory;
import top.egon.cola.component.yuheng.edge.filter.TrustedIdentityHeaderFilter;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityStateService;
import static org.assertj.core.api.Assertions.*;

class EdgeResourceAuthorizationContractTest {
    @TempDir Path directory;

    @Test void verifiedBearerIsForwardedToOnlyConfiguredAudienceAndNoCookieOrIdentityHeadersEscape() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var config = new NameConfig(); config.setName("app-a");
        var filter = new EdgeResourceAuthorizationGatewayFilterFactory(fixture.catalog).apply(config);
        var guard = new TrustedIdentityHeaderFilter();
        for (String path : new String[] {"/api/example/value", "/apps/app-a/api/example/value"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(EdgeSecurityFixture.ORIGIN + path)
                    .header("Cookie", "__Secure-egon_access_app-a=access.jwt.value")
                    .header("tenant_id", "forged").header("x-egon-user-id", "forged").header("Authorization", "Bearer untrusted"));
            exchange.getAttributes().put(EdgeIdentityStateService.ACCESS_ATTRIBUTE, fixture.access());
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("https://resource.example", "app-a"));
            var reached = new AtomicReference<ServerWebExchange>();
            filter.filter(exchange, accepted -> guard.filter(accepted, forwarded -> {
                reached.set(forwarded); return Mono.empty();
            })).block();
            assertThat(reached.get()).isNotNull();
            assertThat(reached.get().getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Bearer access.jwt.value");
            assertThat(reached.get().getRequest().getHeaders().asMultiValueMap()).doesNotContainKeys("Cookie", "tenant_id", "x-egon-user-id");
        }
    }

    @Test void wrongAudienceRouteOrMissingProofFailsClosed() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        var config = new NameConfig(); config.setName("app-a");
        var filter = new EdgeResourceAuthorizationGatewayFilterFactory(fixture.catalog).apply(config);
        for (String uri : new String[] {"https://other.example", "https://resource.example"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/example/value"));
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route(uri, "app-a"));
            var reached = new AtomicReference<ServerWebExchange>();
            filter.filter(exchange, forwarded -> { reached.set(forwarded); return Mono.empty(); }).block();
            assertThat(reached.get()).isNull();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(uri.contains("other") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        }
    }

    @Test void applicationSnapshotRouteRemainsBoundToItsOwnBrowserAliasAndAudience() throws Exception {
        var fixture = new EdgeSecurityFixture(directory);
        String browserPath = "/apps/app-a/api/v1/authorization/effective-permissions";
        fixture.properties.getResources().get("app-a").setRequestPaths(java.util.List.of(browserPath));
        var config = new NameConfig(); config.setName("app-a");
        var filter = new EdgeResourceAuthorizationGatewayFilterFactory(fixture.catalog).apply(config);
        for (String path : java.util.List.of(browserPath, "/api/v1/authorization/effective-permissions",
                "/apps/other/api/v1/authorization/effective-permissions")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            exchange.getAttributes().put(EdgeIdentityStateService.ACCESS_ATTRIBUTE, fixture.access());
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("https://resource.example", "app-a"));
            var reached = new AtomicReference<ServerWebExchange>();
            filter.filter(exchange, forwarded -> { reached.set(forwarded); return Mono.empty(); }).block();
            assertThat(reached.get() != null).isEqualTo(path.equals(browserPath));
        }
        assertThatThrownBy(() -> fixture.catalog.aliasForDirectPath("/api/v1/authorization/effective-permissions"))
                .hasMessageContaining("EDGE_APPLICATION_UNKNOWN");
    }

    private Route route(String uri, String alias) {
        return Route.async().id("resource-a").uri(uri).metadata("edge-app-alias", alias).predicate(exchange -> true).build();
    }
}
