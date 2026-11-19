package top.egon.cola.component.yuheng.mcp.engine.bootstrap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.NativeFixture;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.RuntimePolicyBinding;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.SystemAuthorizationSnapshot;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.CatalogScope;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.EffectivePermissionSnapshot;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.platform.tianquan.jianshen.starter.client.Rbac3AuthorizationClient;
import top.egon.cola.platform.tianquan.jianshen.starter.security.Rbac3AuthenticationToken;
import top.egon.cola.platform.tianquan.shoubing.contract.IdentityPrincipal;
import top.egon.cola.platform.tianquan.shoubing.core.audit.TokenUsageAuditEvent;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;
import top.egon.cola.platform.tianquan.shoubing.starter.security.domain.TokenAuditProofBO;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.*;

/** Runs the production management chains and native verifier through a real Servlet/Spring boundary. */
class McpManagementSecurityConfigurationTest {
    private static final String BASE = "/api/v1/yuheng/admin/mcp";
    private static final String APPROVE = "yuheng:mcp:approve";
    private static final String READ = "yuheng:mcp:runtime:read";
    private static final String WRITE = "yuheng:mcp:write";
    private static final Set<String> ALL = Set.of(APPROVE, READ, WRITE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void dedicatedChainMatchesOnlyTheOriginalFourRouteAndMethodPairs() {
        try (var fixture = new Harness()) {
            var chain = fixture.context.getBean("mcpManagementSecurityFilterChain", SecurityFilterChain.class);
            for (var route : List.of(new Route("POST", BASE + "/approvals", APPROVE), new Route("GET", BASE + "/tasks", READ),
                    new Route("GET", BASE + "/tasks/task-1", READ), new Route("HEAD", BASE + "/tasks", READ),
                    new Route("HEAD", BASE + "/tasks/task-1", READ), new Route("POST", BASE + "/tasks/task-1/cancel", WRITE))) {
                assertTrue(chain.matches(servlet(route.method(), route.path())), route.toString());
            }
            for (var route : List.of(new Route("GET", BASE + "/approvals", ""), new Route("POST", BASE + "/tasks", ""),
                    new Route("DELETE", BASE + "/tasks/task-1", ""), new Route("GET", BASE + "/tasks/task-1/cancel", ""),
                    new Route("POST", BASE + "/tasks/task-1/cancel/extra", ""), new Route("GET", BASE + "/tasks/task-1/extra", ""),
                    new Route("GET", BASE + "/servers", ""), new Route("GET", "/api/v1/yuheng/admin/proxy", ""))) {
                assertFalse(chain.matches(servlet(route.method(), route.path())), route.toString());
                var result = fixture.perform(route.method(), route.path(), "opaque-user");
                assertEquals(403, result.getResponse().getStatus(), route.toString());
            }
            assertEquals(0, fixture.controllerCalls.get());
            assertEquals(0, fixture.nativeFixture.events.size());
        }
    }

    @Test
    void onlyVerifiedUserClientAndCurrentAuthorityReachEveryControllerRoute() {
        try (var fixture = new Harness()) {
            for (var route : List.of(new Route("POST", BASE + "/approvals", APPROVE), new Route("GET", BASE + "/tasks", READ),
                    new Route("GET", BASE + "/tasks/task-1", READ), new Route("POST", BASE + "/tasks/task-1/cancel", WRITE))) {
                var result = fixture.perform(route.method(), route.path(), "opaque-user");
                assertEquals(200, result.getResponse().getStatus(), route.toString());
                var json = MAPPER.readTree(result.getResponse().getContentAsString());
                assertEquals("user-1", json.path("subject").asText());
                assertEquals("1001", json.path("tenant").asText());
                assertEquals("client-1", json.path("client").asText());
                assertEquals(ISSUER.toASCIIString(), json.path("issuer").asText());
                assertEquals(RESOURCE.toASCIIString(), json.path("resource").asText());
                assertNull(result.getRequest().getSession(false));
                assertNull(result.getResponse().getHeader("Set-Cookie"));
                assertNull(result.getRequest().getAttribute(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE));
                assertNull(SecurityContextHolder.getContext().getAuthentication());
                assertFalse(result.getResponse().getContentAsString().contains("opaque-user"));
                assertFalse(result.getResponse().getContentAsString().contains("Bearer"));
            }
            assertEquals(4, fixture.controllerCalls.get());
            assertEquals(4, fixture.snapshotReads.get());
            assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"), fixture.nativeFixture.events.stream().map(TokenUsageAuditEvent::result).toList());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void wrongAudienceRevokedMissingMalformedAndRepeatedAuthorizationStay401() {
        for (String mode : List.of("wrong-audience", "revoked", "missing", "malformed", "repeated")) {
            try (var fixture = new Harness()) {
                if (mode.equals("wrong-audience")) {
                    var proof = fixture.nativeFixture.tokens.get("opaque-user");
                    var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("opaque-user").headers(headers -> headers.putAll(proof.getJwt().getHeaders())).claims(claims -> claims.putAll(proof.getJwt().getClaims())).claim("aud", List.of("https://resource.example/foreign")).build();
                    proof.setJwt(jwt);
                }
                fixture.nativeFixture.revoked = mode.equals("revoked");
                var request = request(HttpMethod.GET, BASE + "/tasks");
                if (mode.equals("repeated")) request.header("Authorization", "Bearer opaque-user", "Bearer other");
                else if (!mode.equals("missing")) request.header("Authorization", mode.equals("malformed") ? "Basic opaque-user" : "Bearer opaque-user");
                var result = fixture.mvc.perform(request).andReturn();
                assertFailure(result, 401);
                assertEquals("Bearer", result.getResponse().getHeader("WWW-Authenticate"));
                assertEquals(0, fixture.controllerCalls.get());
                assertEquals(0, fixture.snapshotReads.get());
                assertEquals(1, fixture.nativeFixture.events.size(), mode);
            } catch (Exception failure) {
                throw new AssertionError(mode, failure);
            }
        }
    }

    @Test
    void serviceInsufficientScopesAndAbsentCapabilityAre403WithOneDurableDeny() {
        for (String mode : List.of("service", "scope", "capability", "foreign-tenant")) {
            try (var fixture = new Harness()) {
                String token = "opaque-user";
                if (mode.equals("service")) {
                    fixture.nativeFixture.token("opaque-service", true, "1001", ALL);
                    token = "opaque-service";
                } else if (mode.equals("scope")) {
                    fixture.nativeFixture.token("opaque-user", false, "1001", Set.of(APPROVE));
                } else if (mode.equals("foreign-tenant")) {
                    fixture.nativeFixture.token("opaque-user", false, "1002", ALL);
                } else {
                    fixture.capabilities = Set.of(APPROVE);
                }
                var result = fixture.perform("GET", BASE + "/tasks", token);
                assertFailure(result, 403);
                assertEquals(0, fixture.controllerCalls.get());
                assertEquals(List.of("SUCCESS", "DENIED"), fixture.nativeFixture.events.stream().map(TokenUsageAuditEvent::result).toList(), mode);
                assertEquals(fixture.nativeFixture.events.getFirst().tokenRef(), fixture.nativeFixture.events.getLast().tokenRef());
            }
        }
    }

    @Test
    void authoritativeStateAndAuthenticationOrDenialAuditFailureRemain503() {
        for (String mode : List.of("authority", "authentication-audit", "denial-audit", "snapshot")) {
            try (var fixture = new Harness()) {
                fixture.nativeFixture.unavailable = mode.equals("authority");
                fixture.nativeFixture.auditUnavailable = mode.equals("authentication-audit");
                fixture.nativeFixture.denialUnavailable = mode.equals("denial-audit");
                if (mode.equals("denial-audit")) fixture.capabilities = Set.of();
                fixture.snapshotUnavailable = mode.equals("snapshot");
                assertFailure(fixture.perform("GET", BASE + "/tasks", "opaque-user"), 503);
                assertEquals(0, fixture.controllerCalls.get());
                assertTrue(fixture.nativeFixture.events.stream().noneMatch(event -> event.result().equals("DENIED")));
            }
        }
    }

    @Test
    void currentRevocationAfterSuccessCannotReuseEarlierAuthenticationOrRbacState() {
        try (var fixture = new Harness()) {
            assertEquals(200, fixture.perform("GET", BASE + "/tasks", "opaque-user").getResponse().getStatus());
            fixture.nativeFixture.revoked = true;
            assertFailure(fixture.perform("GET", BASE + "/tasks", "opaque-user"), 401);
            assertEquals(1, fixture.controllerCalls.get());
            assertEquals(1, fixture.snapshotReads.get());
            assertEquals(2, fixture.nativeFixture.events.size());
            assertEquals("SUCCESS", fixture.nativeFixture.events.getFirst().result());
            assertNotEquals("SUCCESS", fixture.nativeFixture.events.getLast().result());
        }
    }

    @Test
    void unsignedHeadersCookiesAndServletSessionsCannotSupplyIdentityOrCapabilities() throws Exception {
        try (var fixture = new Harness()) {
            var session = new MockHttpSession();
            var forged = SecurityContextHolder.createEmptyContext();
            forged.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("administrator", "forged", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("CAP_*"))));
            session.setAttribute("SPRING_SECURITY_CONTEXT", forged);
            var result = fixture.mvc.perform(get(BASE + "/tasks").session(session).cookie(new Cookie("JSESSIONID", "forged"))
                    .header("X-User-Id", "administrator").header("X-Tenant-Id", "1001").header("X-Client-Id", "client-1")
                    .header("Forwarded", "host=resource.example;proto=https")).andReturn();
            assertFailure(result, 401);
            assertNull(result.getResponse().getHeader("Set-Cookie"));
            assertEquals(0, fixture.controllerCalls.get());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    private static void assertFailure(MvcResult result, int status) {
        String body = new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(status, result.getResponse().getStatus(), body);
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        assertFalse(body.contains("opaque-user"));
        assertFalse(body.contains("opaque-service"));
        assertNull(result.getRequest().getAttribute(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private static MockHttpServletRequest servlet(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    private record Route(String method, String path, String scope) { }

    private static final class Harness implements AutoCloseable {
        final NativeFixture nativeFixture = new NativeFixture();
        final AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        final AtomicInteger controllerCalls = new AtomicInteger();
        final AtomicInteger snapshotReads = new AtomicInteger();
        volatile Set<String> capabilities = ALL;
        volatile boolean snapshotUnavailable;
        final MockMvc mvc;

        Harness() {
            nativeFixture.token("opaque-user", false, "1001", ALL);
            var client = new Rbac3AuthorizationClient() {
                @Override public SystemAuthorizationSnapshot fetch(String code, IdentityPrincipal identity) {
                    throw new AssertionError("The original USER token must be explicitly carried to the current PDP");
                }
                @Override public SystemAuthorizationSnapshot fetch(String code, IdentityPrincipal identity, String token) {
                    assertEquals("opaque-user", token);
                    snapshotReads.incrementAndGet();
                    if (snapshotUnavailable) throw new AuthorizationUnavailableException("controlled-current-PDP");
                    var scope = new CatalogScope("10", "1001", "billing", code, "default", "prod");
                    var permission = new EffectivePermissionSnapshot(ISSUER.toASCIIString(), identity.subject(), scope, "1", "7", "3",
                            new EffectivePermissionSnapshot.ActiveRoleContext(code, List.of("50"), List.of("50")),
                            capabilities.stream().sorted().toList(), NOW.plusSeconds(300));
                    return new SystemAuthorizationSnapshot("1001", identity.subject(), "1", code, 7L, 3L, List.of("50"), null, null,
                            capabilities, Set.of(), Map.of(), Map.of(), "checksum", NOW, NOW.plusSeconds(300), permission,
                            new RuntimePolicyBinding("mcp-management-java", "1", "a".repeat(64), "b".repeat(64), "c".repeat(64)));
                }
            };
            var snapshots = new SingleFlightSnapshotLoader(client, "yuheng-mcp", nativeFixture.clock);
            var identity = new IdpStarterProperties();
            identity.setResourceUri(RESOURCE);
            context.setServletContext(new MockServletContext());
            context.addBeanFactoryPostProcessor(factory -> {
                factory.registerSingleton("managementTestHarness", this);
                factory.registerSingleton("gatewayMcpIdentityAuthenticator", nativeFixture.authenticator);
                factory.registerSingleton("singleFlightSnapshotLoader", snapshots);
                factory.registerSingleton("idpObservedVerificationComponent", nativeFixture.observer);
                factory.registerSingleton("idpStarterProperties", identity);
                factory.registerSingleton("jacksonObjectMapper", MAPPER);
                factory.registerSingleton("gatewayClock", nativeFixture.clock);
                factory.registerSingleton("transactionManager", mock(PlatformTransactionManager.class));
            });
            context.register(WebConfiguration.class);
            context.refresh();
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
        }

        MvcResult perform(String method, String path, String token) {
            try {
                MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}");
                if (token != null) request.header("Authorization", "Bearer " + token);
                return mvc.perform(request).andReturn();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
        @Override public void close() { context.close(); nativeFixture.close(); SecurityContextHolder.clearContext(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(McpManagementSecurityConfiguration.class)
    static class WebConfiguration {
        @Bean SecurityFilterChain closedFallback(HttpSecurity http) throws Exception {
            return new McpGatewayManagementSecurityConfiguration().mcpGatewayManagementSecurityFilterChain(http);
        }
        @Bean ProbeController probeController(Harness fixture) { return new ProbeController(fixture); }
    }

    @TestComponent
    @RestController
    static class ProbeController {
        private final Harness fixture;
        ProbeController(Harness fixture) { this.fixture = fixture; }
        @PostMapping(BASE + "/approvals")
        @PreAuthorize("hasAuthority('SCOPE_yuheng:mcp:approve') and hasAnyAuthority('CAP_yuheng:mcp:approve','CAP_*')")
        public Map<String,String> approve(HttpServletRequest request, Authentication authentication) { return admitted(request, authentication); }
        @GetMapping({BASE + "/tasks", BASE + "/tasks/{id}"})
        @PreAuthorize("hasAuthority('SCOPE_yuheng:mcp:runtime:read') and hasAnyAuthority('CAP_yuheng:mcp:runtime:read','CAP_*')")
        public Map<String,String> read(HttpServletRequest request, Authentication authentication) { return admitted(request, authentication); }
        @PostMapping(BASE + "/tasks/{id}/cancel")
        @PreAuthorize("hasAuthority('SCOPE_yuheng:mcp:write') and hasAnyAuthority('CAP_yuheng:mcp:write','CAP_*')")
        public Map<String,String> cancel(HttpServletRequest request, Authentication authentication) { return admitted(request, authentication); }
        private Map<String,String> admitted(HttpServletRequest request, Authentication authentication) {
            fixture.controllerCalls.incrementAndGet();
            assertInstanceOf(Rbac3AuthenticationToken.class, authentication);
            assertEquals("", authentication.getCredentials());
            var proof = assertInstanceOf(TokenAuditProofBO.class, authentication.getDetails());
            var identity = assertInstanceOf(VerifiedManagementIdentityVO.class, request.getAttribute(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE));
            identity.requireCurrent(fixture.nativeFixture.clock);
            assertSame(proof, identity.proof());
            assertEquals(identity.principal(), ((Rbac3AuthenticationToken) authentication).getPrincipal().identity());
            assertEquals(identity.issuer(), proof.issuer());
            assertEquals(identity.resource(), proof.resource());
            return Map.of("subject", identity.principal().subject(), "tenant", identity.principal().tenantId(), "client", identity.clientId(),
                    "issuer", identity.issuer().toASCIIString(), "resource", identity.resource().toASCIIString());
        }
    }
}
