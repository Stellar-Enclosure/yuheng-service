package top.egon.cola.component.yuheng.edge.config;

import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.session.WebSessionManager;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.filter.EdgeCookieCsrfWebFilter;
import top.egon.cola.component.yuheng.edge.filter.EdgeOAuthCallbackLifecycleFilter;
import top.egon.cola.component.yuheng.edge.filter.EdgeProtocolErrors;
import top.egon.cola.component.yuheng.edge.filter.EdgeRefreshProofWebFilter;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthExchangeClaimBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeAuthorizationRequestResolver;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeCookieAuthorizedClientRepository;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeCsrfProofService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeIdentityStateService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthApplicationCatalog;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCallbackContext;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthCookieService;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeSqlAuthorizationRequestRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeOAuthTransactionRepository;
import top.egon.cola.component.yuheng.edge.service.repository.EdgeRevocationIntentRepository;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TenantIssuerAuthenticationManagerComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.TrustedIssuerRegistry;

/** Native Spring authentication with one-use SQL OAuth requests and request-only SecurityContext. */
@Configuration(value = "edgeSecurityConfiguration", proxyBeanMethods = false)
@EnableWebFluxSecurity
public class EdgeSecurityConfiguration {
    public static final String PUBLIC_I18N_PATH = "/api/v1/tianshu/i18n/public/bundle";
    public static final String PUBLIC_APP_I18N_PATH = "/apps/edge-tianshu" + PUBLIC_I18N_PATH;

    @Bean(name = {"edgeClock", "idpClock"})
    public Clock edgeClock() { return Clock.systemUTC(); }

    @Bean
    public static BeanFactoryPostProcessor edgeJacksonObjectMapperAlias() {
        return beans -> {
            if (beans.containsBean("jackson2ObjectMapper") && !beans.containsBean("jacksonObjectMapper")) {
                beans.registerAlias("jackson2ObjectMapper", "jacksonObjectMapper");
            }
        };
    }

    @Bean(name = {"edgeIdpStarterProperties", "idpStarterProperties"})
    @ConfigurationProperties("egon.cola.platform.tianquan.shoubing")
    public IdpStarterProperties edgeIdpStarterProperties() { return new IdpStarterProperties(); }

    @Bean
    @ConditionalOnMissingBean(TrustedIssuerRegistry.class)
    public TrustedIssuerRegistry trustedIssuerRegistry(@Qualifier("edgeIdpStarterProperties") IdpStarterProperties properties) {
        return new TrustedIssuerRegistry(properties);
    }

    @Bean(initMethod = "initialize")
    @ConditionalOnMissingBean(TenantIssuerAuthenticationManagerComponent.class)
    public TenantIssuerAuthenticationManagerComponent tenantIssuerAuthenticationManagerComponent(TrustedIssuerRegistry registry,
            @Qualifier("edgeIdpStarterProperties") IdpStarterProperties properties, @Qualifier("edgeClock") Clock clock) {
        return new TenantIssuerAuthenticationManagerComponent(registry, properties, clock);
    }

    @Bean(name = "webSessionManager")
    public WebSessionManager forbiddenWebSessionManager() {
        return exchange -> Mono.error(new EdgeOAuthStateException("EDGE_WEB_SESSION_FORBIDDEN"));
    }

