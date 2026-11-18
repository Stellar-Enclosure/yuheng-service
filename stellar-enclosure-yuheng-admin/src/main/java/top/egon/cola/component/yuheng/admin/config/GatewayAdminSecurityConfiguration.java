package top.egon.cola.component.yuheng.admin.config;


import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpSecurityObservationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.vo.GatewayAdminErrorVO;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpBearerAuthenticationFilter;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpEndpointAuthenticationPolicy;
import top.egon.cola.platform.tianquan.jianshen.starter.security.Rbac3BearerAuthenticationFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 中文说明：{@code GatewayAdminSecurityConfiguration} 是配置类，位于当前 Gateway 模块的相关包中，负责网关管理端安全配置相关的职责与边界。
 * English summary: {@code GatewayAdminSecurityConfiguration} is a gateway admin security configuration configuration in the current Gateway module; it owns the gateway admin security configuration-related responsibility and boundary.
 *
 * 用法 / Usage: 通过 Spring 容器或上层组件使用该类型；/ Use this type through the Spring container or an enclosing component; its public contract is the supported extension and invocation boundary.
 */
@Slf4j
@Configuration(value = "gatewayAdminSecurityConfiguration", proxyBeanMethods = false)
@EnableMethodSecurity
public class GatewayAdminSecurityConfiguration {

    /** Allows the management API to serve both Tianquan-Jianshen users and scoped publishers. */
    @Bean("gatewayAdminEndpointAuthenticationPolicy")
    public IdpEndpointAuthenticationPolicy
            gatewayAdminEndpointAuthenticationPolicy() {
        return new IdpEndpointAuthenticationPolicy(
                List.of(),
                List.of(),
                List.of("/api/v1/yuheng/admin/**"),
                true
        );
    }

    /**
     * Creates the narrow SERVICE-scope adapter used by Gateway Admin control-plane calls.
     *
     * @return service-scope authority adapter
     */
    @Bean("gatewayAdminServiceAuthorityFilter")
    public GatewayAdminServiceAuthorityFilter gatewayAdminServiceAuthorityFilter() {
        return new GatewayAdminServiceAuthorityFilter();
    }

