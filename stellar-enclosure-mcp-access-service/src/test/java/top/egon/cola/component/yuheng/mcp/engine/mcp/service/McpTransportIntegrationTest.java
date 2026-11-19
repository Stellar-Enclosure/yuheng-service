package top.egon.cola.component.yuheng.mcp.engine.mcp.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import top.egon.cola.component.yuheng.contract.mcp.protocol.*;
import top.egon.cola.component.yuheng.contract.mcp.rule.*;
import top.egon.cola.component.yuheng.mcp.common.transport.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.SharedMcpTransportStore;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.mcp.rule.service.McpRuleCompiler;
import top.egon.cola.component.yuheng.mcp.server.service.*;
import top.egon.cola.component.yuheng.mcp.server.service.handler.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class McpTransportIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void cancellationClosesTheOuterPhysicalRequestScope() throws Exception {
        var store=new SharedMcpTransportStore();
        var seen=new AtomicReference<McpRequestObservationComponent>();
        var rules=new McpRuleCompiler().compile(content());
        var handler=new McpEngineHttpHandler(() -> rules,new McpMethodDispatcher(List.of(new McpInitializeHandler())),store,store,
                (request,server) -> Mono.deferContextual(context -> { seen.set(context.get(McpRequestObservationComponent.class));return Mono.never(); }),
                MAPPER,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(1),Duration.ofMillis(20),65536);
        var subscription=handler.handle(request("initialize","{\"protocolVersion\":\"2025-11-25\"}",null,"opaque")).subscribe();
        assertNotNull(seen.get()); subscription.dispose();
        long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(seen.get().active() && System.nanoTime()<until) Thread.onSpinWait();
        assertFalse(seen.get().active());
    }

    @Test void realNativeAuthenticationPreserves401403503AndNeverDispatchesRejectedBusiness() {
        for(String outcome:List.of("INVALID","TENANT","AUTHORITY","AUDIT","ALLOW")) {
            try(var fixture=new NativeFixture()) {
                fixture.token("opaque",true,outcome.equals("TENANT")?"1002":"1001",Set.of(SCOPE));
                fixture.unavailable=outcome.equals("AUTHORITY");fixture.auditUnavailable=outcome.equals("AUDIT");
                var business=new AtomicInteger();
                McpMethodHandler method=new McpMethodHandler(){public String method(){return "tools/list";}
                    public org.reactivestreams.Publisher<McpJsonRpcResponse> handle(McpJsonRpcRequest rpc,top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext context){
                        business.incrementAndGet(); assertFalse(context.attributes().containsKey("originalBearerToken"));
                        assertTrue(context.attributes().get(McpGatewayCompiledRulesDTO.class.getName()) instanceof McpGatewayCompiledRulesDTO);
                        return Mono.deferContextual(reactor -> {assertSame(context,reactor.get(top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext.class));
                            return Mono.just(McpJsonRpcResponse.success(rpc.id(),Map.of("tools",List.of())));});
                    }};
                var snapshot=snapshot("1");var store=new SharedMcpTransportStore();
                var handler=McpEngineHttpHandler.createConfigured(resolver(snapshot),new McpMethodDispatcher(List.of(method)),store,store,
                        fixture.authenticator,MAPPER,fixture.clock,Duration.ofMinutes(1),Duration.ofMillis(20),65536);
                var response=handler.handle(new McpHttpRequest("POST","/mcp/billing",Map.of("content-type","application/json","mcp-protocol-version","2026-07-28","mcp-method","tools/list", "authorization","Bearer "+(outcome.equals("INVALID")?"invalid":"opaque")),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"client\":\"fixture\"}}}",Map.of("identity.subject","forged"))).block(Duration.ofSeconds(5));
                int status=outcome.equals("ALLOW")?200:outcome.equals("INVALID")?401:outcome.equals("TENANT")?403:503;
                assertEquals(status,response.status(),outcome); assertEquals(status==200?1:0,business.get());
                if(status!=200) assertEquals("no-store",response.header("cache-control"));
                assertFalse(body(response).contains("opaque"));
            }
        }
    }

    @Test void formalFactoryRejectsSelfAssertedAttributesAndUnverifiedSnapshotOverloads() {
        var snapshot=snapshot("1");var store=new SharedMcpTransportStore();var dispatcher=mock(McpMethodDispatcher.class);
        var handler=McpEngineHttpHandler.createConfigured(resolver(snapshot),dispatcher,store,store,(request,server)->Mono.just(Map.of(
                "callerId","administrator","tenantId","1001","identity.issuer",ISSUER.toASCIIString(),"identity.resource-uri",RESOURCE.toASCIIString())),
                MAPPER,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(1),Duration.ofMillis(20),65536);
        assertEquals(503,handler.handle(request("initialize","{\"protocolVersion\":\"2025-11-25\"}",null,"forged")).block().status());
        assertEquals(503,handler.handle(request("initialize","{}",null,"forged"),snapshot).block().status());
        assertEquals(503,handler.handle(request("initialize","{}",null,"forged"),snapshot.mcpRules()).block().status());
        verifyNoInteractions(dispatcher);
    }

    @Test void formalAdmissionBuildsAttributesFromThePrivateProofRatherThanAnAuthenticatorMap() {
        try(var fixture=new NativeFixture()) {
            fixture.token("opaque",false);var store=new SharedMcpTransportStore();var snapshot=snapshot("1");
            McpMethodHandler method=new McpMethodHandler(){public String method(){return "tools/list";}
                public org.reactivestreams.Publisher<McpJsonRpcResponse> handle(McpJsonRpcRequest rpc,top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext context){
                    assertEquals("user-1",context.attributes().get("callerId"));assertFalse(context.attributes().containsKey("originalBearerToken"));
                    return Mono.just(McpJsonRpcResponse.success(rpc.id(),Map.of("tools",List.of())));}};
            McpEngineHttpHandler.IdentityAuthenticator decorated=(request,server)->fixture.authenticator.authenticate(request,server).map(attributes->{
                var altered=new HashMap<>(attributes);altered.put("callerId","administrator");altered.put("originalBearerToken","Bearer injected");return Map.copyOf(altered);});
            var handler=McpEngineHttpHandler.createConfigured(resolver(snapshot),new McpMethodDispatcher(List.of(method)),store,store,decorated,MAPPER,fixture.clock,Duration.ofMinutes(1),Duration.ofMillis(20),65536);
            var request=new McpHttpRequest("POST","/mcp/billing",Map.of("content-type","application/json","mcp-protocol-version","2026-07-28","mcp-method","tools/list","authorization","Bearer opaque"),
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"client\":\"fixture\"}}}",Map.of());
            assertEquals(200,handler.handle(request).block().status());
        }
    }

    @Test void stableInitializeReportsOnlyTheActuallySelectedTrustedCatalogueFileDigest() throws Exception {
        String field="top.egon/selectedMcpCatalogueSha256";
        for (String version:List.of("3","4")) {
            try(var fixture=new NativeFixture()) {
                fixture.token("opaque",false);
                var base=snapshot("1");
                var selected=new McpGatewayCompiledRulesDTO(base.ownerTenantId(),version,base.validUntil(),base.contentSha256(),
                        version.equals("3")?"d".repeat(64):"e".repeat(64),base.content(),base.mcpRules(),base.serverScopes(),base.bindingPins());
                var store=new SharedMcpTransportStore();
                var handler=McpEngineHttpHandler.createConfigured(resolver(selected),new McpMethodDispatcher(List.of(new McpInitializeHandler())),
                        store,store,fixture.authenticator,MAPPER,fixture.clock,Duration.ofMinutes(1),Duration.ofMillis(20),65536);
                var request=new McpHttpRequest("POST","/mcp/billing",Map.of("content-type","application/json","authorization","Bearer opaque"),
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"_meta\":{\""+field+"\":\"forged-request-value\"}}}",
                        Map.of(field,"forged-attribute-value",McpGatewayCompiledRulesDTO.class.getName(),"forged-catalogue"));
                var response=handler.handle(request).block(Duration.ofSeconds(5));
                assertEquals(200,response.status());
                var payload=body(response);var result=MAPPER.readTree(payload).path("result");
                assertEquals("2025-11-25",result.path("protocolVersion").asText());
                assertEquals(selected.fileSha256(),result.path("_meta").path(field).asText());
                assertEquals(1,result.path("_meta").size());
                assertFalse(payload.contains("forged-"));assertFalse(payload.contains("opaque"));
                assertFalse(result.path("_meta").has("content"));assertFalse(result.path("_meta").has("bindingPins"));
            }
        }
    }

    @Test void keepsInitializationAndMissingSessionProtocolShapesInProcess() throws Exception {
        var handler=handler(new SharedMcpTransportStore());
        var initialized=handler.handle(new McpHttpRequest("POST","/mcp/orders",Map.of("content-type","application/json"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}",Map.of())).block();
        assertEquals(200,initialized.status());assertNotNull(initialized.header("mcp-session-id"));
        var initialization=MAPPER.readTree(body(initialized)).path("result");
        assertEquals("2025-11-25",initialization.path("protocolVersion").asText());
        assertFalse(initialization.has("_meta"));
        var missing=handler.handle(new McpHttpRequest("POST","/mcp/orders",Map.of("content-type","application/json","mcp-protocol-version","2025-11-25"),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}",Map.of())).block();
        assertEquals(400,missing.status());
    }
    @Test void metadataUsesRegisteredCanonicalResourceAndOrdinaryPathsNeverFallback() throws Exception {
        var handler=handler(new SharedMcpTransportStore());
        var metadata=handler.handle(new McpHttpRequest("GET","/.well-known/oauth-protected-resource/mcp/orders",Map.of(),"",Map.of())).block();
        var json=MAPPER.readTree(body(metadata));
        assertEquals("https://resource.egon.top/yuheng-mcp",json.path("resource").asText());
        assertEquals(404,handler.handle(new McpHttpRequest("GET","/ordinary",Map.of(),"",Map.of())).block().status());
    }
    public static McpRuleContent content(){return new McpRuleContent(List.of(server()),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());}
    public static McpGatewayCompiledRulesDTO snapshot(String version){var content=content();return new McpGatewayCompiledRulesDTO(1001,version,NOW.plusSeconds(300),"a".repeat(64),version.equals("1")?"b".repeat(64):"c".repeat(64),content,new McpRuleCompiler().compile(content),Map.of("billing",Set.of(SCOPE)),Map.of());}
    public static McpEngineHttpHandler.SnapshotResolver resolver(McpGatewayCompiledRulesDTO snapshot){return new McpEngineHttpHandler.SnapshotResolver(){public McpGatewayCompiledRulesDTO active(){return snapshot;}public McpGatewayCompiledRulesDTO pinned(String identity){return snapshot.identity().key().equals(identity)?snapshot:null;}};}
    public static McpHttpRequest request(String method,String params,String session,String token){var headers=new HashMap<String,String>();headers.put("content-type","application/json");headers.put("authorization","Bearer "+token);if(!method.equals("initialize"))headers.put("mcp-protocol-version","2025-11-25");if(session!=null)headers.put("mcp-session-id",session);return new McpHttpRequest("POST","/mcp/billing",headers,"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""+method+"\",\"params\":"+params+"}",Map.of());}
    public static String body(McpHttpResponse response){return Flux.from(response.body()).map(b -> new String(b,java.nio.charset.StandardCharsets.UTF_8)).collectList().map(parts -> String.join("",parts)).block(Duration.ofSeconds(3));}
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

}
