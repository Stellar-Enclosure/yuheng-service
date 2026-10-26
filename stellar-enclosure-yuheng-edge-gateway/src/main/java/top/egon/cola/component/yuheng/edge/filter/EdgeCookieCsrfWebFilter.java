package top.egon.cola.component.yuheng.edge.filter;

import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthProperties;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedAccessBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedRefreshProofBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeCsrfProofService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityStateService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthApplicationCatalog;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCookieService;

public final class EdgeCookieCsrfWebFilter implements WebFilter {
    private final EdgeOAuthCookieService cookies;
    private final EdgeCsrfProofService proofs;
    private final EdgeOAuthProperties properties;

    public EdgeCookieCsrfWebFilter(EdgeOAuthCookieService cookies, EdgeCsrfProofService proofs, EdgeOAuthProperties properties) {
        this.cookies = cookies;
        this.proofs = proofs;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/apps/")
                || Set.of("GET", "HEAD", "OPTIONS").contains(exchange.getRequest().getMethod().name())) return chain.filter(exchange);
        return Mono.defer(() -> {
            String alias = EdgeOAuthApplicationCatalog.appAlias(exchange);
            var headers = exchange.getRequest().getHeaders();
            if (headers.getOrEmpty(HttpHeaders.ORIGIN).size() > 1 || headers.getOrEmpty(HttpHeaders.REFERER).size() > 1 || headers.getOrEmpty("X-CSRF-TOKEN").size() != 1) throw invalid();
            String origin = headers.getOrigin();
            String referer = headers.getFirst(HttpHeaders.REFERER);
            try {
                URI source = origin != null ? URI.create(origin) : referer == null ? null : URI.create(referer);
                if (source == null || source.getUserInfo() != null || source.getFragment() != null
                        || (origin != null && (source.getQuery() != null || (source.getPath() != null && !source.getPath().isEmpty())))
                        || !EdgeOAuthApplicationCatalog.sameOrigin(properties.getPublicOrigin(), source)) throw invalid();
            } catch (IllegalArgumentException malformed) { throw invalid(); }
            String context;
            EdgeVerifiedRefreshProofBO refresh = exchange.getAttribute(EdgeIdentityStateService.REFRESH_PROOF_ATTRIBUTE);
            EdgeVerifiedAccessBO access = exchange.getAttribute(EdgeIdentityStateService.ACCESS_ATTRIBUTE);
            if (refresh != null) {
                var value = refresh.context();
                context = EdgeCsrfProofService.context(value.target(), value.subject(), value.authorizationId(), value.authenticationJti());
            } else if (access != null && access.browserTarget() != null) {
                context = EdgeCsrfProofService.context(access.browserTarget(), access.proof().getSubject(),
                        access.proof().getAuthorizationId(), access.state().getAuthenticationJti());
            } else throw invalid();
            proofs.verify(cookies.csrf(exchange, alias).orElseThrow(EdgeCookieCsrfWebFilter::invalid), headers.getFirst("X-CSRF-TOKEN"), context);
            return chain.filter(exchange);
        });
    }

    private static EdgeOAuthStateException invalid() { return new EdgeOAuthStateException("EDGE_CSRF_INVALID"); }
}
