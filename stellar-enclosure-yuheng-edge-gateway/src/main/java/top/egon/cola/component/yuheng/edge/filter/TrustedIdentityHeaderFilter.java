package top.egon.cola.component.yuheng.edge.filter;

import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeSecurityConfiguration;

/** Fail-closed gateway boundary: upstreams receive verified Bearer credentials and no browser cookies. */
@Component("trustedIdentityHeaderFilter")
public class TrustedIdentityHeaderFilter implements GlobalFilter, Ordered {
    private static final Set<String> UNTRUSTED_IDENTITY_HEADERS = Set.of(
            "tenant_id", "user_id", "scope", "x-tenant-id", "x-user-id", "x-scopes",
            "x-egon-tenant-id", "x-egon-user-id", "x-egon-scopes", "X-Egon-Tianshu-Tenant");

    static void sanitize(HttpHeaders headers) {
        UNTRUSTED_IDENTITY_HEADERS.forEach(headers::remove);
        headers.remove(HttpHeaders.COOKIE);
        headers.remove(HttpHeaders.AUTHORIZATION);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null && Set.of("tianshu-public-i18n", "tianshu-public-auth-i18n-browser").contains(route.getId())
                && route.getMetadata().get("edge-app-alias") == null && EdgeSecurityConfiguration.publicI18n(exchange)) {
            return chain.filter(exchange.mutate().request(exchange.getRequest().mutate()
                    .headers(TrustedIdentityHeaderFilter::sanitize).build()).build());
        }
        if (!Boolean.TRUE.equals(exchange.getAttribute(EdgeResourceAuthorizationGatewayFilterFactory.FORWARDED))) {
            return EdgeProtocolErrors.write(exchange, HttpStatus.UNAUTHORIZED, "invalid_token");
        }
        return chain.filter(exchange);
    }

    @Override public int getOrder() { return -1000; }
}
