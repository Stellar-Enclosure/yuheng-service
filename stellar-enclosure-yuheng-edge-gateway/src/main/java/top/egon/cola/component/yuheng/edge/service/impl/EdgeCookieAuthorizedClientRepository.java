package top.egon.cola.component.yuheng.edge.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthExchangeClaimBO;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeOAuthTransactionRepository;

/** Native OAuth login save adapter. It never retains or reloads an AuthorizedClient. */
@Repository
@RequiredArgsConstructor
public class EdgeCookieAuthorizedClientRepository implements ServerOAuth2AuthorizedClientRepository {
    private final EdgeIdentityStateService identities;
    private final EdgeOAuthTransactionRepository transactions;
    private final EdgeOAuthCookieService cookies;

    @Override
    public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(String registrationId,
            Authentication principal, ServerWebExchange exchange) {
        return Mono.error(new EdgeOAuthStateException("EDGE_AUTHORIZED_CLIENT_STORE_FORBIDDEN"));
    }

    @Override
    public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal, ServerWebExchange exchange) {
        return Mono.defer(() -> {
            EdgeOAuthExchangeClaimBO claim = exchange.getAttribute(EdgeSqlAuthorizationRequestRepository.EXCHANGE_CLAIM);
            EdgeOAuthCallbackContext callback = exchange.getAttribute(EdgeSqlAuthorizationRequestRepository.CALLBACK);
            if (claim == null || callback == null || callback.response() == null || client.getRefreshToken() == null
                    || !claim.transaction().target().registrationId().equals(client.getClientRegistration().getRegistrationId())
                    || !client.getAccessToken().getTokenValue().equals(callback.response().getAccessToken().getTokenValue())) {
                return Mono.error(new EdgeOAuthStateException("EDGE_CODE_CALLBACK_INVALID"));
            }
            String alias = claim.transaction().target().appAlias();
            String browser = cookies.browserBinding(exchange, alias)
                    .orElseThrow(() -> new EdgeOAuthStateException("EDGE_BROWSER_BINDING_INVALID"));
            return identities.access(alias, client.getAccessToken().getTokenValue(), true)
                    .switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_ACCESS_REJECTED"))).flatMap(access -> {
                if (!principal.getName().equals(access.proof().getSubject()) || exchange.getResponse().isCommitted()) {
                    return Mono.error(new EdgeOAuthStateException("EDGE_CODE_IDENTITY_MISMATCH"));
                }
                var context = identities.issuedContext(access, browser);
                if (!context.target().equals(claim.transaction().target())
                        || !context.browserBindingDigest().equals(claim.transaction().browserBindingDigest())) {
                    return Mono.error(new EdgeOAuthStateException("EDGE_CODE_IDENTITY_MISMATCH"));
                }
                callback.verifiedContext(context);
                var prepared = cookies.prepareCredentials(alias, client.getAccessToken().getTokenValue(),
                        client.getRefreshToken().getTokenValue(), browser, context.authorizationExpiresAt());
                return transactions.complete(claim, true, null).flatMap(committed -> {
                    if (!committed) return Mono.error(new EdgeOAuthStateException("EDGE_CODE_EXCHANGE_UNCONFIRMED"));
                    cookies.writePrepared(exchange, prepared);
                    return Mono.empty();
                });
            });
        });
    }

    @Override
    public Mono<Void> removeAuthorizedClient(String registrationId, Authentication principal, ServerWebExchange exchange) {
        return Mono.error(new EdgeOAuthStateException("EDGE_EXPLICIT_LOGOUT_REQUIRED"));
    }
}