    @Bean
    public SecurityWebFilterChain edgeSecurityWebFilterChain(ServerHttpSecurity http,
            @Qualifier("edgeOAuthProperties") EdgeOAuthProperties properties,
            ReactiveClientRegistrationRepository registrations, EdgeAuthorizationRequestResolver resolver,
            EdgeSqlAuthorizationRequestRepository requests, EdgeCookieAuthorizedClientRepository clients,
            @Qualifier("edgeOidcAuthenticationManager") ReactiveAuthenticationManager oidc,
            EdgeOAuthApplicationCatalog catalog, EdgeOAuthCookieService cookies, EdgeCsrfProofService csrf,
            EdgeIdentityStateService identities, EdgeOAuthTransactionRepository transactions, EdgeRevocationIntentRepository revocations) {
        var redirects = new DefaultServerRedirectStrategy();
        redirects.setHttpStatus(HttpStatus.SEE_OTHER);
        return http.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(cors(properties)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, failure) -> EdgeProtocolErrors.write(exchange, HttpStatus.UNAUTHORIZED, "invalid_token"))
                        .accessDeniedHandler((exchange, failure) -> EdgeProtocolErrors.write(exchange, HttpStatus.FORBIDDEN, "access_denied")))
                .authorizeExchange(access -> access
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", PUBLIC_I18N_PATH, PUBLIC_APP_I18N_PATH).permitAll()
                        .pathMatchers(HttpMethod.GET, "/apps/*/auth/login", "/apps/*/auth/callback", "/apps/*/auth/csrf", "/apps/*/auth/refresh-operations/*").permitAll()
                        .pathMatchers(HttpMethod.POST, "/apps/*/auth/refresh", "/apps/*/auth/logout").permitAll()
                        .pathMatchers("/apps/*/auth/identity", "/apps/*/api/**", "/api/**", "/mcp/**", "/v1/**").authenticated()
                        .anyExchange().denyAll())
                .addFilterBefore(new EdgeOAuthCallbackLifecycleFilter(transactions, revocations), SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAt(apiAuthentication(catalog, cookies, identities), SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(new EdgeRefreshProofWebFilter(catalog, cookies, identities), SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterBefore(new EdgeCookieCsrfWebFilter(cookies, csrf, properties), SecurityWebFiltersOrder.AUTHORIZATION)
                .oauth2Login(login -> login.clientRegistrationRepository(registrations)
                        .authorizationRequestResolver(resolver).authorizationRequestRepository(requests)
                        .authorizedClientRepository(clients).authenticationManager(oidc)
                        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                        .loginPage("/apps/" + properties.getDefaultRegistrationId() + "/auth/login")
                        .authenticationMatcher(new PathPatternParserServerWebExchangeMatcher("/apps/{appAlias}/auth/callback", HttpMethod.GET))
                        .authenticationSuccessHandler((web, authentication) -> {
                            var exchange = web.getExchange();
                            EdgeOAuthExchangeClaimBO claim = exchange.getAttribute(EdgeSqlAuthorizationRequestRepository.EXCHANGE_CLAIM);
                            EdgeOAuthCallbackContext callback = exchange.getAttribute(EdgeSqlAuthorizationRequestRepository.CALLBACK);
                            if (claim == null || callback == null || callback.verifiedContext() == null
                                    || !properties.getAllowedReturnPaths().contains(claim.transaction().returnPath())) {
                                return Mono.error(new EdgeOAuthStateException("EDGE_CODE_CALLBACK_INVALID"));
                            }
                            exchange.getResponse().getHeaders().setCacheControl("no-store");
                            exchange.getResponse().getHeaders().set("Referrer-Policy", "no-referrer");
                            return redirects.sendRedirect(exchange, properties.getPublicOrigin().resolve(claim.transaction().returnPath()))
                                    .doOnSuccess(ignored -> callback.complete());
                        })
                        .authenticationFailureHandler((web, failure) -> EdgeProtocolErrors.write(web.getExchange(), failure)))
                .build();
    }

    @Bean
    public io.swagger.v3.oas.models.OpenAPI edgeOpenAPI() {
        var alias = new io.swagger.v3.oas.models.parameters.Parameter().name("appAlias").in("path").required(true)
                .schema(new io.swagger.v3.oas.models.media.StringSchema().pattern("[A-Za-z0-9_-]{1,128}"));
        var login = new io.swagger.v3.oas.models.Operation().operationId("edgeLogin")
                .summary("Start a one-use OAuth transaction for a configured application").security(List.of()).addParametersItem(alias)
                .addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("returnTo").in("query")
                        .description("Optional exact member of the configured local return-path allowlist")
                        .schema(new io.swagger.v3.oas.models.media.StringSchema()))
                .responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                        .addApiResponse("302", new io.swagger.v3.oas.models.responses.ApiResponse().description("Redirect to configured issuer with PKCE, state and nonce"))
                        .addApiResponse("400", new io.swagger.v3.oas.models.responses.ApiResponse().description("Invalid application or return path"))
                        .addApiResponse("503", new io.swagger.v3.oas.models.responses.ApiResponse().description("OAuth state owner unavailable")));
        var callback = new io.swagger.v3.oas.models.Operation().operationId("edgeOAuthCallback")
                .summary("Consume the stored OAuth transaction once").security(List.of()).addParametersItem(alias)
                .description("Successful verification writes application-scoped HttpOnly credential cookies and redirects. No Session or reusable token-retrieval endpoint exists.")
                .responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                        .addApiResponse("303", new io.swagger.v3.oas.models.responses.ApiResponse().description("Resume the fixed local return path"))
                        .addApiResponse("400", new io.swagger.v3.oas.models.responses.ApiResponse().description("Invalid or consumed OAuth transaction"))
                        .addApiResponse("401", new io.swagger.v3.oas.models.responses.ApiResponse().description("OAuth identity rejected"))
                        .addApiResponse("503", new io.swagger.v3.oas.models.responses.ApiResponse().description("Identity owner or state confirmation unavailable")));
        for (String parameter : List.of("state", "code", "error", "iss")) {
            callback.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name(parameter).in("query")
                    .required("state".equals(parameter)).schema(new io.swagger.v3.oas.models.media.StringSchema()));
        }
        return new io.swagger.v3.oas.models.OpenAPI().info(new io.swagger.v3.oas.models.info.Info().title("Yuheng OAuth Edge").version("2.0"))
                .paths(new io.swagger.v3.oas.models.Paths()
                        .addPathItem("/apps/{appAlias}/auth/login", new io.swagger.v3.oas.models.PathItem().get(login))
                        .addPathItem("/apps/{appAlias}/auth/callback", new io.swagger.v3.oas.models.PathItem().get(callback)))
                .components(new io.swagger.v3.oas.models.Components().addSecuritySchemes("edgeCsrfHeader", new io.swagger.v3.oas.models.security.SecurityScheme()
                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.APIKEY).in(io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER)
                        .name("X-CSRF-TOKEN")));
    }

    private static AuthenticationWebFilter apiAuthentication(EdgeOAuthApplicationCatalog catalog,
            EdgeOAuthCookieService cookies, EdgeIdentityStateService identities) {
        ReactiveAuthenticationManagerResolver<ServerWebExchange> managers = exchange -> Mono.just(authentication -> Mono.defer(() -> {
            if (!(authentication instanceof BearerTokenAuthenticationToken bearer)) {
                return Mono.error(new OAuth2AuthenticationException(new OAuth2Error("invalid_request")));
            }
            boolean browser = exchange.getRequest().getPath().value().startsWith("/apps/");
            String alias = browser ? EdgeOAuthApplicationCatalog.appAlias(exchange)
                    : catalog.aliasForDirectPath(exchange.getRequest().getPath().value());
            return identities.access(alias, bearer.getToken(), browser)
                    .switchIfEmpty(Mono.error(new EdgeOAuthStateException("EDGE_ACCESS_REJECTED")))
                    .map(access -> {
                        exchange.getAttributes().put(EdgeIdentityStateService.ACCESS_ATTRIBUTE, access);
                        return (org.springframework.security.core.Authentication) new JwtAuthenticationToken(access.proof().getJwt(),
                                new JwtGrantedAuthoritiesConverter().convert(access.proof().getJwt()), access.proof().getSubject());
                    });
        }).onErrorMap(failure -> new OAuth2AuthenticationException(new OAuth2Error(
                EdgeProtocolErrors.status(failure) == HttpStatus.SERVICE_UNAVAILABLE ? "temporarily_unavailable"
                        : EdgeProtocolErrors.status(failure) == HttpStatus.BAD_REQUEST ? "invalid_request" : "invalid_token"))));
        var filter = new AuthenticationWebFilter(managers);
        filter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        filter.setRequiresAuthenticationMatcher(exchange -> {
            String path = exchange.getRequest().getPath().value();
            boolean required = !publicI18n(exchange) && (path.matches("/apps/[A-Za-z0-9_-]{1,128}/(?:api(?:/.*)?|auth/identity)")
                    || path.startsWith("/api/") || path.startsWith("/mcp/") || path.startsWith("/v1/"));
            return required ? ServerWebExchangeMatcher.MatchResult.match() : ServerWebExchangeMatcher.MatchResult.notMatch();
        });
        var bearerConverter = new ServerBearerTokenAuthenticationConverter();
        filter.setServerAuthenticationConverter(exchange -> Mono.defer(() -> {
            if (exchange.getRequest().getPath().value().startsWith("/apps/")) {
                catalog.requireBrowserOrigin(exchange);
                cookies.rejectAmbientAuthorization(exchange);
                return cookies.access(exchange, EdgeOAuthApplicationCatalog.appAlias(exchange))
                        .map(value -> Mono.<org.springframework.security.core.Authentication>just(new BearerTokenAuthenticationToken(value)))
                        .orElseGet(Mono::empty);
            }
            if (cookies.containsCredentialCookie(exchange) || exchange.getRequest().getHeaders().getOrEmpty("Authorization").size() > 1) {
                return Mono.error(new OAuth2AuthenticationException(new OAuth2Error("invalid_request")));
            }
            return bearerConverter.convert(exchange);
        }));
        filter.setAuthenticationFailureHandler((web, failure) -> EdgeProtocolErrors.write(web.getExchange(), failure));
        return filter;
    }

    public static boolean publicI18n(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.GET) return false;
        String path = exchange.getRequest().getPath().value();
        if (PUBLIC_I18N_PATH.equals(path)) return true;
        var query = exchange.getRequest().getQueryParams();
        return PUBLIC_APP_I18N_PATH.equals(path) && query.keySet().stream().allMatch("locale"::equals)
                && query.values().stream().allMatch(values -> values.size() == 1 && values.getFirst().length() <= 128);
    }

    private static UrlBasedCorsConfigurationSource cors(EdgeOAuthProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(properties.getPublicOrigin().toASCIIString()));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-CSRF-TOKEN"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
