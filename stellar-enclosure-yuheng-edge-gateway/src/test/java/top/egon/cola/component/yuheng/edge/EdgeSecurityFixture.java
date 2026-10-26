package top.egon.cola.component.yuheng.edge;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthProperties;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.config.EdgeSecurityConfiguration;
import top.egon.cola.component.yuheng.edge.config.domain.bo.EdgeRegistrationResourceBindingBO;
import top.egon.cola.component.yuheng.edge.filter.EdgeProtocolErrors;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthExchangeClaimBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTransactionBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeVerifiedAccessBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeAuthorizationRequestResolver;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeCookieAuthorizedClientRepository;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeCsrfProofService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityStateService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthApplicationCatalog;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCallbackContext;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCookieService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeSqlAuthorizationRequestRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeOAuthTransactionRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRevocationIntentRepository;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.VerifiedJwtBO;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class EdgeSecurityFixture {
    static final String ALIAS = "app-a";
    static final String ORIGIN = "https://edge.example";
    static final String ISSUER = "https://idp.example/tenants/1001";
    static final Instant NOW = Instant.now();
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final EdgeOAuthStateProperties stateProperties = new EdgeOAuthStateProperties();
    final java.util.concurrent.atomic.AtomicBoolean reauthenticate = new java.util.concurrent.atomic.AtomicBoolean();
    final EdgeOAuthProperties properties;
    final ClientRegistration registration;
    final EdgeOAuthApplicationCatalog catalog;
    final EdgeOAuthCookieService cookies;
    final EdgeCsrfProofService csrf;
    final EdgeIdentityStateService identities = mock(EdgeIdentityStateService.class);
    final EdgeOAuthTransactionRepository transactions = mock(EdgeOAuthTransactionRepository.class);
    final EdgeRevocationIntentRepository revocations = mock(EdgeRevocationIntentRepository.class);
    final EdgeSqlAuthorizationRequestRepository requests;
    final AtomicReference<OAuth2AuthorizationRequest> exchanged = new AtomicReference<>();
    final AtomicInteger tokenCalls = new AtomicInteger();
    final AtomicReference<EdgeOAuthTransactionBO> pending = new AtomicReference<>();
    final AtomicReference<String> verifier = new AtomicReference<>();
    final AtomicReference<String> nonce = new AtomicReference<>();
    final AtomicBoolean claimed = new AtomicBoolean();
    final AtomicBoolean wrongNonce = new AtomicBoolean();
    final WebFilterChainProxy security;
    final EdgeOAuthTargetBO target = new EdgeOAuthTargetBO(ALIAS, "app-a-tenant", ISSUER, "1001", "browser-client", "https://resource.example");

    EdgeSecurityFixture(Path directory) throws Exception {
        var resource = new EdgeRegistrationResourceBindingBO().setResourceServerId("resource-a")
                .setResourceUri(URI.create(target.resource())).setDefaultRegistrationId(target.registrationId())
                .setIssuerRegistrationIds(Map.of(ISSUER, target.registrationId())).setRouteIds(Set.of("resource-a", "resource-a-browser"))
                .setRequestPaths(List.of("/api/example/**")).setScopes(Set.of("openid", "profile", "offline_access")).setReturnPath("/");
        properties = new EdgeOAuthProperties().setPublicOrigin(URI.create(ORIGIN)).setDefaultRegistrationId(ALIAS)
                .setAllowedReturnPaths(List.of("/" )).setResources(Map.of(ALIAS, resource))
                .setUpstreams(new EdgeOAuthProperties.Upstreams(URI.create("https://idp.example"), URI.create("https://resource.example"), URI.create("https://mcp.example")));
        properties.validate();
        registration = ClientRegistration.withRegistrationId(target.registrationId()).clientId(target.clientId()).clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .redirectUri(ORIGIN + "/apps/" + ALIAS + "/auth/callback").scope(resource.getScopes())
                .authorizationUri(ISSUER + "/oauth2/authorize").tokenUri(ISSUER + "/oauth2/token").issuerUri(ISSUER)
                .jwkSetUri(ISSUER + "/.well-known/jwks.json").providerConfigurationMetadata(Map.of("revocation_endpoint", ISSUER + "/oauth2/revoke")).build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(registration);
        catalog = new EdgeOAuthApplicationCatalog(properties, registrations);
        cookies = new EdgeOAuthCookieService(properties, stateProperties, clock);
        Path key = directory.resolve(UUID.randomUUID() + ".key");
        Files.write(key, new byte[32]);
        Files.setPosixFilePermissions(key, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        stateProperties.getEncryption().setActiveKeyId("v1");
        stateProperties.getEncryption().setKeyFiles(Map.of("v1", key));
        var cipher = new EdgeOAuthStateCipher(stateProperties.getEncryption());
        csrf = new EdgeCsrfProofService(cipher, clock);
        requests = new EdgeSqlAuthorizationRequestRepository(transactions, catalog, cookies);
        when(transactions.create(any(), anyString(), anyString(), anyList(), anyString(), anyString(), anyString(), anyString(), anyBoolean())).thenAnswer(call -> {
            var transaction = new EdgeOAuthTransactionBO(UUID.randomUUID(), call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    call.getArgument(3), EdgeOAuthStateDigests.sha256(call.getArgument(4)), call.getArgument(5),
                    EdgeOAuthStateDigests.sha256(call.getArgument(7)), EdgeOAuthStateDigests.sha256Base64Url(call.getArgument(6)), "PENDING", NOW.plusSeconds(300), 0);
            pending.set(transaction); verifier.set(call.getArgument(6)); nonce.set(call.getArgument(7)); reauthenticate.set(call.getArgument(8));
            return Mono.just(transaction);
        });
        when(transactions.findPending(anyString(), anyString(), anyString())).thenAnswer(call -> Mono.just(Optional.ofNullable(pending.get())
                .filter(value -> value.stateDigest().equals(EdgeOAuthStateDigests.sha256(call.getArgument(1))) && value.browserBindingDigest().equals(call.getArgument(2)))));
        when(transactions.claim(any(), anyString(), anyString(), anyString())).thenAnswer(call -> claimed.compareAndSet(false, true)
                ? Mono.just(new EdgeOAuthExchangeClaimBO(pending.get(), UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(10), verifier.get(), nonce.get(), reauthenticate.get()))
                : Mono.error(new IllegalStateException("one-use claim rejected")));
        when(transactions.complete(any(), anyBoolean(), nullable(String.class))).thenReturn(Mono.just(true));
        when(transactions.cancel(any(), anyString())).thenReturn(Mono.just(true));
        when(revocations.revokeUnconfirmedExchange(any(), any(EdgeOAuthTargetBO.class), anyString(), anyString())).thenReturn(Mono.empty());
        when(revocations.revokeUnconfirmedExchange(any(), any(EdgeRefreshContextBO.class), anyString())).thenReturn(Mono.empty());
        when(identities.access(eq(ALIAS), eq("access.jwt.value"), eq(true))).thenReturn(Mono.just(access()));
        when(identities.issuedContext(any(), anyString())).thenAnswer(call -> context(call.getArgument(1)));
        var nativeOidc = new OidcAuthorizationCodeReactiveAuthenticationManager(request -> {
            tokenCalls.incrementAndGet();
            var authorization = request.getAuthorizationExchange().getAuthorizationRequest();
            exchanged.set(authorization);
            var response = OAuth2AccessTokenResponse.withToken("access.jwt.value").tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .refreshToken("refresh.value").expiresIn(300).scopes(resource.getScopes()).additionalParameters(Map.of("id_token", "id.jwt.value")).build();
            EdgeOAuthCallbackContext callback = authorization.getAttribute(EdgeSqlAuthorizationRequestRepository.CALLBACK);
            callback.received(response);
            return Mono.just(response);
        }, request -> Mono.just(new DefaultOidcUser(List.of(), request.getIdToken())));
        nativeOidc.setJwtDecoderFactory(client -> value -> Mono.just(Jwt.withTokenValue(value).header("alg", "RS256")
                .issuer(ISSUER).subject("user-1").audience(List.of(target.clientId())).issuedAt(NOW.minusSeconds(1)).expiresAt(NOW.plusSeconds(300))
                .claim("nonce", wrongNonce.get() ? "wrong-nonce" : exchanged.get().getAdditionalParameters().get("nonce")).build()));
        var configuration = new EdgeSecurityConfiguration();
        var chain = configuration.edgeSecurityWebFilterChain(ServerHttpSecurity.http(), properties, registrations,
                new EdgeAuthorizationRequestResolver(catalog, cookies), requests,
                new EdgeCookieAuthorizedClientRepository(identities, transactions, cookies), nativeOidc, catalog, cookies, csrf, identities, transactions, revocations);
        security = new WebFilterChainProxy(chain);
    }

    EdgeVerifiedAccessBO access() {
        var jwt = Jwt.withTokenValue("access.jwt.value").header("alg", "RS256").issuer(ISSUER).subject("user-1")
                .audience(List.of(target.resource())).issuedAt(NOW.minusSeconds(1)).expiresAt(NOW.plusSeconds(300)).claim("scope", "openid profile offline_access").build();
        return new EdgeVerifiedAccessBO(ALIAS, URI.create(target.resource()), new VerifiedJwtBO().setJwt(jwt).setSubject("user-1")
                .setAuthorizationId("123").setTenantId("1001").setExpiresAt(NOW.plusSeconds(300)), null, target);
    }

    EdgeRefreshContextBO context(String browser) {
        return new EdgeRefreshContextBO(target, "user-1", "123", "456", 0, NOW.plusSeconds(3600), NOW.plusSeconds(7200), EdgeOAuthStateDigests.sha256(browser));
    }

    void run(MockServerWebExchange exchange) {
        ServerWebExchange noSession = new ServerWebExchangeDecorator(exchange) {
            @Override public Mono<WebSession> getSession() { return Mono.error(new AssertionError("EDGE attempted WebSession access")); }
        };
        security.filter(noSession, current -> {
            current.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return current.getResponse().setComplete();
        }).onErrorResume(failure -> failure instanceof AssertionError ? Mono.error(failure) : EdgeProtocolErrors.write(noSession, failure))
                .block(Duration.ofSeconds(3));
    }

    MockServerWebExchange login() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(ORIGIN + "/apps/" + ALIAS + "/auth/login"));
        run(exchange);
        return exchange;
    }

    MockServerWebExchange callback(MockServerWebExchange login) {
        var location = login.getResponse().getHeaders().getLocation();
        String state = org.springframework.web.util.UriComponentsBuilder.fromUri(location).build().getQueryParams().getFirst("state");
        String binding = login.getResponse().getCookies().getFirst("__Secure-egon_binding_" + ALIAS).getValue();
        return MockServerWebExchange.from(MockServerHttpRequest.get(ORIGIN + "/apps/" + ALIAS + "/auth/callback?code=code-value&state=" + state)
                .cookie(new HttpCookie("__Secure-egon_binding_" + ALIAS, binding)));
    }
}
