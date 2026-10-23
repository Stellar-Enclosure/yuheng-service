package top.egon.cola.component.yuheng.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthProperties;
import top.egon.cola.component.yuheng.edge.config.EdgeSecurityConfiguration;
import top.egon.cola.component.yuheng.edge.filter.TrustedIdentityHeaderFilter;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeSessionBindingComponent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TianshuPublicI18nRouteTest {
    private static final String PUBLIC_PATH = "/api/v1/tianshu/i18n/public/bundle";
    private final EdgeSessionBindingComponent bindings = mock(EdgeSessionBindingComponent.class);
    private final TrustedIdentityHeaderFilter filter = new TrustedIdentityHeaderFilter(new ObjectMapper(), bindings);

    @Test void configuredPublicRouteMatchesOnlyExactGetInBothProfiles() {
        for (String profile : List.of("application.yml", "application-local.yml")) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource("src/main/resources/" + profile));
            var properties = yaml.getObject();
            String prefix = null;
            for (int index = 0; properties.containsKey(GatewayProperties.PREFIX + ".routes[" + index + "].id"); index++) {
                String candidate = GatewayProperties.PREFIX + ".routes[" + index + "]";
                if ("tianshu-public-i18n".equals(properties.getProperty(candidate + ".id"))) prefix = candidate;
            }
            assertThat(prefix).isNotNull();
            var path = new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config()
                    .setPatterns(List.of(properties.getProperty(prefix + ".predicates[0].args.patterns")))
                    .setMatchTrailingSlash(Boolean.parseBoolean(properties.getProperty(prefix + ".predicates[0].args.matchTrailingSlash"))));
            var methodConfig = new MethodRoutePredicateFactory.Config();
            methodConfig.setMethods(HttpMethod.valueOf(properties.getProperty(prefix + ".predicates[1]").substring("Method=".length())));
            var method = new MethodRoutePredicateFactory().apply(methodConfig);
            assertThat(path.and(method).test(exchange(HttpMethod.GET, PUBLIC_PATH))).isTrue();
            for (HttpMethod denied : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD)) {
                assertThat(path.and(method).test(exchange(denied, PUBLIC_PATH))).isFalse();
            }
            for (String denied : List.of(PUBLIC_PATH + "/", PUBLIC_PATH + "/extra", "/api/v1/tianshu/i18n/admin/bundle",
                    "/api/v1/tianshu/i18n/admin/bootstrap", "/api/v1/tianshu/i18n/bundle")) {
                assertThat(path.and(method).test(exchange(HttpMethod.GET, denied))).isFalse();
            }
        }
    }

    @Test void anonymousPublicGetPassesApiSecurityAndGlobalFilterWithoutSessionOrTokenLookup() {
        var configuration = new EdgeSecurityConfiguration(new EdgeOAuthProperties());
        var security = configuration.edgeApiSecurityWebFilterChain(ServerHttpSecurity.http(), new WebSessionServerCsrfTokenRepository());
        for (String ambientCredential : List.of("", "session", "bearer")) {
            var request = MockServerHttpRequest.get(PUBLIC_PATH).header("X-Egon-Tianshu-Tenant", "other-tenant")
                    .header("tenant_id", "forged").header("x-egon-user-id", "forged");
            if ("session".equals(ambientCredential)) request.header(HttpHeaders.COOKIE, "__Host-egon_bff=expired");
            if ("bearer".equals(ambientCredential)) request.header(HttpHeaders.AUTHORIZATION, "Bearer expired");
            var exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("tianshu-public-i18n"));
            var forwarded = new AtomicReference<org.springframework.web.server.ServerWebExchange>();
            new WebFilterChainProxy(security).filter(exchange, current -> filter.filter(current, downstream -> {
                forwarded.set(downstream);
                return downstream.getResponse().setComplete();
            })).block(Duration.ofSeconds(3));
            assertThat(forwarded.get()).isNotNull();
            assertThat(forwarded.get().getRequest().getHeaders()).doesNotContainKeys(HttpHeaders.AUTHORIZATION,
                    HttpHeaders.COOKIE, "X-Egon-Tianshu-Tenant", "tenant_id", "x-egon-user-id");
        }
        verifyNoInteractions(bindings);
    }

    @Test void publicRouteIdentityCannotBypassOtherPathsMethodsOrProtectedRoute() {
        for (String path : List.of("/api/v1/tianshu/i18n/admin/bundle", "/api/v1/tianshu/i18n/bundle", PUBLIC_PATH + "/")) {
            var exchange = exchange(HttpMethod.GET, path);
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("tianshu-public-i18n"));
            assertUnauthorized(exchange);
        }
        var post = exchange(HttpMethod.POST, PUBLIC_PATH);
        post.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("tianshu-public-i18n"));
        assertUnauthorized(post);
        var protectedRoute = exchange(HttpMethod.GET, PUBLIC_PATH);
        protectedRoute.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("tianshu-admin"));
        assertUnauthorized(protectedRoute);
        assertUnauthorized(exchange(HttpMethod.GET, PUBLIC_PATH));
    }

    private void assertUnauthorized(MockServerWebExchange exchange) {
        var forwarded = new AtomicReference<org.springframework.web.server.ServerWebExchange>();
        filter.filter(exchange, downstream -> {
            forwarded.set(downstream);
            return downstream.getResponse().setComplete();
        }).block(Duration.ofSeconds(3));
        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private MockServerWebExchange exchange(HttpMethod method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path));
    }

    private Route route(String id) {
        return Route.async().id(id).uri("https://tianshu.example").predicate(exchange -> true).build();
    }
}
