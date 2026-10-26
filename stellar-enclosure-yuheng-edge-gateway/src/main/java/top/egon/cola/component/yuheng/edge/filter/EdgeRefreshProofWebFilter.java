package top.egon.cola.component.yuheng.edge.filter;

import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityStateService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthApplicationCatalog;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCookieService;

/** Only the four explicitly bounded protocol operations accept a signed expired Access proof. */
public final class EdgeRefreshProofWebFilter implements WebFilter {
    private final EdgeOAuthApplicationCatalog catalog;
    private final EdgeOAuthCookieService cookies;
    private final EdgeIdentityStateService identities;

    public EdgeRefreshProofWebFilter(EdgeOAuthApplicationCatalog catalog, EdgeOAuthCookieService cookies, EdgeIdentityStateService identities) {
        this.catalog = catalog;
        this.cookies = cookies;
        this.identities = identities;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.matches("/apps/[A-Za-z0-9_-]{1,128}/auth/(?:csrf|refresh|logout|refresh-operations/[a-f0-9-]{36})")) {
            return chain.filter(exchange);
        }
        return Mono.defer(() -> {
            catalog.requireBrowserOrigin(exchange);
            cookies.rejectAmbientAuthorization(exchange);
            String alias = EdgeOAuthApplicationCatalog.appAlias(exchange);
            String access = cookies.access(exchange, alias).orElseThrow(() -> new EdgeOAuthStateException("EDGE_ACCESS_REJECTED"));
            String browser = cookies.browserBinding(exchange, alias).orElseThrow(() -> new EdgeOAuthStateException("EDGE_BROWSER_BINDING_INVALID"));
            return identities.refreshProof(alias, access, browser).switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_ACCESS_REJECTED")))
                    .flatMap(proof -> {
                        exchange.getAttributes().put(EdgeIdentityStateService.REFRESH_PROOF_ATTRIBUTE, proof);
                        return chain.filter(exchange).doFinally(ignored -> exchange.getAttributes().remove(EdgeIdentityStateService.REFRESH_PROOF_ATTRIBUTE));
                    });
        });
    }
}
