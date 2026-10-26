package top.egon.cola.component.yuheng.edge.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthExchangeClaimBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeOAuthTransactionRepository;

/** Adapts Spring's consuming callback converter to a one-use SQL transaction, never WebSession. */
@Repository
@RequiredArgsConstructor
public class EdgeSqlAuthorizationRequestRepository implements ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    public static final String EXCHANGE_CLAIM = EdgeSqlAuthorizationRequestRepository.class.getName() + ".claim";
    public static final String APPLICATION = EdgeSqlAuthorizationRequestRepository.class.getName() + ".application";
    public static final String CALLBACK = EdgeSqlAuthorizationRequestRepository.class.getName() + ".callback";
    private final EdgeOAuthTransactionRepository transactions;
    private final EdgeOAuthApplicationCatalog catalog;
    private final EdgeOAuthCookieService cookies;

    @Override
    public Mono<OAuth2AuthorizationRequest> loadAuthorizationRequest(ServerWebExchange exchange) {
        // The native callback converter uses removeAuthorizationRequest. A reusable secret read is forbidden.
        return Mono.error(new EdgeOAuthStateException("EDGE_OAUTH_CONSUMING_READ_REQUIRED"));
    }

    @Override
    public Mono<Void> saveAuthorizationRequest(OAuth2AuthorizationRequest request, ServerWebExchange exchange) {
        if (request == null) return Mono.empty();
        return Mono.defer(() -> {
            String alias = EdgeOAuthApplicationCatalog.appAlias(exchange);
            EdgeOAuthTargetBO target = request.getAttribute(EdgeAuthorizationRequestResolver.TARGET);
            if (target == null || !alias.equals(target.appAlias())) throw invalid();
            return catalog.application(alias, target.issuer()).flatMap(application -> {
                String verifier = request.getAttribute(PkceParameterNames.CODE_VERIFIER);
                String nonce = request.getAttribute("nonce");
                boolean reauthenticate = Boolean.TRUE.equals(request.getAttribute(EdgeAuthorizationRequestResolver.REAUTHENTICATE));
                Object rawBinding = request.getAdditionalParameters().get("browser_binding");
                if (!application.target().equals(target) || !application.callbackUri().equals(request.getRedirectUri())
                        || !target.clientId().equals(request.getClientId()) || !application.resource().getScopes().equals(request.getScopes())
                        || !(rawBinding instanceof String binding)
                        || !binding.equals(exchange.getAttribute(EdgeAuthorizationRequestResolver.BROWSER_BINDING))
                        || !target.resource().equals(request.getAdditionalParameters().get("resource"))
                        || !"S256".equals(request.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE_METHOD))
                        || !EdgeOAuthStateDigests.sha256Base64Url(verifier).equals(request.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE))
                        || !EdgeOAuthStateDigests.sha256Base64Url(nonce).equals(request.getAdditionalParameters().get("nonce"))
                        || (reauthenticate && (!"login".equals(request.getAdditionalParameters().get("prompt")) || !"0".equals(request.getAdditionalParameters().get("max_age"))))
                        || (!reauthenticate && (request.getAdditionalParameters().containsKey("prompt") || request.getAdditionalParameters().containsKey("max_age")))) {
                    throw invalid();
                }
                return transactions.create(target, request.getRedirectUri(), request.getAttribute(EdgeAuthorizationRequestResolver.RETURN_PATH),
                        request.getScopes().stream().sorted().toList(), request.getState(), binding, verifier, nonce, reauthenticate).then();
            });
        });
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> removeAuthorizationRequest(ServerWebExchange exchange) {
        return Mono.defer(() -> {
            if (exchange.getRequest().getMethod() != HttpMethod.GET
                    || !exchange.getRequest().getPath().value().matches("/apps/[A-Za-z0-9_-]{1,128}/auth/callback")) {
                throw invalid();
            }
            catalog.requireBrowserOrigin(exchange);
            cookies.rejectAmbientAuthorization(exchange);
            String alias = EdgeOAuthApplicationCatalog.appAlias(exchange);
            var query = exchange.getRequest().getQueryParams();
            if (query.getOrDefault("state", List.of()).size() != 1
                    || query.getOrDefault("code", List.of()).size() > 1
                    || query.getOrDefault("error", List.of()).size() > 1
                    || query.getOrDefault("iss", List.of()).size() > 1
                    || query.containsKey("code") == query.containsKey("error")) throw invalid();
            String state = query.getFirst("state");
            String browser = cookies.browserBinding(exchange, alias).orElseThrow(EdgeSqlAuthorizationRequestRepository::invalid);
            String binding = EdgeOAuthStateDigests.sha256(browser);
            return transactions.findPending(alias, state, binding).flatMap(found -> {
                if (found.isEmpty()) return Mono.error(invalid());
                var transaction = found.orElseThrow();
                return catalog.application(alias, transaction.target().issuer()).flatMap(application -> {
                    if (!application.target().equals(transaction.target()) || !application.callbackUri().equals(transaction.redirectUri())
                            || (query.containsKey("iss") && !transaction.target().issuer().equals(query.getFirst("iss")))
                            || (Boolean.TRUE.equals(application.registration().getProviderDetails().getConfigurationMetadata()
                                .get("authorization_response_iss_parameter_supported")) && !query.containsKey("iss"))) {
                        return Mono.error(invalid());
                    }
                    if (query.containsKey("error")) {
                        return transactions.cancel(transaction, "EDGE_OAUTH_ACCESS_DENIED")
                                .then(Mono.error(new OAuth2AuthenticationException(new OAuth2Error("access_denied"))));
                    }
                    return transactions.claim(transaction.target(), state, binding, query.getFirst("code")).map(claim -> {
                        exchange.getAttributes().put(EXCHANGE_CLAIM, claim);
                        exchange.getAttributes().put(APPLICATION, application);
                        var callback = new EdgeOAuthCallbackContext();
                        exchange.getAttributes().put(CALLBACK, callback);
                        return restore(application, claim, state, callback);
                    });
                });
            });
        });
    }

    private static OAuth2AuthorizationRequest restore(EdgeOAuthApplicationCatalog.Application application,
                                                       EdgeOAuthExchangeClaimBO claim, String state, EdgeOAuthCallbackContext callback) {
        var transaction = claim.transaction();
        var parameters = new java.util.LinkedHashMap<String, Object>(Map.of("resource", application.target().resource(),
                "browser_binding", transaction.browserBindingDigest(), PkceParameterNames.CODE_CHALLENGE, transaction.codeChallenge(),
                PkceParameterNames.CODE_CHALLENGE_METHOD, "S256", "nonce", EdgeOAuthStateDigests.sha256Base64Url(claim.nonce())));
        if (claim.reauthenticate()) { parameters.put("prompt", "login"); parameters.put("max_age", "0"); }
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(application.registration().getProviderDetails().getAuthorizationUri())
                .clientId(application.target().clientId()).redirectUri(transaction.redirectUri())
                .scopes(Set.copyOf(transaction.requestedScopes())).state(state)
                .attributes(Map.of("registration_id", application.registration().getRegistrationId(),
                        PkceParameterNames.CODE_VERIFIER, claim.codeVerifier(), "nonce", claim.nonce(),
                        EdgeAuthorizationRequestResolver.APPLICATION_ALIAS, application.target().appAlias(),
                        EdgeAuthorizationRequestResolver.RETURN_PATH, transaction.returnPath(),
                        EdgeAuthorizationRequestResolver.TARGET, application.target(), CALLBACK, callback,
                        EdgeAuthorizationRequestResolver.REAUTHENTICATE, claim.reauthenticate()))
                .additionalParameters(parameters)
                .build();
    }

    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_request"), "EDGE_OAUTH_CALLBACK_INVALID");
    }

}
