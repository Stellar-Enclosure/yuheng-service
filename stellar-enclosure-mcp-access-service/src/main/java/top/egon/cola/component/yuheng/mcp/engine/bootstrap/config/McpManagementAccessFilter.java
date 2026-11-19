package top.egon.cola.component.yuheng.mcp.engine.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.SecurityFailure;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponent;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.SystemAuthorizationSnapshot;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.platform.tianquan.jianshen.starter.client.Rbac3AuthorizationClient;
import top.egon.cola.platform.tianquan.jianshen.starter.security.Rbac3AuthenticationToken;
import top.egon.cola.platform.tianquan.jianshen.starter.security.Rbac3UserDetails;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Establishes one verified USER and its current RBAC context for an exact management request. */
@Slf4j
@RequiredArgsConstructor
public final class McpManagementAccessFilter extends OncePerRequestFilter {
    public static final String VERIFIED_IDENTITY_ATTRIBUTE =
            "top.egon.cola.component.yuheng.mcp.engine.bootstrap.config.McpManagementAccessFilter.verifiedIdentity";

    private static final Duration AUTHENTICATION_TIMEOUT = Duration.ofSeconds(5);

    @Qualifier("gatewayMcpIdentityAuthenticator") private final McpGatewayIdentityAuthenticator identities;
    @Qualifier("singleFlightSnapshotLoader") private final SingleFlightSnapshotLoader snapshots;
    @Qualifier("jacksonObjectMapper") private final ObjectMapper mapper;
    @Qualifier("gatewayClock") private final Clock clock;
    private final URI resource;
    private final Function<HttpServletRequest, Set<String>> requiredScopes;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (var observation = new McpRequestObservationComponent()) {
            final VerifiedManagementIdentityVO identity;
            final Rbac3AuthenticationToken authentication;
            try {
                identity = identities.authenticateManagement(credentials(request), resource, requiredScopes.apply(request))
                        .contextWrite(context -> context.put(McpRequestObservationComponent.class, observation))
                        .block(AUTHENTICATION_TIMEOUT);
                if (identity == null) {
                    throw SecurityFailure.unavailable();
                }
                authentication = currentAuthentication(identity, observation);
            } catch (SecurityFailure failure) {
                writeFailure(response, mapper, clock, failure.httpStatus(), codeFor(failure.httpStatus()));
                return;
            } catch (RuntimeException unavailable) {
                log.debug("MCP management authentication unavailable; failureType={}",
                        unavailable.getClass().getSimpleName());
                writeFailure(response, mapper, clock, 503, codeFor(503));
                return;
            }

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setAttribute(VERIFIED_IDENTITY_ATTRIBUTE, identity);
            try {
                chain.doFilter(request, response);
            } finally {
                request.removeAttribute(VERIFIED_IDENTITY_ATTRIBUTE);
                SecurityContextHolder.clearContext();
            }
        }
    }

    private Rbac3AuthenticationToken currentAuthentication(VerifiedManagementIdentityVO identity,
            McpRequestObservationComponent observation) {
        var principal = identity.principal();
        final SystemAuthorizationSnapshot snapshot;
        try {
            snapshot = snapshots.load(principal, identity.userTokenForAuthorization());
        } catch (Rbac3AuthorizationClient.AuthorizationDeniedException denied) {
            observation.confirmDenial();
            throw SecurityFailure.forbidden();
        }
        if (snapshot == null || snapshot.permissionSnapshot() == null || snapshot.policyBinding() == null
                || !principal.subject().equals(snapshot.identitySub()) || !principal.tenantId().equals(snapshot.tenantId())
                || !identity.issuer().toASCIIString().equals(snapshot.permissionSnapshot().issuer())
                || !snapshot.expiresAt().isAfter(clock.instant()) || snapshot.expiresAt().isAfter(principal.expiresAt())) {
            throw SecurityFailure.unavailable();
        }
        identity.requireCurrent(clock);
        var scopes = identity.scopes().stream().map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList();
        var authentication = new Rbac3AuthenticationToken(new Rbac3UserDetails(principal, snapshot), scopes);
        authentication.setDetails(identity.proof());
        return authentication;
    }

    private static McpHttpRequest credentials(HttpServletRequest request) {
        var authorization = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        Map<String, String> headers = new HashMap<>();
        if (authorization.size() == 1 && !authorization.getFirst().isBlank()) {
            headers.put(HttpHeaders.AUTHORIZATION, authorization.getFirst());
        }
        String trace = MDC.get("traceId");
        if (trace != null) {
            headers.put("x-egon-request-id", trace);
        }
        return new McpHttpRequest(request.getMethod(), request.getRequestURI(), headers, "", Map.of());
    }

    static String codeFor(int status) {
        return switch (status) {
            case 401 -> "YUHENG_ADMIN_AUTHENTICATION_REQUIRED";
            case 403 -> "YUHENG_ADMIN_CAPABILITY_REQUIRED";
            default -> "YUHENG_ADMIN_SECURITY_UNAVAILABLE";
        };
    }

    static void writeFailure(HttpServletResponse response, ObjectMapper mapper, Clock clock, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        mapper.writeValue(response.getOutputStream(), Map.of("code", code, "message", code,
                "timestamp", clock.instant().toString()));
    }
}
