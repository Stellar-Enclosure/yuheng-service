package top.egon.cola.component.yuheng.edge.filter;

import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthExchangeClaimBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCallbackContext;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeSqlAuthorizationRequestRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeOAuthTransactionRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRevocationIntentRepository;

/** Async cancellation cleanup is part of the callback publisher, not a fire-and-forget side channel. */
public final class EdgeOAuthCallbackLifecycleFilter implements WebFilter {
    private final EdgeOAuthTransactionRepository transactions;
    private final EdgeRevocationIntentRepository revocations;

    public EdgeOAuthCallbackLifecycleFilter(EdgeOAuthTransactionRepository transactions, EdgeRevocationIntentRepository revocations) {
        this.transactions = transactions;
        this.revocations = revocations;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().matches("/apps/[A-Za-z0-9_-]{1,128}/auth/callback")) {
            return chain.filter(exchange);
        }
        return Mono.usingWhen(Mono.just(exchange), chain::filter,
                current -> cleanup(current, false), (current, failure) -> cleanup(current, true), current -> cleanup(current, true));
    }

    private Mono<Void> cleanup(ServerWebExchange exchange, boolean cancelledOrFailed) {
        EdgeOAuthExchangeClaimBO claim = exchange.getAttribute(EdgeSqlAuthorizationRequestRepository.EXCHANGE_CLAIM);
        EdgeOAuthCallbackContext callback = exchange.getAttribute(EdgeSqlAuthorizationRequestRepository.CALLBACK);
        Mono<Void> cleanup = Mono.empty();
        if (claim != null && callback != null && (cancelledOrFailed || !callback.completed())) {
            var response = callback.response();
            if (response != null && response.getRefreshToken() != null) {
                String token = response.getRefreshToken().getTokenValue();
                cleanup = callback.verifiedContext() == null
                        ? revocations.revokeUnconfirmedExchange(claim.transaction().transactionId(), claim.transaction().target(),
                            claim.transaction().browserBindingDigest(), token).then()
                        : revocations.revokeUnconfirmedExchange(claim.transaction().transactionId(), callback.verifiedContext(), token).then();
            }
            cleanup = cleanup.then(transactions.complete(claim, false, "EDGE_CODE_RESPONSE_UNCONFIRMED")).then();
        }
        return cleanup.doFinally(ignored -> {
            if (callback != null) callback.clear();
            exchange.getAttributes().remove(EdgeSqlAuthorizationRequestRepository.EXCHANGE_CLAIM);
            exchange.getAttributes().remove(EdgeSqlAuthorizationRequestRepository.APPLICATION);
            exchange.getAttributes().remove(EdgeSqlAuthorizationRequestRepository.CALLBACK);
        });
    }
}
