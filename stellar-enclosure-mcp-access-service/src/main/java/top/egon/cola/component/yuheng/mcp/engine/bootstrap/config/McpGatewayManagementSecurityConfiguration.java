package top.egon.cola.component.yuheng.mcp.engine.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 中文说明：MCP 管理端仅开放健康探针，数据面的身份与权限仍由 MCP 处理器执行。
 * English summary: Keeps the MCP management server stateless and separate from data-plane authorization.
 */
@Slf4j
@Configuration(value = "mcpGatewayManagementSecurityConfiguration", proxyBeanMethods = false)
@EnableConfigurationProperties(McpGatewayManagementSecurityConfiguration.ProtocolAsyncProperties.class)
public class McpGatewayManagementSecurityConfiguration {

    private static final String MCP = "/mcp/{serverCode}";
    private static final String LEGACY = "/legacy/mcp/{serverCode}";
    private static final String RESOURCE_METADATA = "/.well-known/oauth-protected-resource/mcp/{serverCode}";

    /** The protocol controller delegates identity and authorization to the MCP handler. */
    @Bean(name = "mcpGatewayManagementSecurityFilterChain")
    SecurityFilterChain mcpGatewayManagementSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info",
                                MCP, LEGACY, RESOURCE_METADATA).permitAll()
                        .requestMatchers(HttpMethod.POST, MCP, LEGACY).permitAll()
                        .requestMatchers(HttpMethod.DELETE, MCP, LEGACY).permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    /** Composes the shared audit readiness with this existing health-only management surface. */
    @Bean(name = "mcpGatewayAuditReadiness")
    org.springframework.boot.health.contributor.HealthIndicator auditReadiness(
            @Qualifier("idpSecurityAuditReady") java.util.function.BooleanSupplier ready) {
        return () -> ready.getAsBoolean() ? org.springframework.boot.health.contributor.Health.up().build()
                : org.springframework.boot.health.contributor.Health.down().withDetail("failedCheck", "oauthAudit").build();
    }

    @Bean(name = "mcpGatewaySecurityObservationStartupCheck")
    Object securityObservationStartupCheck(
            @Qualifier("oauthUsageObservationProperties") top.egon.cola.platform.tianquan.shoubing.starter.audit.OAuthUsageObservationProperties usage,
            @Qualifier("idpSecurityAuditReady") java.util.function.BooleanSupplier ready,
            @Qualifier("idpStarterProperties") top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties identity,
            @Qualifier("defaultValidator") jakarta.validation.Validator validator,
            @Qualifier("environment") org.springframework.core.env.Environment environment) {
        new top.egon.cola.component.common.core.validation.ValidationUtils(validator).validate(usage);
        identity.validate();
        int servicePort = environment.getProperty("server.port", Integer.class, 8080);
        int managementPort = environment.getProperty("management.server.port", Integer.class, servicePort);
        if (managementPort < 0 || (servicePort > 0 && servicePort == managementPort)) {
            throw new IllegalStateException("MCP_MANAGEMENT_PORT_NOT_SEPARATE");
        }
        if (!usage.isEnabled() || !ready.getAsBoolean() || identity.getTrustedIssuers().isEmpty()
                || identity.getResourceUri() == null || !"https".equals(identity.getResourceUri().getScheme())
                || identity.getTrustedIssuers().values().stream().anyMatch(issuer -> !issuer.getIssuer().startsWith("https://") || !issuer.getJwksUri().startsWith("https://"))
                || environment.getProperty("server.ssl.enabled", Boolean.class, false)
                || environment.getProperty("management.server.ssl.enabled", Boolean.class, false)
                || !"none".equals(environment.getProperty("server.forward-headers-strategy", "none"))
                || !"h2c".equals(environment.getProperty("egon.cola.component.rpc.transport", "h2c"))) {
            throw new IllegalStateException("YUHENG_SECURITY_OBSERVATION_NOT_READY");
        }
        return new Object();
    }

    @Bean("mcpProtocolAsyncExecutor")
    ThreadPoolTaskExecutor protocolAsyncExecutor(ProtocolAsyncProperties settings) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mcp-protocol-");
        executor.setCorePoolSize(settings.concurrentStreams());
        executor.setMaxPoolSize(settings.concurrentStreams());
        executor.setQueueCapacity(0);
        executor.setAllowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean("mcpProtocolMvcConfigurer")
    WebMvcConfigurer protocolMvcConfigurer(
            @Qualifier("mcpProtocolAsyncExecutor") ThreadPoolTaskExecutor executor,
            ProtocolAsyncProperties settings) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(executor);
                configurer.setDefaultTimeout(settings.timeout().toMillis());
            }
        };
    }

    /** Bounded concurrency and lifetime of protocol-response workers. */
    @ConfigurationProperties(value = "egon.cola.component.yuheng.mcp-engine.mvc-async", ignoreUnknownFields = false)
    public record ProtocolAsyncProperties(@DefaultValue("64") int concurrentStreams,
            @DefaultValue("90s") Duration timeout) {
        public ProtocolAsyncProperties {
            if (concurrentStreams < 1 || concurrentStreams > 512 || timeout == null
                    || timeout.compareTo(Duration.ofSeconds(1)) < 0
                    || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("MCP_ASYNC_SETTINGS_INVALID");
            }
        }
    }
}
