package top.egon.cola.component.yuheng.edge.service.impl;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;

@Component
@RequiredArgsConstructor
public class EdgeAuthorizationRequestResolver implements ServerOAuth2AuthorizationRequestResolver {
    static final String APPLICATION_ALIAS = "edge.application-alias";
    static final String RETURN_PATH = "edge.return-path";
    static final String TARGET = "edge.oauth-target";
    static final String BROWSER_BINDING = "edge.browser-binding-digest";
    static final String REAUTHENTICATE = "edge.reauthenticate";
    private final EdgeOAuthApplicationCatalog catalog;
    private final EdgeOAuthCookieService cookies;
    private final SecureRandom random = new SecureRandom();

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.GET
                || !exchange.getRequest().getPath().value().matches("/apps/[A-Za-z0-9_-]{1,128}/auth/login")) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            catalog.requireBrowserOrigin(exchange);
            cookies.rejectAmbientAuthorization(exchange);
            String alias = EdgeOAuthApplicationCatalog.appAlias(exchange);
            var query = exchange.getRequest().getQueryParams();
            if (query.keySet().stream().anyMatch(key -> !List.of("returnTo", "reauthenticate").contains(key))
                    || query.values().stream().anyMatch(values -> values.size() != 1)
                    || (query.containsKey("reauthenticate") && !"true".equals(query.getFirst("reauthenticate")))) {
                throw new EdgeOAuthStateException("EDGE_LOGIN_REQUEST_INVALID");
            }
            return catalog.defaultApplication(alias).map(application -> {
                String browser = cookies.ensureBrowserBinding(exchange, alias);
                String binding = EdgeOAuthStateDigests.sha256(browser);
                exchange.getAttributes().put(BROWSER_BINDING, binding);
                String nonce = randomValue();
                String verifier = randomValue();
                String returnPath = catalog.returnPath(application, query.getFirst("returnTo"));
                boolean reauthenticate = "true".equals(query.getFirst("reauthenticate"));
                var parameters = new java.util.LinkedHashMap<String, Object>(Map.of("resource", application.target().resource(),
                        "browser_binding", binding, "nonce", EdgeOAuthStateDigests.sha256Base64Url(nonce),
                        PkceParameterNames.CODE_CHALLENGE, EdgeOAuthStateDigests.sha256Base64Url(verifier),
                        PkceParameterNames.CODE_CHALLENGE_METHOD, "S256"));
                if (reauthenticate) { parameters.put("prompt", "login"); parameters.put("max_age", "0"); }
                return OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri(application.registration().getProviderDetails().getAuthorizationUri())
                        .clientId(application.target().clientId()).redirectUri(application.callbackUri())
                        .scopes(application.resource().getScopes()).state(randomValue())
                        .attributes(Map.of("registration_id", application.registration().getRegistrationId(),
                                PkceParameterNames.CODE_VERIFIER, verifier, "nonce", nonce,
                                APPLICATION_ALIAS, alias, RETURN_PATH, returnPath, TARGET, application.target(), REAUTHENTICATE, reauthenticate))
                        .additionalParameters(parameters)
                        .build();
            });
        });
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange, String clientRegistrationId) {
        // A query/header must never choose a different registered client or resource.
        return resolve(exchange).filter(value -> clientRegistrationId.equals(value.getAttribute("registration_id")));
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
