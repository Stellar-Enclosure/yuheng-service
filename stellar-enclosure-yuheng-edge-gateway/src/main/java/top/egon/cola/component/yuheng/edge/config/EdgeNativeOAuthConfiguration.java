package top.egon.cola.component.yuheng.edge.config;

import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeResourceRefreshTokenGrantRequest;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCallbackContext;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeSqlAuthorizationRequestRepository;

@Configuration(proxyBeanMethods = false)
public class EdgeNativeOAuthConfiguration {
    @Bean(name = "edgeNativeOAuthWebClient")
    public WebClient edgeNativeOAuthWebClient(EdgeOAuthStateProperties properties) {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                .followRedirect(false).responseTimeout(properties.getExchangeTimeout())))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(64 * 1024)).build();
    }

    @Bean(name = "edgeAuthorizationCodeTokenResponseClient")
    public ReactiveOAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> edgeAuthorizationCodeTokenResponseClient(
            @Qualifier("edgeNativeOAuthWebClient") WebClient webClient, EdgeOAuthStateProperties properties) {
        var delegate = new WebClientReactiveAuthorizationCodeTokenResponseClient();
        delegate.setWebClient(webClient);
        delegate.addParametersConverter(request -> {
            var parameters = new LinkedMultiValueMap<String, String>();
            Object resource = request.getAuthorizationExchange().getAuthorizationRequest().getAdditionalParameters().get("resource");
            if (!(resource instanceof String value)) throw new EdgeOAuthStateException("EDGE_CODE_RESOURCE_MISSING");
            parameters.set("resource", value);
            Object browserBinding = request.getAuthorizationExchange().getAuthorizationRequest().getAdditionalParameters().get("browser_binding");
            if (!(browserBinding instanceof String binding)) throw new EdgeOAuthStateException("EDGE_BROWSER_BINDING_INVALID");
            top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests.requireDigest(binding);
            parameters.set("browser_binding", binding);
            return parameters;
        });
        return request -> Mono.defer(() -> {
            EdgeOAuthCallbackContext callback = request.getAuthorizationExchange().getAuthorizationRequest()
                    .getAttribute(EdgeSqlAuthorizationRequestRepository.CALLBACK);
            if (callback == null) return Mono.error(new EdgeOAuthStateException("EDGE_CODE_TRANSACTION_MISSING"));
            return delegate.getTokenResponse(request).doOnNext(callback::received).timeout(properties.getExchangeTimeout());
        });
    }

    @Bean(name = "edgeRefreshTokenResponseClient")
    public ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> edgeRefreshTokenResponseClient(
            @Qualifier("edgeNativeOAuthWebClient") WebClient webClient, EdgeOAuthStateProperties properties) {
        var delegate = new WebClientReactiveRefreshTokenTokenResponseClient();
        delegate.setWebClient(webClient);
        delegate.addParametersConverter(request -> {
            if (!(request instanceof EdgeResourceRefreshTokenGrantRequest bound)) {
                throw new EdgeOAuthStateException("EDGE_REFRESH_RESOURCE_MISSING");
            }
            var parameters = new LinkedMultiValueMap<String, String>();
            parameters.set("resource", bound.getResource().toASCIIString());
            parameters.set("browser_binding", bound.getBrowserBindingDigest());
            return parameters;
        });
        return request -> delegate.getTokenResponse(request).timeout(properties.getExchangeTimeout());
    }

    @Bean(name = "edgeOidcAuthenticationManager")
    public ReactiveAuthenticationManager edgeOidcAuthenticationManager(
            @Qualifier("edgeAuthorizationCodeTokenResponseClient") ReactiveOAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens,
            @Qualifier("edgeNativeOAuthWebClient") WebClient webClient, EdgeOAuthStateProperties properties) {
        var oauthUsers = new DefaultReactiveOAuth2UserService();
        oauthUsers.setWebClient(webClient);
        var oidcUsers = new OidcReactiveOAuth2UserService();
        oidcUsers.setOauth2UserService(oauthUsers);
        var nativeOidc = new OidcAuthorizationCodeReactiveAuthenticationManager(tokens, oidcUsers);
        return authentication -> nativeOidc.authenticate(authentication).timeout(properties.getExchangeTimeout())
                .onErrorMap(TimeoutException.class, timeout -> new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable")));
    }
}
