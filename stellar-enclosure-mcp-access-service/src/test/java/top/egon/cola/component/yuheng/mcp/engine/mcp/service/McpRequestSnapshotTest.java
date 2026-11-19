package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpJsonRpcRequest;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpJsonRpcResponse;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.SharedMcpTransportStore;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules;
import top.egon.cola.component.yuheng.mcp.rule.domain.McpRequestRules;
import top.egon.cola.component.yuheng.mcp.rule.service.McpRuleCompiler;
import top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext;
import top.egon.cola.component.yuheng.mcp.server.service.McpMethodDispatcher;
import top.egon.cola.component.yuheng.mcp.server.service.McpMethodHandler;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpRequestSnapshotTest {
    @Test
    void unsupportedOrOversizedRequestsDoNotConsultTheRuleSupplier() {
        var store = new SharedMcpTransportStore();
        var handler = new McpEngineHttpHandler(() -> {
            throw new AssertionError("rules must not be read");
        }, new McpMethodDispatcher(List.of()), store, store,
                (request, server) -> Mono.empty(), new ObjectMapper(), Clock.systemUTC(),
                Duration.ofMinutes(1), Duration.ofMillis(20), 4);
        assertEquals(404, handler.handle(new McpHttpRequest("GET", "/outside", Map.of(), "", Map.of()))
                .block(Duration.ofSeconds(2)).status());
        assertEquals(413, handler.handle(new McpHttpRequest("POST", "/mcp/billing", Map.of(), "12345", Map.of()))
                .block(Duration.ofSeconds(2)).status());
    }

    @Test
    void overlappingRequestsKeepIndependentSnapshotsAcrossAsyncAuthentication() throws Exception {
        CompiledMcpRules old = rules("old");
        CompiledMcpRules newer = rules("new");
        var oldSnapshot = mock(McpGatewayCompiledRulesDTO.class);
        var newSnapshot = mock(McpGatewayCompiledRulesDTO.class);
        when(oldSnapshot.mcpRules()).thenReturn(old);
        when(newSnapshot.mcpRules()).thenReturn(newer);
        when(oldSnapshot.releaseId()).thenReturn("release-old");
        when(newSnapshot.releaseId()).thenReturn("release-new");
        var oldAuth = reactor.core.publisher.Sinks.<Map<String, Object>>one();
        var newAuth = reactor.core.publisher.Sinks.<Map<String, Object>>one();
        McpMethodHandler method = new McpMethodHandler() {
            @Override public String method() { return "tools/list"; }
            @Override public Publisher<McpJsonRpcResponse> handle(McpJsonRpcRequest request,
                    McpRequestContext context) {
                var selected = McpRequestRules.resolve(context.attributes(), () -> newer);
                return Mono.deferContextual(reactor -> Mono.just(McpJsonRpcResponse.success(request.id(), Map.of(
                        "server", context.server().name(),
                        "rules", selected.server("billing").orElseThrow().name(),
                        "release", reactor.get(McpGatewayCompiledRulesDTO.class).releaseId()))));
            }
        };
        var store = new SharedMcpTransportStore();
        var handler = new McpEngineHttpHandler(() -> newer, new McpMethodDispatcher(List.of(method)),
                store, store, (request, server) -> server.name().equals("Billing old")
                        ? oldAuth.asMono() : newAuth.asMono(), new ObjectMapper(), Clock.systemUTC(),
                Duration.ofMinutes(1), Duration.ofMillis(20), 4096);
        var request = new McpHttpRequest("POST", "/mcp/billing", Map.of("content-type", "application/json",
                "mcp-protocol-version", McpProtocolDialect.RC_2026_07_28.protocolVersion(),
                "mcp-method", "tools/list"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"client\":\"test\"}}}", Map.of());

        var pendingOld = handler.handle(request, oldSnapshot).toFuture();
        var pendingNew = handler.handle(request, newSnapshot).toFuture();
        newAuth.tryEmitValue(Map.of("callerId", "new"));
        oldAuth.tryEmitValue(Map.of("callerId", "old"));
        var mapper = new ObjectMapper();
        var oldResponse = pendingOld.get(3, java.util.concurrent.TimeUnit.SECONDS);
        var newResponse = pendingNew.get(3, java.util.concurrent.TimeUnit.SECONDS);
        String oldBody = Flux.from(oldResponse.body()).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(2));
        String newBody = Flux.from(newResponse.body()).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(2));
        assertEquals("release-old", mapper.readTree(oldBody).path("result").path("release").asText());
        assertEquals("Billing old", mapper.readTree(oldBody).path("result").path("rules").asText());
        assertEquals("release-new", mapper.readTree(newBody).path("result").path("release").asText());
        assertEquals("Billing new", mapper.readTree(newBody).path("result").path("rules").asText());
    }

    @Test
    void serverDispatcherAndAsyncOperationReceiveTheSameSnapshotAfterSwitch() throws Exception {
        CompiledMcpRules old = rules("old");
        CompiledMcpRules newer = rules("new");
        var current = new AtomicReference<>(old);
        var snapshot = mock(McpGatewayCompiledRulesDTO.class);
        when(snapshot.mcpRules()).thenReturn(old);
        var selectedServer = new AtomicReference<McpRuntimeServer>();
        var selectedRules = new AtomicReference<CompiledMcpRules>();
        var selectedOperationRules = new AtomicReference<McpGatewayCompiledRulesDTO>();
        McpMethodHandler method = new McpMethodHandler() {
            @Override public String method() { return "tools/list"; }
            @Override public Publisher<McpJsonRpcResponse> handle(McpJsonRpcRequest request,
                    McpRequestContext context) {
                selectedRules.set(McpRequestRules.resolve(context.attributes(), current::get));
                return Mono.deferContextual(reactor -> {
                    selectedOperationRules.set(reactor.get(McpGatewayCompiledRulesDTO.class));
                    return Mono.just(McpJsonRpcResponse.success(request.id(), Map.of("version", "old")));
                });
            }
        };
        var store = new SharedMcpTransportStore();
        var handler = new McpEngineHttpHandler(current::get, new McpMethodDispatcher(List.of(method)),
                store, store, (request, server) -> Mono.fromSupplier(() -> {
                    selectedServer.set(server);
                    current.set(newer);
                    return Map.of("callerId", "user-1");
                }), new ObjectMapper(), Clock.systemUTC(), Duration.ofMinutes(1),
                Duration.ofMillis(20), 4096);
        var request = new McpHttpRequest("POST", "/mcp/billing",
                Map.of("content-type", "application/json",
                        "mcp-protocol-version", McpProtocolDialect.RC_2026_07_28.protocolVersion(),
                        "mcp-method", "tools/list"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"client\":\"test\"}}}", Map.of());

        var response = handler.handle(request, snapshot).block(Duration.ofSeconds(2));
        String body = Flux.from(response.body()).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(2));
        assertEquals(200, response.status());
        assertEquals("old", new ObjectMapper().readTree(body).path("result").path("version").asText());
        assertEquals("Billing old", selectedServer.get().name());
        assertSame(old, selectedRules.get());
        assertSame(snapshot, selectedOperationRules.get());
        assertSame(newer, current.get());
    }


    @Test void formalStableSessionPinsHistoricalCatalogAndRejectsMissingOrLegacyPins() {
        try(var fixture=new McpRequestObservationComponentTest.NativeFixture()) {
            fixture.token("opaque",false);
            var old=McpTransportIntegrationTest.snapshot("1");
            var initialNew=McpTransportIntegrationTest.snapshot("2");
            var newer=new McpGatewayCompiledRulesDTO(initialNew.ownerTenantId(),initialNew.version(),initialNew.validUntil(),initialNew.contentSha256(),initialNew.fileSha256(),
                    initialNew.content(),initialNew.mcpRules(),Map.of("billing",Set.of("new:catalog:scope")),Map.of());
            var authenticator=new McpGatewayIdentityAuthenticator(fixture.issuers,fixture.authorities,
                    new top.egon.cola.platform.tianquan.shoubing.starter.security.VerifiedPrincipalConverterImpl(),fixture.observer,fixture.validation.getValidator(),
                    (server,context) -> context.get(McpGatewayCompiledRulesDTO.class).serverScopes().get(server.serverCode()),fixture.clock,"1001");
            var active=new AtomicReference<>(old);var history=new java.util.concurrent.ConcurrentHashMap<String,McpGatewayCompiledRulesDTO>();history.put(old.identity().key(),old);
            var seen=new AtomicReference<McpGatewayCompiledRulesDTO>();
            McpMethodHandler ping=new McpMethodHandler(){public String method(){return "ping";}
                public Publisher<McpJsonRpcResponse> handle(McpJsonRpcRequest request,McpRequestContext context){return Mono.deferContextual(reactor -> {
                    var selected=reactor.get(McpGatewayCompiledRulesDTO.class);seen.set(selected);
                    assertSame(selected,context.attributes().get(McpGatewayCompiledRulesDTO.class.getName()));assertSame(context,reactor.get(McpRequestContext.class));
                    return Mono.just(McpJsonRpcResponse.success(request.id(),Map.of()));});}};
            var store=new SharedMcpTransportStore();
            var resolver=new McpEngineHttpHandler.SnapshotResolver(){public McpGatewayCompiledRulesDTO active(){return active.get();}public McpGatewayCompiledRulesDTO pinned(String key){return history.get(key);}};
            var handler=McpEngineHttpHandler.createConfigured(resolver,new McpMethodDispatcher(List.of(new top.egon.cola.component.yuheng.mcp.server.service.handler.McpInitializeHandler(),ping)),store,store,authenticator,new ObjectMapper(),fixture.clock,Duration.ofMinutes(1),Duration.ofMillis(20),65536);
            var initialized=handler.handle(McpTransportIntegrationTest.request("initialize","{\"protocolVersion\":\"2025-11-25\"}",null,"opaque")).block(Duration.ofSeconds(5));
            assertEquals(200,initialized.status());String sessionId=initialized.header("mcp-session-id");
            var session=Mono.from(store.find(sessionId)).block();assertEquals(old.identity().key(),session.snapshotIdentity());
            assertEquals(McpRequestObservationComponentTest.ISSUER.toASCIIString(),session.issuer());
            assertEquals(McpRequestObservationComponentTest.RESOURCE.toASCIIString(),session.resource());
            active.set(newer);
            assertEquals(200,handler.handle(McpTransportIntegrationTest.request("ping","{}",sessionId,"opaque")).block().status());assertSame(old,seen.get());
            assertEquals(403,handler.handle(McpTransportIntegrationTest.request("initialize","{\"protocolVersion\":\"2025-11-25\"}",null,"opaque")).block().status());
            history.clear();seen.set(null);
            assertEquals(503,handler.handle(McpTransportIntegrationTest.request("ping","{}",sessionId,"opaque")).block().status());assertEquals(null,seen.get());
            var legacy=new top.egon.cola.component.yuheng.mcp.common.transport.McpSessionStore.Session("legacy","billing","user-1","1001","client-1",McpRequestObservationComponentTest.NOW);
            Mono.from(store.create(legacy,Duration.ofMinutes(1))).block();
            assertEquals(503,handler.handle(McpTransportIntegrationTest.request("ping","{}","legacy","opaque")).block().status());
        }
    }


    @Test void storedSessionIssuerResourceAndCatalogMustStillMatchAtAdmission() {
        for(String mismatch:List.of("issuer","resource","catalog")) {
            try(var fixture=new McpRequestObservationComponentTest.NativeFixture()) {
                fixture.token("opaque",false);var snapshot=McpTransportIntegrationTest.snapshot("1");
                var original=new top.egon.cola.component.yuheng.mcp.common.transport.McpSessionStore.Session("session","billing","user-1","1001","client-1",McpRequestObservationComponentTest.NOW,
                        snapshot.identity().key(),McpRequestObservationComponentTest.ISSUER.toASCIIString(),McpRequestObservationComponentTest.RESOURCE.toASCIIString());
                var changed=new top.egon.cola.component.yuheng.mcp.common.transport.McpSessionStore.Session("session","billing","user-1","1001","client-1",McpRequestObservationComponentTest.NOW,
                        mismatch.equals("catalog")?McpTransportIntegrationTest.snapshot("2").identity().key():original.snapshotIdentity(),
                        mismatch.equals("issuer")?"https://other.example/tenants/1001":original.issuer(),
                        mismatch.equals("resource")?"https://resource.example/another":original.resource());
                var store=mock(top.egon.cola.component.yuheng.mcp.common.transport.McpSessionStore.class);
                when(store.find("session")).thenReturn(Mono.just(original),Mono.just(changed));
                var dispatcher=mock(McpMethodDispatcher.class);
                var handler=McpEngineHttpHandler.createConfigured(McpTransportIntegrationTest.resolver(snapshot),dispatcher,store,new SharedMcpTransportStore(),fixture.authenticator,
                        new ObjectMapper(),fixture.clock,Duration.ofMinutes(1),Duration.ofMillis(20),65536);
                assertEquals(403,handler.handle(McpTransportIntegrationTest.request("ping","{}","session","opaque")).block().status(),mismatch);
                org.mockito.Mockito.verifyNoInteractions(dispatcher);
                assertEquals(List.of("SUCCESS","DENIED"),fixture.events.stream().map(event -> event.result()).toList());
            }
        }
    }

    private CompiledMcpRules rules(String version) {
        var server = new McpRuntimeServer("server-" + version, "billing", "Billing " + version,
                null, null, Set.of(McpProtocolDialect.RC_2026_07_28),
                "https://resource.example/" + version, 0, true);
        return new McpRuleCompiler().compile(new McpRuleContent(List.of(server), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }
}
