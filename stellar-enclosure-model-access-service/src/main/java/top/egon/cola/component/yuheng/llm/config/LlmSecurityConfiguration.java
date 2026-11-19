package top.egon.cola.component.yuheng.llm.config;

import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpBearerAuthenticationFilter;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpEndpointAuthenticationPolicy;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpSecurityObservationFilter;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.TokenAuditProofBO;
import top.egon.cola.component.yuheng.llm.proxy.domain.enums.LlmProtocolEnum;
import top.egon.cola.component.yuheng.llm.proxy.domain.exception.LlmInvocationException;
import top.egon.cola.component.yuheng.llm.proxy.service.LlmProtocolStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import top.egon.cola.platform.tianquan.jianshen.starter.autoconfigure.Rbac3StarterProperties;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import top.egon.cola.platform.tianquan.shoubing.contract.IdpPrincipal;

/** Protects native model entries with the shared verifier, scope gate and original model authorization. */
@Slf4j
@Configuration(value = "llmSecurityConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties(Rbac3StarterProperties.class)
public class LlmSecurityConfiguration {

    /** Host-owned business cache connection; authorization and revocation continue through their SQL authority clients. */
    @Bean(name = "redissonClient", destroyMethod = "shutdown")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = "redissonClient")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "yuheng.llm.cache", name = "enabled", havingValue = "true")
    org.redisson.api.RedissonClient applicationCacheRedissonClient(
            @Qualifier(LlmGatewayProperties.BEAN_NAME) LlmGatewayProperties gatewayProperties) throws java.io.IOException {
        var cache = gatewayProperties.getCache();
        var redis = cache.getRedis();
        String address = redis.getAddress();
        int database = redis.getDatabase();
        String passwordFile = redis.getPasswordFile();
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("YUHENG_CACHE_REDIS_CONFIGURATION_INVALID");
        }
        var endpoint = java.net.URI.create(address);
        if (!java.util.Set.of("redis", "rediss").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || database < 0) throw new IllegalArgumentException("YUHENG_CACHE_REDIS_CONFIGURATION_INVALID");
        var config = new org.redisson.config.Config();
        var server = config.useSingleServer().setAddress(address).setDatabase(database)
                .setConnectTimeout(cache.getTimeoutMs()).setTimeout(cache.getTimeoutMs()).setRetryAttempts(0);
        if (passwordFile != null && !passwordFile.isBlank()) {
            server.setPassword(java.nio.file.Files.readString(java.nio.file.Path.of(passwordFile)).strip());
        }
        return org.redisson.Redisson.create(config);
    }

    private static final String BUSINESS_ENTERED = LlmSecurityConfiguration.class.getName() + ".businessEntered";
    private static final Map<String, LlmProtocolEnum> PROTOCOLS = Map.of(
            "/v1/models", LlmProtocolEnum.OPENAI_CHAT, "/v1/model-policies", LlmProtocolEnum.OPENAI_CHAT, "/v1/chat/completions", LlmProtocolEnum.OPENAI_CHAT,
            "/v1/embeddings", LlmProtocolEnum.OPENAI_EMBEDDING, "/v1/responses", LlmProtocolEnum.OPENAI_RESPONSES,
            "/v1/messages", LlmProtocolEnum.ANTHROPIC_MESSAGES);

    @Bean(name = "idpEndpointAuthenticationPolicy")
    IdpEndpointAuthenticationPolicy idpEndpointAuthenticationPolicy() {
        return new IdpEndpointAuthenticationPolicy(List.of("/actuator/health/**", "/actuator/info"), List.of(), PROTOCOLS.keySet(), false);
    }

    @Bean(name = "llmSecurityFilterChain")
    @Order(0)
    SecurityFilterChain llmSecurityFilterChain(HttpSecurity http,
            @Qualifier("idpBearerAuthenticationFilter") IdpBearerAuthenticationFilter bearer,
            @Qualifier("idpSecurityObservationFilter") IdpSecurityObservationFilter observation,
            @Qualifier("llmVerifiedPrincipalRequestFilter") OncePerRequestFilter projection,
            @Qualifier("llmProtocolSecurityResponseFilter") OncePerRequestFilter protocol,
            @Qualifier(LlmPersistenceProperties.BEAN_NAME) LlmPersistenceProperties persistence) throws Exception {
        Long identityTenantId = persistence.getIdentityTenantId();
        if (identityTenantId == null || identityTenantId <= 0) {
            throw new IllegalStateException("LLM_IDENTITY_TENANT_REQUIRED");
        }
        String expectedIdentityTenant = identityTenantId.toString();
        http.securityMatcher("/v1/**").csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .addFilterBefore(bearer, AnonymousAuthenticationFilter.class)
                .addFilterBefore(observation, IdpBearerAuthenticationFilter.class)
                .addFilterBefore(protocol, IdpSecurityObservationFilter.class)
                .addFilterAfter(projection, AuthorizationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/v1/models", "/v1/model-policies").access(scope("yuheng:llm:read", expectedIdentityTenant))
                        .requestMatchers(HttpMethod.POST, "/v1/chat/completions", "/v1/embeddings", "/v1/responses", "/v1/messages")
                        .access(scope("yuheng:llm:invoke", expectedIdentityTenant)).anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) ->
                        response.setStatus(IdpSecurityObservationFilter.authorizationObservationContext(request) != null
                                && IdpSecurityObservationFilter.beforeAuthenticationFailure(request) ? 401 : 503))
                        .accessDeniedHandler((request, response, failure) -> {
                            var marker = IdpSecurityObservationFilter.authorizationObservationContext(request);
                            response.setStatus(marker != null && IdpSecurityObservationFilter.beforeAuthorizationDenied(request) ? 403 : 503);
                        }));
        return http.build();
    }

    /** The connector's local port is authoritative; forwarded headers cannot select a listener. */
    @Bean(name = "llmManagementSecurityFilterChain")
    @Order(-1)
    SecurityFilterChain llmManagementSecurityFilterChain(HttpSecurity http,
            @Qualifier("environment") Environment environment) throws Exception {
        int servicePort = environment.getRequiredProperty("server.port", Integer.class);
        int managementPort = environment.getRequiredProperty("management.server.port", Integer.class);
        return http.securityMatcher(request -> request.getLocalPort() != servicePort)
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .securityContext(context -> context.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize.requestMatchers(request -> {
                    String path = request.getRequestURI();
                    return request.getLocalPort() == managementPort && "GET".equals(request.getMethod())
                            && ("/actuator/health".equals(path) || path.startsWith("/actuator/health/")
                            || "/actuator/info".equals(path));
                }).permitAll().anyRequest().denyAll()).build();
    }

    /** Optional OpenAPI has its own earlier authenticated chain; every remaining surface is closed. */
    @Bean(name = "llmDefaultSecurityFilterChain")
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain llmDefaultSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .securityContext(context -> context.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll()).build();
    }

    private AuthorizationManager<RequestAuthorizationContext> scope(String value, String expectedIdentityTenant) {
        return (authentication, context) -> {
            var current = authentication.get();
            return new AuthorizationDecision(current != null && current.isAuthenticated()
                    && current.getPrincipal() instanceof IdpPrincipal principal
                    && expectedIdentityTenant.equals(principal.tenantId())
                    && current.getDetails() instanceof TokenAuditProofBO
                    && current.getAuthorities().stream().anyMatch(authority -> ("SCOPE_" + value).equals(authority.getAuthority())));
        };
    }

    @Bean(name = "llmProtocolSecurityResponseFilter")
    OncePerRequestFilter llmProtocolSecurityResponseFilter(@Qualifier("jacksonObjectMapper") ObjectMapper mapper,
            @Qualifier("llmSecurityProtocolStrategies") Map<LlmProtocolEnum, LlmProtocolStrategy> strategies) {
        return new ProtocolSecurityResponseFilter(mapper, strategies);
    }

    @Bean(name = "llmSecurityProtocolStrategies")
    Map<LlmProtocolEnum, LlmProtocolStrategy> llmSecurityProtocolStrategies(
            @Qualifier("llmProtocolStrategyRegistry") Map<LlmProtocolEnum, String> registry,
            Map<String, LlmProtocolStrategy> candidates) {
        var selected = new java.util.EnumMap<LlmProtocolEnum, LlmProtocolStrategy>(LlmProtocolEnum.class);
        registry.forEach((protocol, name) -> {
            var strategy = candidates.get(name);
            if (strategy == null || strategy.protocol() != protocol) { throw new IllegalStateException("LLM_SECURITY_PROTOCOL_REQUIRED"); }
            selected.put(protocol, strategy);
        });
        if (selected.size() != LlmProtocolEnum.values().length) { throw new IllegalStateException("LLM_SECURITY_PROTOCOL_REQUIRED"); }
        return Map.copyOf(selected);
    }

    @Bean(name = "llmSecurityObservationStartupCheck")
    Object llmSecurityObservationStartupCheck(
            @Qualifier("idpObservedVerificationComponent") top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent observation,
            @Qualifier("oauthUsageObservationProperties") top.egon.cola.platform.tianquan.shoubing.starter.audit.OAuthUsageObservationProperties usage,
            @Qualifier("idpSecurityAuditReady") java.util.function.BooleanSupplier ready,
            @Qualifier("defaultValidator") jakarta.validation.Validator validator,
            @Qualifier("idpStarterProperties") top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties properties,
            @Qualifier("environment") org.springframework.core.env.Environment environment) {
        new top.egon.cola.component.common.core.validation.ValidationUtils(validator).validate(usage);
        if (!observation.enabled() || !ready.getAsBoolean()) {
            throw new IllegalStateException("LLM_SECURITY_OBSERVATION_REQUIRED");
        }
        Integer servicePort = environment.getProperty("server.port", Integer.class);
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        if (servicePort == null || managementPort == null || servicePort <= 0 || servicePort > 65535
                || managementPort <= 0 || managementPort > 65535 || servicePort.equals(managementPort)) {
            throw new IllegalStateException("LLM_MANAGEMENT_PORT_NOT_SEPARATE");
        }
        properties.validate();
        if (!"https".equals(properties.getResourceUri().getScheme()) || properties.getTrustedIssuers().isEmpty()
                || properties.getTrustedIssuers().values().stream().anyMatch(issuer -> !issuer.getIssuer().startsWith("https://") || !issuer.getJwksUri().startsWith("https://"))
                || applicationTlsConfigured(environment, "server.ssl.")
                || applicationTlsConfigured(environment, "management.server.ssl.")
                || !"none".equals(environment.getProperty("server.forward-headers-strategy", "none"))) {
            throw new IllegalStateException("LLM_SECURITY_TRANSPORT_REQUIRED");
        }
        return new Object();
    }

    private static boolean applicationTlsConfigured(Environment environment, String prefix) {
        if (environment.getProperty(prefix + "enabled", Boolean.class, false)) { return true; }
        return List.of("bundle", "key-store", "key-store-password", "key-password", "certificate",
                        "certificate-private-key", "trust-store", "trust-store-password", "trust-certificate")
                .stream().anyMatch(key -> {
                    String value = environment.getProperty(prefix + key);
                    return value != null && !value.isBlank();
                });
    }

    @Bean(name = "llmAuditReadiness")
    org.springframework.boot.health.contributor.HealthIndicator llmAuditReadiness(
            @Qualifier("idpSecurityAuditReady") java.util.function.BooleanSupplier ready) {
        return () -> ready.getAsBoolean() ? org.springframework.boot.health.contributor.Health.up().build()
                : org.springframework.boot.health.contributor.Health.down().withDetail("failedCheck", "oauthAudit").build();
    }
    /** Adapts the verified identity to the existing controller's Servlet Principal contract. */
    @Bean(name = "llmVerifiedPrincipalRequestFilter")
    OncePerRequestFilter llmVerifiedPrincipalRequestFilter() { return new VerifiedPrincipalRequestFilter(); }

    @Bean(name = "llmVerifiedPrincipalFilterRegistration")
    org.springframework.boot.web.servlet.FilterRegistrationBean<OncePerRequestFilter> llmVerifiedPrincipalFilterRegistration(
            @Qualifier("llmVerifiedPrincipalRequestFilter") OncePerRequestFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean(name = "llmProtocolSecurityFilterRegistration")
    org.springframework.boot.web.servlet.FilterRegistrationBean<OncePerRequestFilter> llmProtocolSecurityFilterRegistration(
            @Qualifier("llmProtocolSecurityResponseFilter") OncePerRequestFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean(name = "llmSecurityOpenApiCustomizer")
    org.springdoc.core.customizers.GlobalOpenApiCustomizer llmSecurityOpenApiCustomizer() {
        Map<String, String> scopes = Map.of("listLlmModels", "yuheng:llm:read", "describeLlmModelPolicies", "yuheng:llm:read", "createLlmChatCompletion", "yuheng:llm:invoke",
                "createLlmEmbeddings", "yuheng:llm:invoke", "createLlmResponse", "yuheng:llm:invoke", "createLlmMessage", "yuheng:llm:invoke");
        return document -> {
            if (document.getPaths() == null) { return; }
            document.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                String scope = scopes.get(operation.getOperationId());
                if (scope == null) { return; }
                operation.addExtension("x-required-oauth-scopes", List.of(scope));
                operation.setDescription((operation.getDescription() == null ? "" : operation.getDescription())
                        + " A verified USER or SERVICE access token requires OAuth scope " + scope
                        + " and the existing model subject/capability rules. Security state or audit failure returns native 503.");
                if (operation.getResponses() != null && operation.getResponses().get("403") != null) {
                    operation.getResponses().get("403").setDescription("Required OAuth scope or model authorization is denied");
                }
            }));
        };
    }

    @Slf4j
    private static class VerifiedPrincipalRequestFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof IdpPrincipal identity) {
                String subject = identity.subject();
                if (request.getAttribute(BUSINESS_ENTERED) instanceof AtomicBoolean entered) { entered.set(true); }
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override public Principal getUserPrincipal() { return () -> subject; }
                    @Override public String getRemoteUser() { return subject; }
                }, response);
            } else { chain.doFilter(request, response); }
        }
    }

    /** Decorates security failures only; successful unary/SSE bytes are never buffered. */
    @Slf4j
    @RequiredArgsConstructor
    private static class ProtocolSecurityResponseFilter extends OncePerRequestFilter {
        @Qualifier("jacksonObjectMapper") private final ObjectMapper mapper;
        @Qualifier("llmSecurityProtocolStrategies") private final Map<LlmProtocolEnum, LlmProtocolStrategy> strategies;

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            LlmProtocolEnum protocol = PROTOCOLS.get(request.getRequestURI());
            if (protocol == null) { chain.doFilter(request, response); return; }
            var entered = new AtomicBoolean(); request.setAttribute(BUSINESS_ENTERED, entered);
            var guarded = new NativeSecurityResponse(request, response, entered);
            try {
                chain.doFilter(request, guarded);
                if (guarded.securityFailure() && !response.isCommitted()) {
                    int status = guarded.getStatus();
                    String code = status == 401 ? "authentication_error" : status == 403 ? "permission_denied" : "service_unavailable";
                    String message = status == 401 ? "A verified enterprise identity is required" : status == 403
                            ? "The verified identity lacks the required permission" : "Authentication or authorization is temporarily unavailable";
                    var strategy = strategies.get(protocol);
                    response.setStatus(status); response.setHeader("Cache-Control", "no-store"); response.setContentType("application/json");
                    if (status == 401) { response.setHeader("WWW-Authenticate", "Bearer"); }
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    mapper.writeValue(response.getOutputStream(), strategy.encodeError(new LlmInvocationException(status, code, null, message, status == 503)));
                }
            } finally { request.removeAttribute(BUSINESS_ENTERED); }
        }
    }

    /** Framework response adapter suppresses only a rejected security body, never the model's stream. */
    @Slf4j
    private static class NativeSecurityResponse extends HttpServletResponseWrapper {
        private final HttpServletRequest request;
        private final AtomicBoolean entered;
        private boolean forcedUnavailable;
        private boolean denialChecked;

        NativeSecurityResponse(HttpServletRequest request, HttpServletResponse response, AtomicBoolean entered) {
            super(response); this.request = request; this.entered = entered;
        }

        boolean securityFailure() { return forcedUnavailable || !entered.get() && (getStatus() == 401 || getStatus() == 403 || getStatus() == 503); }

        private void beforeWrite() {
            if (entered.get() && getStatus() == 403 && !denialChecked) {
                denialChecked = true;
                var marker = IdpSecurityObservationFilter.authorizationObservationContext(request);
                if (marker == null || !IdpSecurityObservationFilter.beforeAuthorizationDenied(request)) {
                    forcedUnavailable = true; super.setStatus(503);
                }
            }
        }

        @Override public void sendError(int status) { super.setStatus(status); }
        @Override public void sendError(int status, String message) { super.setStatus(status); }
        @Override public void flushBuffer() throws IOException { beforeWrite(); if (!securityFailure()) { super.flushBuffer(); } }
        @Override public PrintWriter getWriter() throws IOException { return new PrintWriter(new OutputStreamWriter(getOutputStream(), StandardCharsets.UTF_8)); }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            var target = super.getOutputStream();
            return new ServletOutputStream() {
                @Override public boolean isReady() { return target.isReady(); }
                @Override public void setWriteListener(WriteListener listener) { target.setWriteListener(listener); }
                @Override public void write(int value) throws IOException { beforeWrite(); if (!securityFailure()) { target.write(value); } }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { beforeWrite(); if (!securityFailure()) { target.write(bytes, offset, length); } }
                @Override public void flush() throws IOException { beforeWrite(); if (!securityFailure()) { target.flush(); } }
                @Override public void close() throws IOException { if (!securityFailure()) { target.close(); } }
            };
        }
    }
}
