package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.mcp.engine.http.service.McpGatewayHttpDataPlaneHandlerAdapter;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties;
import top.egon.cola.component.yuheng.mcp.engine.http.service.McpGatewayHttpServer;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.SharedMcpTransportStore;
import top.egon.cola.component.yuheng.mcp.rule.service.McpRuleCompiler;
import top.egon.cola.component.yuheng.mcp.server.service.McpMethodDispatcher;
import top.egon.cola.component.yuheng.mcp.server.service.handler.McpDiscoverHandler;
import top.egon.cola.component.yuheng.mcp.server.service.handler.McpInitializeHandler;
import top.egon.cola.component.yuheng.mcp.server.service.handler.McpInitializedHandler;
import top.egon.cola.component.yuheng.mcp.server.service.handler.McpPingHandler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class McpTransportIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cancellationClosesTheOuterPhysicalRequestScope() throws Exception {
        var server = new McpRuntimeServer("server-1", "billing", "Billing", null, null,
                Set.of(McpProtocolDialect.STABLE_2025_11_25), "https://resource.example/yuheng", 0, true);
        var rules = new McpRuleCompiler().compile(new McpRuleContent(List.of(server), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        var cleaned = new java.util.concurrent.CountDownLatch(1);
        var seen = new java.util.concurrent.atomic.AtomicReference<McpRequestObservationComponent>();
        var store = new SharedMcpTransportStore();
        var handler = new McpEngineHttpHandler(() -> rules, new McpMethodDispatcher(List.of(new McpInitializeHandler())), store, store,
                (request, selected) -> Mono.deferContextual(reactor -> {
                    McpRequestObservationComponent scope = reactor.get(McpRequestObservationComponent.class); seen.set(scope);
                    scope.authorizationScope().apply("controlled-cancel", () -> Map.entry(new Object(), (Runnable) cleaned::countDown));
                    return Mono.never();
                }), MAPPER, Clock.systemUTC(), Duration.ofMinutes(30), Duration.ofMillis(20), 65536);
        var pending = handler.handle(new top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest("POST", "/mcp/billing",
                Map.of("content-type", "application/json"), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}", Map.of()));
        var subscription = pending.subscribe();
        assertNotNull(seen.get()); subscription.dispose();
        assertTrue(cleaned.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(seen.get().authorizationScope().active());
    }

    @Test
    void strictCapabilityReadKeepsDenyAndUnavailableDistinctAndBusinessUncalled() throws Exception {
        for (String outcome : List.of("ALLOW", "DENY", "AUDIT_FALSE", "AUDIT_THROW", "UNAVAILABLE")) {
            var request = McpRequestObservationComponentTest.request();
            var context = McpRequestObservationComponentTest.context(request);
            var business = new java.util.concurrent.atomic.AtomicInteger();
            var observed = mock(top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent.class);
            if ("AUDIT_THROW".equals(outcome)) { when(observed.authorizationDenied(any(), any())).thenThrow(new IllegalStateException("controlled-audit")); }
            else { when(observed.authorizationDenied(any(), any())).thenReturn(!"AUDIT_FALSE".equals(outcome)); }
            var clock = Clock.systemUTC();
            var snapshotStore = mock(top.egon.cola.platform.tianquan.jianshen.starter.cache.AuthorizationSnapshotCache.SnapshotStore.class);
            when(snapshotStore.get(any())).thenReturn(java.util.Optional.empty());
            var cache = new top.egon.cola.platform.tianquan.jianshen.starter.cache.AuthorizationSnapshotCache(snapshotStore, clock, Duration.ofSeconds(5));
            var loader = new top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader(cache,
                    (code, principal) -> {
                        if ("UNAVAILABLE".equals(outcome)) {
                            throw new top.egon.cola.platform.tianquan.jianshen.starter.client.Rbac3AuthorizationClient.AuthorizationUnavailableException("controlled-snapshot");
                        }
                        var granted="ALLOW".equals(outcome) ? request.requiredPermissions() : Set.<String>of();
                        var expiry=request.expiresAt().minusSeconds(1);
                        var view=new top.egon.cola.platform.tianquan.jianshen.contract.permission.EffectivePermissionSnapshot(request.issuer(),request.subjectId(),
                                new top.egon.cola.platform.tianquan.jianshen.contract.permission.CatalogScope("10",request.tenantId(),"xingyuan",code,"default","test"),"1","1","1",
                                new top.egon.cola.platform.tianquan.jianshen.contract.permission.EffectivePermissionSnapshot.ActiveRoleContext(code,List.of("50"),List.of("50")),
                                granted.stream().sorted().toList(),expiry);
                        return new top.egon.cola.platform.tianquan.jianshen.contract.authorization.SystemAuthorizationSnapshot(
                                request.tenantId(),request.subjectId(),"1",code,1,1,List.of("50"),null,null,granted,Set.of(),Map.of(),Map.of(),"checksum",clock.instant(),expiry,view,
                                new top.egon.cola.platform.tianquan.jianshen.contract.authorization.RuntimePolicyBinding("yuheng-mcp-java","1","a".repeat(64),"b".repeat(64),"c".repeat(64)));
                    }, "yuheng-mcp", Duration.ofMinutes(5), clock);
            try (var validation = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                var adapter = top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security.Rbac3McpAuthorizationAdapter.createConfigured(loader,
                        observed, top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder.NOOP, validation.getValidator());
                var gate = new top.egon.cola.component.yuheng.mcp.common.security.McpSecurityGate(adapter,
                        ignored -> Mono.just(top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort.Result.APPROVED), MAPPER);
                var server = new McpRuntimeServer("server-1", "billing", "Billing", null, null, Set.of(McpProtocolDialect.STABLE_2025_11_25), request.resourceUri(), 0, true);
                var resource = new top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeResource("resource-1", "billing", "scopes",
                        "egon://billing/scopes", null, "application/json", "LOCAL_OPERATION", "operation-1", null, Map.of(), Set.of(), 65536, true);
                var rules = new McpRuleCompiler().compile(new McpRuleContent(List.of(server), List.of(), List.of(resource),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), Set.of("operation-1"));
                var catalog = new top.egon.cola.component.yuheng.mcp.resource.service.McpResourceCatalog(() -> rules,
                        new top.egon.cola.component.yuheng.mcp.resource.domain.McpResourceUriValidator());
                var driver = new top.egon.cola.component.yuheng.mcp.resource.adapter.OperationResourceDriver(invocation -> Mono.defer(() -> {
                    business.incrementAndGet();
                    assertEquals("Bearer " + request.userAccessToken(), invocation.originalBearerToken());
                    assertFalse(invocation.call().queryArguments().containsKey(McpGatewayIdentityAuthenticator.VERIFIED_CONTEXT_ATTRIBUTE));
                    return Mono.just(new top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult(200, Map.of(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }));
                var sessionStore = new SharedMcpTransportStore();
                var scopeRef = new java.util.concurrent.atomic.AtomicReference<McpRequestObservationComponent>();
                McpEngineHttpHandler.IdentityAuthenticator authenticator = (http, selected) -> Mono.deferContextual(reactor -> {
                    scopeRef.set(reactor.get(McpRequestObservationComponent.class));
                    var identity = new java.util.LinkedHashMap<String, Object>(context.principal().attributes());
                    identity.put("callerId", request.subjectId()); identity.put("tenantId", request.tenantId());
                    identity.put("identity.issuer", request.issuer()); identity.put("identity.resource-uri", request.resourceUri());
                    identity.put("originalBearerToken", "Bearer " + request.userAccessToken());
                    identity.put(McpGatewayIdentityAuthenticator.VERIFIED_CONTEXT_ATTRIBUTE, context);
                    return Mono.just(Map.copyOf(identity));
                });
                var handler = new McpEngineHttpHandler(() -> rules, new McpMethodDispatcher(List.of(new McpInitializeHandler(),
                        new top.egon.cola.component.yuheng.mcp.resource.service.McpResourcesReadHandler(catalog, List.of(driver), gate),
                        new top.egon.cola.component.yuheng.mcp.resource.service.McpResourcesListHandler(catalog, gate))),
                        sessionStore, sessionStore, authenticator, MAPPER, clock, Duration.ofMinutes(30), Duration.ofMillis(20), 65536);
                var initialize = inProcess(handler, "initialize", "{\"protocolVersion\":\"2025-11-25\"}", null);
                assertEquals(200, initialize.status());
                var sessionId = initialize.header("mcp-session-id");
                assertNotNull(sessionId);
                assertFalse(scopeRef.get().authorizationScope().active());
                String sessionJson = MAPPER.copy().findAndRegisterModules().writeValueAsString(Mono.from(sessionStore.find(sessionId)).block());
                assertFalse(sessionJson.contains("verified-context")); assertFalse(sessionJson.contains(request.userAccessToken()));
                var read = inProcess(handler, "resources/read", "{\"uri\":\"egon://billing/scopes\"}", sessionId);
                assertEquals("ALLOW".equals(outcome) ? 200 : "DENY".equals(outcome) ? 403 : 503, read.status(), outcome);
                if (!"ALLOW".equals(outcome)) { assertEquals("no-store", read.header("cache-control")); }
                assertEquals("ALLOW".equals(outcome) ? 1 : 0, business.get());
                var body = reactor.core.publisher.Flux.from(read.body()).map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).collectList().block();
                assertFalse(body.toString().contains("verified-context")); assertFalse(body.toString().contains(request.userAccessToken()));
                assertFalse(scopeRef.get().authorizationScope().active());
                if ("UNAVAILABLE".equals(outcome)) {
                    var listed = inProcess(handler, "resources/list", "{}", sessionId);
                    assertEquals(503, listed.status()); assertEquals("no-store", listed.header("cache-control"));
                    verifyNoInteractions(observed);
                } else if ("ALLOW".equals(outcome)) { verifyNoInteractions(observed); }
                else if ("DENY".equals(outcome)) {
                    var listed = inProcess(handler, "resources/list", "{}", sessionId);
                    assertEquals(200, listed.status());
                    var listedBody = reactor.core.publisher.Flux.from(listed.body())
                            .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).collectList().block();
                    assertTrue(listedBody.toString().contains("\"resources\":[]")); assertEquals(0, business.get());
                    verify(observed, times(2)).authorizationDenied(any(), eq(context.traceId()));
                } else { verify(observed, times(1)).authorizationDenied(any(), eq(context.traceId())); }
            }
        }
    }

    @Test
    void enforcesVerifiedServiceScopesForToolCallsWithoutStartingAListener() {
        var now = Instant.parse("2026-08-02T05:00:00Z");
        var resourceUri = java.net.URI.create("https://resource.egon.top/yuheng-mcp");
        for (String outcome : List.of("ALLOW", "MISSING_IMPLICIT_SCOPE", "MISSING_EXTRA_SCOPE", "EXPIRED_HANDOFF", "FOREIGN_SCOPE")) {
            var scopes = new java.util.HashSet<>(Set.of("yuheng:read", "tool:pay", "invoice:pay"));
            if ("MISSING_IMPLICIT_SCOPE".equals(outcome)) { scopes.remove("tool:pay"); }
            if ("MISSING_EXTRA_SCOPE".equals(outcome)) { scopes.remove("invoice:pay"); }
            var principal = new top.egon.cola.platform.tianquan.shoubing.contract.ServiceIdentityPrincipal("finance-service", "1001", "finance-service", "service-token-1",
                    resourceUri, 1, scopes, "finance", "finance-service", "test", "credential-1", now.minusSeconds(30), now.plusSeconds(300));
            var proof = new top.egon.cola.platform.tianquan.shoubing.starter.security.domain.TokenAuditProofBO(java.net.URI.create("https://tianquan-shoubing.internal"),
                    resourceUri, null, "a".repeat(64), "b".repeat(64));
            var anonymous = new top.egon.cola.component.yuheng.core.security.GatewayAuthContext(top.egon.cola.component.yuheng.contract.protocol.AccessZone.PUBLIC,
                    top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol.HTTP, "yuheng.mcp.billing", null, "published-policy", "/mcp/billing", "POST", Set.of("bearer"),
                    top.egon.cola.component.yuheng.core.context.GatewayPrincipal.anonymous(), "127.0.0.1", "service-trace", "service-request", now.plusSeconds(30), "release-1",
                    Map.of("tianquan-shoubing.resource-uri", resourceUri.toString()), Set.of("yuheng:read"));
            var observer = mock(top.egon.cola.platform.tianquan.shoubing.starter.security.IdpObservedVerificationComponent.class);
            when(observer.authorizationDenied(any(), any())).thenReturn(true);
            var loader = mock(top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader.class);
            var checking = new java.util.concurrent.atomic.AtomicBoolean();
            var reads = new java.util.concurrent.atomic.AtomicInteger(); var clock = mock(Clock.class);
            when(clock.instant()).thenAnswer(call -> checking.get() && reads.incrementAndGet() > 1 && "EXPIRED_HANDOFF".equals(outcome) ? principal.expiresAt() : now);
            var business = new java.util.concurrent.atomic.AtomicInteger();
            try (var validation = jakarta.validation.Validation.buildDefaultValidatorFactory(); var foreign = new McpRequestObservationComponent()) {
                var provider = new top.egon.cola.platform.tianquan.shoubing.yuheng.security.IdpIdentityAuthenticationProvider((context, token) -> {
                    assertEquals("controlled-service-bearer", token);
                    return new top.egon.cola.platform.tianquan.shoubing.yuheng.security.IdpGatewayJwtVerifier.Verification(principal,
                            top.egon.cola.component.yuheng.core.security.AuthenticationFailure.NONE, null, scopes, proof);
                }, validation.getValidator());
                var bindings = List.of(new McpGatewayEngineProperties.ServiceScopeBindingVO(resourceUri, "mcp:billing:tool:pay_invoice:call", Set.of("tool:pay")),
                        new McpGatewayEngineProperties.ServiceScopeBindingVO(resourceUri, "invoice:pay", Set.of("invoice:pay")));
                var adapter = top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security.Rbac3McpAuthorizationAdapter.createConfigured(loader, observer,
                        top.egon.cola.platform.tianquan.shoubing.core.observability.IdpSecurityMetricsRecorder.NOOP, validation.getValidator(), bindings, clock);
                top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort authorization = request -> {
                    checking.set(true);
                    var decision = Mono.from(adapter.authorize(request));
                    return "FOREIGN_SCOPE".equals(outcome) ? decision.contextWrite(reactor -> reactor.put(
                            top.egon.cola.component.yuheng.core.security.GatewayAuthContext.class, foreign.authorizationScope())) : decision;
                };
                var gate = new top.egon.cola.component.yuheng.mcp.common.security.McpSecurityGate(authorization,
                        ignored -> Mono.just(top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort.Result.APPROVED), MAPPER);
                var server = new McpRuntimeServer("server-1", "billing", "Billing", null, null, Set.of(McpProtocolDialect.STABLE_2025_11_25), resourceUri.toString(), 0, true);
                var tool = new top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool("tool-1", "billing", "pay_invoice", "Pay invoice", "LOCAL_OPERATION", "operation-1", "HTTP",
                        null, "{\"type\":\"object\"}", "{\"type\":\"object\"}", Map.of(), Set.of("invoice:pay"), "LOW", true, true);
                var rules = new McpRuleCompiler().compile(new McpRuleContent(List.of(server), List.of(tool), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()), Set.of("operation-1"));
                var catalog = new top.egon.cola.component.yuheng.mcp.tool.service.McpToolCatalog(() -> rules);
                var tools = new top.egon.cola.component.yuheng.mcp.tool.service.McpToolsCallHandler(catalog,
                        new top.egon.cola.component.yuheng.mcp.tool.service.McpResultBinder(MAPPER), invocation -> Mono.defer(() -> {
                            business.incrementAndGet();
                            assertEquals("Bearer controlled-service-bearer", invocation.originalBearerToken());
                            assertEquals("finance-service", invocation.callerId());
                            assertFalse(invocation.call().queryArguments().containsKey(McpGatewayIdentityAuthenticator.VERIFIED_CONTEXT_ATTRIBUTE));
                            return Mono.just(new top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult(200, Map.of(), "{\"paid\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        }), gate);
                var scopeRef = new java.util.concurrent.atomic.AtomicReference<McpRequestObservationComponent>();
                McpEngineHttpHandler.IdentityAuthenticator authenticator = (http, selected) -> Mono.deferContextual(reactor -> {
                    scopeRef.set(reactor.get(McpRequestObservationComponent.class));
                    return Mono.from(provider.authenticate(anonymous, new top.egon.cola.component.yuheng.core.security.GatewayCredential("bearer", "controlled-service-bearer", Map.of())))
                            .map(authenticated -> {
                                assertEquals(top.egon.cola.component.yuheng.core.security.SecurityDecision.ALLOW, authenticated.decision());
                                var context = anonymous.withPrincipal(authenticated.principal());
                                var identity = new java.util.LinkedHashMap<String, Object>(context.principal().attributes());
                                identity.put("callerId", principal.subject()); identity.put("tenantId", principal.tenantId());
                                identity.put("identity.issuer", proof.issuer().toString()); identity.put("identity.resource-uri", resourceUri.toString());
                                identity.put("originalBearerToken", "Bearer controlled-service-bearer");
                                identity.put(McpGatewayIdentityAuthenticator.VERIFIED_CONTEXT_ATTRIBUTE, context);
                                return Map.copyOf(identity);
                            });
                });
                var store = new SharedMcpTransportStore();
                var handler = new McpEngineHttpHandler(() -> rules, new McpMethodDispatcher(List.of(new McpInitializeHandler(), tools)),
                        store, store, authenticator, MAPPER, clock, Duration.ofMinutes(30), Duration.ofMillis(20), 65536);
                var initialized = inProcess(handler, "initialize", "{\"protocolVersion\":\"2025-11-25\"}", null);
                assertEquals(200, initialized.status(), outcome);
                assertFalse(scopeRef.get().authorizationScope().active());
                var response = inProcess(handler, "tools/call", "{\"name\":\"pay_invoice\",\"arguments\":{}}", initialized.header("mcp-session-id"));
                int expected = "ALLOW".equals(outcome) ? 200 : outcome.startsWith("MISSING") ? 403 : 503;
                assertEquals(expected, response.status(), outcome);
                if (expected != 200) { assertEquals("no-store", response.header("cache-control")); }
                assertEquals(expected == 200 ? 1 : 0, business.get(), outcome);
                var body = reactor.core.publisher.Flux.from(response.body()).map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).collectList().block();
                assertFalse(body.toString().contains("controlled-service-bearer")); assertFalse(body.toString().contains("verified-context"));
                assertFalse(scopeRef.get().authorizationScope().active());
                if (expected == 403) { verify(observer, times(1)).authorizationDenied(same(proof), eq(anonymous.traceId())); }
                else { verifyNoInteractions(observer); }
                verifyNoInteractions(loader);
            }
        }
    }

    private top.egon.cola.component.yuheng.mcp.common.transport.McpHttpResponse inProcess(McpEngineHttpHandler handler,
            String method, String params, String sessionId) {
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put("content-type", "application/json"); headers.put("mcp-protocol-version", "2025-11-25");
        if (sessionId != null) { headers.put("mcp-session-id", sessionId); }
        return handler.handle(new top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest("POST", "/mcp/billing", headers,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}", Map.of())).block(Duration.ofSeconds(5));
    }

    @Test
    void preservesSecurityStatusesWithoutDispatchingBusinessOrStartingAListener() {
        var runtimeServer = new McpRuntimeServer("server-1", "orders", "Orders", null, null,
                Set.of(McpProtocolDialect.STABLE_2025_11_25),
                "https://resource.egon.top/yuheng-mcp", 30, true);
        var rules = new McpRuleCompiler().compile(new McpRuleContent(List.of(runtimeServer),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        var dispatcher = org.mockito.Mockito.mock(McpMethodDispatcher.class);
        var store = org.mockito.Mockito.mock(top.egon.cola.component.yuheng.mcp.common.transport.McpSessionStore.class);
        for (int status : List.of(401, 403, 503)) {
            var failure = new top.egon.cola.component.yuheng.runtime.security.domain.GatewaySecurityException(
                    "SECURITY_" + status, status, "UNAVAILABLE",
                    Map.of("www-authenticate", List.of("Bearer realm=\"mcp\""), "Cache-Control", List.of("public, max-age=9999")));
            var handler = new McpEngineHttpHandler(() -> rules, dispatcher, store,
                    new SharedMcpTransportStore(), (request, selected) -> Mono.error(failure),
                    MAPPER, Clock.systemUTC(), Duration.ofMinutes(30), Duration.ofMillis(20), 65536);
            var response = handler.handle(new top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest(
                    "POST", "/mcp/orders", Map.of("content-type", "application/json"),
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}",
                    Map.of())).block(Duration.ofSeconds(2));
            assertEquals(status, response.status());
            assertEquals("no-store", response.header("cache-control"));
            assertEquals("Bearer realm=\"mcp\"", response.header("www-authenticate"));
            String body = reactor.core.publisher.Flux.from(response.body())
                    .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                    .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(2));
            assertTrue(body.contains("\"error\":\"SECURITY_" + status + "\""));
        }
        org.mockito.Mockito.verifyNoInteractions(dispatcher, store);
    }

    @Test
    void keepsInitializationAndMissingSessionProtocolShapesInProcess() throws Exception {
        var handler = handler(new SharedMcpTransportStore());
        var initialized = handler.handle(new top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest(
                "POST", "/mcp/orders", Map.of("content-type", "application/json"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}",
                Map.of())).block(Duration.ofSeconds(2));
        assertEquals(200, initialized.status());
        assertNotNull(initialized.header("mcp-session-id"));
        String body = reactor.core.publisher.Flux.from(initialized.body())
                .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(2));
        assertEquals("2025-11-25", MAPPER.readTree(body).path("result").path("protocolVersion").asText());
        var missingSession = handler.handle(new top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest(
                "POST", "/mcp/orders", Map.of("content-type", "application/json", "mcp-protocol-version", "2025-11-25"),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}", Map.of()))
                .block(Duration.ofSeconds(2));
        assertEquals(400, missingSession.status());
    }

    @Test
    void exposesStableRcAndMetadataWithoutApiFallback() throws Exception {
        SharedMcpTransportStore store = new SharedMcpTransportStore();
        McpEngineHttpHandler mcp = handler(store);
        McpGatewayHttpDataPlaneHandlerAdapter composite =
                new McpGatewayHttpDataPlaneHandlerAdapter(
                        mcp,
                        properties()
                );
        McpGatewayHttpServer server = new McpGatewayHttpServer(
                properties(),
                composite
        );
        server.start();
        try {
            HttpResult initialized = post(
                    server.publicPort(),
                    "/mcp/orders",
                    Map.of("content-type", "application/json"),
                    """
                    {"jsonrpc":"2.0","id":1,"method":"initialize",\
                    "params":{"protocolVersion":"2025-11-25"}}
                    """
            );
            assertEquals(200, initialized.status());
            assertNotNull(initialized.sessionId());
            assertEquals(
                    "2025-11-25",
                    MAPPER.readTree(initialized.body())
                            .path("result")
                            .path("protocolVersion")
                            .asText()
            );

            HttpResult rc = post(
                    server.publicPort(),
                    "/mcp/orders",
                    Map.of(
                            "content-type", "application/json",
                            "Mcp-Protocol-Version", "2026-07-28",
                            "Mcp-Method", "server/discover"
                    ),
                    """
                    {"jsonrpc":"2.0","id":2,"method":"server/discover",\
                    "params":{"_meta":{"client":"test"}}}
                    """
            );
            assertEquals(200, rc.status());
            assertEquals(
                    "2026-07-28",
                    MAPPER.readTree(rc.body())
                            .path("result")
                            .path("protocolVersion")
                            .asText()
            );

            JsonNode metadata = MAPPER.readTree(HttpClient.create()
                    .get()
                    .uri("http://127.0.0.1:" + server.publicPort()
                            + "/.well-known/oauth-protected-resource/mcp/orders")
                    .responseSingle((response, body) -> body.asString())
                    .block(Duration.ofSeconds(2)));
            assertEquals(
                    "https://resource.egon.top/yuheng-mcp",
                    metadata.path("resourceUri").asText()
            );

            HttpResult delegated = get(
                    server.publicPort(),
                    "/ordinary"
            );
            assertEquals(404, delegated.status());
            assertEquals("MCP_ROUTE_NOT_FOUND", delegated.body());
        } finally {
            server.close();
        }
    }

    @Test
    void rejectsStableFollowUpWithoutSession() {
        SharedMcpTransportStore store = new SharedMcpTransportStore();
        McpGatewayHttpServer server = new McpGatewayHttpServer(
                properties(),
                new McpGatewayHttpDataPlaneHandlerAdapter(
                        handler(store),
                        properties()
                )
        );
        server.start();
        try {
            HttpResult result = post(
                    server.publicPort(),
                    "/mcp/orders",
                    Map.of(
                            "content-type", "application/json",
                            "Mcp-Protocol-Version", "2025-11-25"
                    ),
                    """
                    {"jsonrpc":"2.0","id":3,"method":"ping","params":{}}
                    """
            );
            assertEquals(400, result.status());
            assertTrue(result.body().contains("MCP_SESSION_REQUIRED"));
        } finally {
            server.close();
        }
    }

    public static McpEngineHttpHandler handler(SharedMcpTransportStore store) {
        return handler(store, identity());
    }

    public static McpEngineHttpHandler handler(
            SharedMcpTransportStore store,
            Map<String, Object> identity) {
        McpRuntimeServer server = new McpRuntimeServer(
                "server-1",
                "orders",
                "Orders",
                "Order capabilities",
                "Use approved operations.",
                Set.of(
                        McpProtocolDialect.STABLE_2025_11_25,
                        McpProtocolDialect.RC_2026_07_28,
                        McpProtocolDialect.LEGACY_2024_SSE
                ),
                "https://resource.egon.top/yuheng-mcp",
                30,
                true
        );
        var rules = new McpRuleCompiler().compile(new McpRuleContent(
                List.of(server),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        return new McpEngineHttpHandler(
                () -> rules,
                new McpMethodDispatcher(List.of(
                        new McpInitializeHandler(),
                        new McpInitializedHandler(),
                        new McpPingHandler(),
                        new McpDiscoverHandler()
                )),
                store,
                store,
                (request, selected) -> Mono.just(identity),
                MAPPER,
                Clock.fixed(
                        Instant.parse("2026-08-02T00:00:00Z"),
                        ZoneOffset.UTC
                ),
                Duration.ofMinutes(30),
                Duration.ofMillis(20),
                1024 * 1024
        );
    }

    static Map<String, Object> identity() {
        return Map.of(
                "callerId", "user-1",
                "tenantId", "tenant-1",
                "tianquan-shoubing.client-id", "client-1",
                "identity.token-id", "token-1"
        );
    }

    private HttpResult post(
            int port,
            String path,
            Map<String, String> headers,
            String body) {
        return HttpClient.create()
                .headers(target -> headers.forEach(target::set))
                .post()
                .uri("http://127.0.0.1:" + port + path)
                .send((request, outbound) -> outbound.sendString(
                        Mono.just(body)
                ))
                .responseSingle((response, content) -> content.asString()
                        .defaultIfEmpty("")
                        .map(value -> new HttpResult(
                                response.status().code(),
                                response.responseHeaders().get(
                                        "Mcp-Session-Id"
                                ),
                                value
                        )))
                .block(Duration.ofSeconds(2));
    }

    private HttpResult get(int port, String path) {
        return HttpClient.create()
                .get()
                .uri("http://127.0.0.1:" + port + path)
                .responseSingle((response, content) -> content.asString()
                        .defaultIfEmpty("")
                        .map(value -> new HttpResult(
                                response.status().code(),
                                response.responseHeaders().get(
                                        "Mcp-Session-Id"
                                ),
                                value
                        )))
                .block(Duration.ofSeconds(2));
    }

    private McpGatewayEngineProperties.ListenerProperties properties() {
        return new McpGatewayEngineProperties.ListenerProperties(true, "127.0.0.1", 0,
                1024 * 1024, Duration.ofSeconds(2),
                new McpGatewayEngineProperties.TlsProperties(false, true, null, null, null, false));
    }

    private record HttpResult(int status, String sessionId, String body) {
    }
}
