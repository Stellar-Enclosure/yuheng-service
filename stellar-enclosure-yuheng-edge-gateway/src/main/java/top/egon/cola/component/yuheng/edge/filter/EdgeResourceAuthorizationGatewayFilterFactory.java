package top.egon.cola.component.yuheng.edge.filter;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedAccessBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityStateService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthApplicationCatalog;

/** Routes the already verified request to its fixed audience; never loads or refreshes credentials. */
@Component
@RequiredArgsConstructor
public class EdgeResourceAuthorizationGatewayFilterFactory extends AbstractGatewayFilterFactory<AbstractGatewayFilterFactory.NameConfig> {
    public static final String FORWARDED = EdgeResourceAuthorizationGatewayFilterFactory.class.getName() + ".forwarded";
    private final EdgeOAuthApplicationCatalog catalog;
    private final AntPathMatcher paths = new AntPathMatcher();

    @Override public Class<NameConfig> getConfigClass() { return NameConfig.class; }
    @Override public List<String> shortcutFieldOrder() { return List.of("name"); }

    @Override
    public GatewayFilter apply(NameConfig config) {
        String alias = Objects.requireNonNull(config.getName(), "application alias");
        catalog.resource(alias);
        return new OrderedGatewayFilter((exchange, chain) -> Mono.defer(() -> {
            var resource = catalog.resource(alias);
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String path = exchange.getRequest().getPath().value();
            String browserPrefix = "/apps/" + alias;
            String resourcePath = path.startsWith(browserPrefix + "/") ? path.substring(browserPrefix.length()) : path;
            if (route == null || !alias.equals(route.getMetadata().get("edge-app-alias"))
                    || !resource.getRouteIds().contains(route.getId()) || !routeUri(resource.getResourceUri()).equals(route.getUri())
                    || resource.getRequestPaths().stream().noneMatch(pattern -> paths.match(pattern, resourcePath) || paths.match(pattern, path))) {
                return EdgeProtocolErrors.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable");
            }
            EdgeVerifiedAccessBO access = exchange.getAttribute(EdgeIdentityStateService.ACCESS_ATTRIBUTE);
            if (access == null || !alias.equals(access.appAlias()) || !resource.getResourceUri().equals(access.resource())) {
                return EdgeProtocolErrors.write(exchange, HttpStatus.UNAUTHORIZED, "invalid_token");
            }
            var request = exchange.getRequest().mutate().headers(headers -> {
                TrustedIdentityHeaderFilter.sanitize(headers);
                headers.setBearerAuth(access.proof().getJwt().getTokenValue());
            }).build();
            exchange.getAttributes().put(FORWARDED, Boolean.TRUE);
            return chain.filter(exchange.mutate().request(request).build())
                    .doFinally(signal -> exchange.getAttributes().remove(FORWARDED));
        }), -1100);
    }

    private static URI routeUri(URI resource) {
        return resource.getPort() < 0 ? org.springframework.web.util.UriComponentsBuilder.fromUri(resource).port(443).build(false).toUri() : resource;
    }
}