    /**
     * 中文说明：执行 网关管理端安全过滤器Chain 操作；该方法是 {@code GatewayAdminSecurityConfiguration} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the gateway admin security filter chain operation; this method is the invocation entry point on {@code GatewayAdminSecurityConfiguration} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code GatewayAdminSecurityConfiguration.gatewayAdminSecurityFilterChain(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param http 参数 http；parameter http。
     * @param objectMapper 参数 object映射器；parameter object mapper。
     * @param idpFilters 参数 idpFilters；parameter idp filters。
     * @param rbac3Filters 参数 rbac3Filters；parameter rbac3 filters。
     * @return 返回 网关管理端安全过滤器Chain 的处理结果；returns the result of the operation.
     */
    @Bean("gatewayAdminSecurityFilterChain")
    public SecurityFilterChain gatewayAdminSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("jacksonObjectMapper") ObjectMapper objectMapper,
            @Qualifier("idpBearerAuthenticationFilter") ObjectProvider<IdpBearerAuthenticationFilter> idpFilters,
            @Qualifier("rbac3BearerAuthenticationFilter") ObjectProvider<Rbac3BearerAuthenticationFilter> rbac3Filters,
            @Qualifier("gatewayAdminServiceAuthorityFilter") ObjectProvider<GatewayAdminServiceAuthorityFilter> serviceAuthorityFilters,
            @Qualifier("idpSecurityObservationFilter") ObjectProvider<IdpSecurityObservationFilter> observations)
            throws Exception {
        IdpBearerAuthenticationFilter idpFilter = idpFilters.getIfAvailable();
        Rbac3BearerAuthenticationFilter rbac3Filter = rbac3Filters.getIfAvailable();
        GatewayAdminServiceAuthorityFilter serviceAuthorityFilter =
                serviceAuthorityFilters.getIfAvailable();
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                ))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/v1/yuheng/openapi/interface-definitions/**",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, error) ->
                                writeSecurityError(request, response, objectMapper,
                                        HttpServletResponse.SC_UNAUTHORIZED,
                                        isExposureRequest(request) ? "YUHENG_ADMIN_UNAUTHENTICATED"
                                        : "YUHENG_ADMIN_AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, error) ->
                                writeSecurityError(request, response, objectMapper,
                                        HttpServletResponse.SC_FORBIDDEN,
                                        isExposureRequest(request) ? "YUHENG_EXPOSURE_ACCESS_DENIED"
                                                : "YUHENG_ADMIN_CAPABILITY_REQUIRED")))
                .headers(headers -> headers.addHeaderWriter((request, response) -> {
                    if (isExposureRequest(request)) {
                        exposureResponseHeaders(request, response);
                    }
                }));
        if (idpFilter != null && rbac3Filter != null
                && serviceAuthorityFilter != null) {
            http.addFilterBefore(idpFilter, AnonymousAuthenticationFilter.class);
            http.addFilterBefore(exposureBearerErrors(objectMapper), IdpBearerAuthenticationFilter.class);
            var observation = observations.getIfAvailable();
            if (observation != null) { http.addFilterBefore(observation, IdpBearerAuthenticationFilter.class); }
            http.addFilterAfter(
                    serviceAuthorityFilter,
                    IdpBearerAuthenticationFilter.class);
            http.addFilterAfter(
                    rbac3Filter,
                    GatewayAdminServiceAuthorityFilter.class);
        } else {
            throw new IllegalStateException(
                    "Tianquan-Shoubing, Gateway service, and Tianquan-Jianshen authentication filters must be configured together");
        }
        return http.build();
    }

    /**
     * 中文说明：执行 write安全Error 操作；该方法是 {@code GatewayAdminSecurityConfiguration} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the write security error operation; this method is the invocation entry point on {@code GatewayAdminSecurityConfiguration} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code GatewayAdminSecurityConfiguration.writeSecurityError(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param response 参数 响应；parameter response。
     * @param objectMapper 参数 object映射器；parameter object mapper。
     * @param status 参数 status；parameter status。
     * @param code 参数 code；parameter code。
     */
    private void writeSecurityError(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String code) throws IOException {
        boolean delivered = status == HttpServletResponse.SC_UNAUTHORIZED
                ? IdpSecurityObservationFilter.beforeAuthenticationFailure(request) : IdpSecurityObservationFilter.beforeAuthorizationDenied(request);
        response.setStatus(delivered ? status : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Cache-Control", "no-store");
        if (delivered && status == HttpServletResponse.SC_UNAUTHORIZED) { response.setHeader("WWW-Authenticate", "Bearer"); }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (isExposureRequest(request)) {
            exposureResponseHeaders(request, response);
            String publicCode = delivered ? code : "OAUTH_AUDIT_DELIVERY_UNAVAILABLE";
            objectMapper.writeValue(response.getOutputStream(), new GatewayAdminErrorVO(publicCode, publicCode,
                    null, java.util.List.of(), Instant.now()));
            return;
        }
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "code", delivered ? code : "OAUTH_AUDIT_DELIVERY_UNAVAILABLE",
                "message", delivered ? code : "OAUTH_AUDIT_DELIVERY_UNAVAILABLE",
                "timestamp", Instant.now().toString()
        ));
    }
    private static OncePerRequestFilter exposureBearerErrors(ObjectMapper mapper) {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !isExposureRequest(request) || request.getHeader(HttpHeaders.AUTHORIZATION) == null;
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                // The shared bearer filter may write its own 401 before the normal Admin entry point.
                var captured = new ContentCachingResponseWrapper(response);
                chain.doFilter(request, captured);
                if (captured.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
                    captured.resetBuffer();
                    captured.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    exposureResponseHeaders(request, captured);
                    captured.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(captured.getOutputStream(), new GatewayAdminErrorVO(
                            "YUHENG_ADMIN_UNAUTHENTICATED", "Authentication is required", null, List.of(), Instant.now()));
                }
                captured.copyBodyToResponse();
            }
        };
    }

    private static boolean isExposureRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/yuheng/admin/exposures/");
    }

    private static void exposureResponseHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!response.containsHeader("x-egon-request-id")) {
            response.setHeader("x-egon-request-id", RequestAuditContext.current(request.getHeader("x-egon-request-id")).requestId());
        }
    }

    @Bean("gatewayAdminAuditReadinessHealthIndicator")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "egon.cola.platform.tianquan.shoubing.usage-observation", name = "enabled", havingValue = "true")
    org.springframework.boot.health.contributor.HealthIndicator gatewayAdminAuditReadinessHealthIndicator(
            @Qualifier("idpSecurityAuditReady") java.util.function.BooleanSupplier ready) {
        return () -> ready.getAsBoolean() ? org.springframework.boot.health.contributor.Health.up().build()
                : org.springframework.boot.health.contributor.Health.down().build();
    }

    @Bean("gatewayAdminIdentityStartupGuard")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "egon.cola.platform.tianquan.shoubing.usage-observation", name = "enabled", havingValue = "true")
    Object gatewayAdminIdentityStartupGuard(@Qualifier("environment") org.springframework.core.env.Environment environment,
            @Qualifier("idpStarterProperties") top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties properties,
            @Qualifier("idpSecurityAuditReady") java.util.function.BooleanSupplier ready) {
        properties.validate();
        if (!ready.getAsBoolean() || !properties.isMultiIssuerConfigured() || !environment.getProperty("server.ssl.enabled", Boolean.class, false)) {
            throw new IllegalStateException("OAUTH_HOST_SECURITY_NOT_READY");
        }
        if (properties.getTrustedIssuers().values().stream().anyMatch(issuer -> !issuer.getIssuer().startsWith("https://") || !issuer.getJwksUri().startsWith("https://"))
                || !"https".equalsIgnoreCase(properties.getResourceUri().getScheme())
                || !environment.getProperty("egon.cola.platform.tianquan.jianshen.authorization.endpoint", "").startsWith("https://")) {
            throw new IllegalStateException("OAUTH_HOST_TLS_REQUIRED");
        }
        return Boolean.TRUE;
    }

}
