package top.egon.cola.component.yuheng.mcp.engine.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpEndpointAuthenticationPolicy;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpSecurityObservationFilter;

import java.time.Clock;
import java.security.SecureRandom;
import java.util.Set;

/** Gives the management actions a separate, stateless USER security chain. */
@Slf4j
@EnableMethodSecurity
@Configuration(value = "mcpManagementSecurityConfiguration", proxyBeanMethods = false)
public class McpManagementSecurityConfiguration {
    private static final String APPROVALS = "/api/v1/yuheng/admin/mcp/approvals";
    private static final String TASKS = "/api/v1/yuheng/admin/mcp/tasks";
    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher ISSUE_APPROVAL = PATHS.matcher(HttpMethod.POST, APPROVALS);
    private static final RequestMatcher READ_TASKS = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.GET, TASKS), PATHS.matcher(HttpMethod.GET, TASKS + "/{id}"),
            PATHS.matcher(HttpMethod.HEAD, TASKS), PATHS.matcher(HttpMethod.HEAD, TASKS + "/{id}"));
    private static final RequestMatcher CANCEL_TASK = PATHS.matcher(HttpMethod.POST, TASKS + "/{id}/cancel");
    private static final RequestMatcher MANAGED = new OrRequestMatcher(ISSUE_APPROVAL, READ_TASKS, CANCEL_TASK);

    @Bean("mcpManagementTransactionTemplate")
    TransactionTemplate mcpManagementTransactionTemplate(
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(5);
        return template;
    }

    @Bean("mcpApprovalSecureRandom")
    SecureRandom mcpApprovalSecureRandom() {
        return new SecureRandom();
    }

    @Bean("mcpManagementSecurityFilterChain")
    @Order(10)
    SecurityFilterChain mcpManagementSecurityFilterChain(HttpSecurity http,
            @Qualifier("gatewayMcpIdentityAuthenticator") McpGatewayIdentityAuthenticator identities,
            @Qualifier("singleFlightSnapshotLoader") SingleFlightSnapshotLoader snapshots,
            @Qualifier("idpObservedVerificationComponent") IdpObservedVerificationComponent observations,
            @Qualifier("idpStarterProperties") IdpStarterProperties identity,
            @Qualifier("jacksonObjectMapper") ObjectMapper mapper,
            @Qualifier("gatewayClock") Clock clock) throws Exception {
        var audit = new IdpSecurityObservationFilter(observations, new IdpEndpointAuthenticationPolicy());
        var access = new McpManagementAccessFilter(identities, snapshots, mapper, clock,
                identity.getResourceUri(), McpManagementSecurityConfiguration::scopes);
        return http.securityMatcher(MANAGED)
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            boolean delivered = IdpSecurityObservationFilter.beforeAuthenticationFailure(request);
                            McpManagementAccessFilter.writeFailure(response, mapper, clock, delivered ? 401 : 503,
                                    delivered ? McpManagementAccessFilter.codeFor(401) : "OAUTH_AUDIT_DELIVERY_UNAVAILABLE");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            boolean delivered = IdpSecurityObservationFilter.beforeAuthorizationDenied(request);
                            McpManagementAccessFilter.writeFailure(response, mapper, clock, delivered ? 403 : 503,
                                    delivered ? McpManagementAccessFilter.codeFor(403) : "OAUTH_AUDIT_DELIVERY_UNAVAILABLE");
                        }))
                .addFilterBefore(audit, AnonymousAuthenticationFilter.class)
                .addFilterAfter(access, IdpSecurityObservationFilter.class)
                .build();
    }

    static Set<String> scopes(HttpServletRequest request) {
        if (ISSUE_APPROVAL.matches(request)) {
            return Set.of("yuheng:mcp:approve");
        }
        if (READ_TASKS.matches(request)) {
            return Set.of("yuheng:mcp:runtime:read");
        }
        if (CANCEL_TASK.matches(request)) {
            return Set.of("yuheng:mcp:write");
        }
        throw new IllegalArgumentException("MCP_MANAGEMENT_ACTION_INVALID");
    }
}
