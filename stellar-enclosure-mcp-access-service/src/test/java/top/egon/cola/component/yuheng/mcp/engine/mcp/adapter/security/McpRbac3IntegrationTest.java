package top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import top.egon.cola.component.yuheng.core.mcp.security.*;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties.ServiceScopeBindingVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.*;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.*;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.*;
import top.egon.cola.platform.tianquan.jianshen.starter.cache.SingleFlightSnapshotLoader;
import top.egon.cola.platform.tianquan.jianshen.starter.client.Rbac3AuthorizationClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class McpRbac3IntegrationTest {
    private Rbac3McpAuthorizationAdapter configured(NativeFixture fixture, Function<String,SystemAuthorizationSnapshot> fetch,
            List<ServiceScopeBindingVO> serviceBindings) {
        Rbac3AuthorizationClient client = new Rbac3AuthorizationClient() {
            @Override public SystemAuthorizationSnapshot fetch(String code, top.egon.cola.platform.tianquan.shoubing.contract.IdentityPrincipal identity) {
                throw new AssertionError("original USER bearer must use the explicit token boundary");
            }
            @Override public SystemAuthorizationSnapshot fetch(String code, top.egon.cola.platform.tianquan.shoubing.contract.IdentityPrincipal identity, String token) {
                assertEquals("opaque-user", token); assertEquals("user-1", identity.subject());
                assertTrue(Thread.currentThread().getName().startsWith("boundedElastic"));
                return fetch.apply(code);
            }
        };
        return Rbac3McpAuthorizationAdapter.createConfigured(new SingleFlightSnapshotLoader(client,"yuheng-mcp",fixture.clock),
                fixture.observer, fixture.metrics, fixture.validation.getValidator(), serviceBindings, fixture.clock);
    }
    private McpAuthorizationPort.Decision call(Rbac3McpAuthorizationAdapter adapter, McpAuthorizationRequest request, McpRequestObservationComponent scope) {
        return Mono.from(adapter.authorize(request)).contextWrite(Context.of(McpRequestObservationComponent.class, scope)).block(Duration.ofSeconds(5));
    }
    private SystemAuthorizationSnapshot snapshot(String code) {
        Set<String> permissions = Set.of("invoice:pay", "mcp:billing:tool:pay_invoice:call");
        var scope = new CatalogScope("10","1001","billing",code,"default","prod");
        var view = new EffectivePermissionSnapshot(ISSUER.toASCIIString(),"user-1",scope,"1","7","3",
                new EffectivePermissionSnapshot.ActiveRoleContext(code,List.of("50"),List.of("50")),
                permissions.stream().sorted().toList(), NOW.plusSeconds(300));
        return new SystemAuthorizationSnapshot("1001","user-1","1",code,7L,3L,List.of("50"),null,null,
                permissions,Set.of(),Map.of(),Map.of(),"snapshot-checksum",NOW,NOW.plusSeconds(300),view,
                new RuntimePolicyBinding("yuheng-mcp-java","1","a".repeat(64),"b".repeat(64),"c".repeat(64)));
    }
    @Test void currentUserSnapshotIsQueriedWithOriginalBearerAndNoCachedDecision() {
        try (var fixture = new NativeFixture(); var scope = new McpRequestObservationComponent()) {
            fixture.token("opaque-user",false); fixture.authenticate(scope,"opaque-user");
            AtomicInteger fetched = new AtomicInteger();
            var adapter = configured(fixture, code -> { fetched.incrementAndGet(); return snapshot(code); },List.of());
            var request = fixture.request(scope,Set.of("invoice:pay"),7,3,3);
            assertTrue(call(adapter,request,scope).allowed()); assertTrue(call(adapter,request,scope).allowed());
            assertEquals(2,fetched.get()); assertEquals(1,fixture.events.size());
        }
    }
    @Test void fineDenialsShareExactlyOneOriginalProofAndAllowNeverAddsDeny() {
        try (var fixture = new NativeFixture(); var scope = new McpRequestObservationComponent()) {
            fixture.token("opaque-user",false); fixture.authenticate(scope,"opaque-user");
            var adapter = configured(fixture,this::snapshot,List.of());
            var denied = fixture.request(scope,Set.of("invoice:refund"),7,3,3);
            assertFalse(call(adapter,denied,scope).allowed()); assertFalse(call(adapter,denied,scope).allowed());
            assertTrue(call(adapter,fixture.request(scope,Set.of("invoice:pay"),7,3,3),scope).allowed());
            assertEquals(List.of("SUCCESS","DENIED"), fixture.events.stream().map(e -> e.result()).toList());
        }
    }
    @Test void unavailableSnapshotAndVersionFenceRemain503WithoutInventingDenial() {
        for (boolean unavailable : new boolean[]{false,true}) {
            try (var fixture = new NativeFixture(); var scope = new McpRequestObservationComponent()) {
                fixture.token("opaque-user",false); fixture.authenticate(scope,"opaque-user");
                AtomicInteger fetched = new AtomicInteger();
                var adapter = configured(fixture, code -> { fetched.incrementAndGet(); if (unavailable) throw new Rbac3AuthorizationClient.AuthorizationUnavailableException("controlled"); return snapshot(code); },List.of());
                assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,
                        () -> call(adapter,fixture.request(scope,Set.of("invoice:pay"),8,3,3),scope)).httpStatus());
                assertEquals(1,fetched.get()); assertEquals(1,fixture.events.size());
            }
        }
    }
    @Test void missingForeignAndClosedRequestFramesNeverLoadAnAllowingSnapshot() {
        try (var fixture = new NativeFixture(); var scope = new McpRequestObservationComponent(); var foreign = new McpRequestObservationComponent()) {
            fixture.token("opaque-user",false); fixture.authenticate(scope,"opaque-user");
            AtomicInteger fetched = new AtomicInteger(); var adapter = configured(fixture,code -> { fetched.incrementAndGet(); return snapshot(code); },List.of());
            var request = fixture.request(scope,Set.of("invoice:pay"),0,0,0);
            assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> Mono.from(adapter.authorize(request)).block()).httpStatus());
            assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> call(adapter,request,foreign)).httpStatus());
            scope.close(); assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> call(adapter,request,scope)).httpStatus());
            assertEquals(0,fetched.get());
        }
    }
    @Test void callerMetadataCannotSubstituteAnotherIdentityOrOriginalBearer() {
        for (boolean tokenForgery : new boolean[]{false,true}) {
            try (var fixture = new NativeFixture(); var scope = new McpRequestObservationComponent()) {
                fixture.token("opaque-user",false); fixture.authenticate(scope,"opaque-user"); var identity=scope.verifiedIdentity();
                var request = new McpAuthorizationRequest(ISSUER.toASCIIString(),tokenForgery ? identity.subject() : "administrator",identity.tenantId(),identity.clientId(),identity.tokenId(),
                        RESOURCE.toASCIIString(),identity.issuedAt(),identity.expiresAt(),Set.of("invoice:pay"),0,0,0,tokenForgery ? "forged" : null);
                var adapter = configured(fixture, code -> { throw new AssertionError("no PDP after forged metadata"); },List.of());
                assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> call(adapter,request,scope)).httpStatus());
            }
        }
    }
    @Test void serviceScopesRequireEveryExactDeploymentBindingAndNeverLoadUserSnapshot() {
        for (boolean mapped : new boolean[]{false,true}) {
            try (var fixture = new NativeFixture(); var scope = new McpRequestObservationComponent()) {
                fixture.token("opaque-service",true); fixture.authenticate(scope,"opaque-service");
                var bindings = mapped ? List.of(new ServiceScopeBindingVO(RESOURCE,"invoice:pay",Set.of(SCOPE)),new ServiceScopeBindingVO(RESOURCE,"invoice:read",Set.of(SCOPE))) : List.<ServiceScopeBindingVO>of();
                var adapter = configured(fixture, code -> { throw new AssertionError("SERVICE must not load a USER snapshot"); },bindings);
                assertEquals(mapped,call(adapter,fixture.request(scope,Set.of("invoice:pay","invoice:read"),0,0,0),scope).allowed());
                assertFalse(call(adapter,fixture.request(scope,Set.of("invoice:unknown"),0,0,0),scope).allowed());
                assertEquals(2,fixture.events.size());
            }
        }
    }
    @Test void serviceVersionMinimaRemainFencedAndExactResourceNeverBroadens() {
        try (var fixture = new NativeFixture(); var scope = new McpRequestObservationComponent()) {
            fixture.token("opaque-service",true); fixture.authenticate(scope,"opaque-service");
            var adapter = configured(fixture,code -> { throw new AssertionError("no USER PDP"); },List.of(new ServiceScopeBindingVO(RESOURCE,"invoice:pay",Set.of(SCOPE))));
            assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class,
                    () -> call(adapter,fixture.request(scope,Set.of("invoice:pay"),1,0,0),scope)).httpStatus());
            assertEquals(1,fixture.events.size());
        }
    }
    @Test void failedStrictDenialAuditStaysUnavailableWithoutRetry() {
        try (var fixture = new NativeFixture(); var scope = new McpRequestObservationComponent()) {
            fixture.token("opaque-user",false); fixture.authenticate(scope,"opaque-user"); fixture.denialUnavailable=true;
            var adapter = configured(fixture,this::snapshot,List.of()); var request=fixture.request(scope,Set.of("invoice:refund"),0,0,0);
            assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> call(adapter,request,scope)).httpStatus());
            assertEquals(503,assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> call(adapter,request,scope)).httpStatus());
            verify(fixture.authority,times(2)).appendAudit(any());
        }
    }
    @Test void deploymentScopeIndexIsFiniteImmutableAndRejectsDuplicateKeys() {
        try (var fixture = new NativeFixture()) {
            var binding=new ServiceScopeBindingVO(RESOURCE,"invoice:pay",Set.of(SCOPE));
            assertThrows(IllegalArgumentException.class, () -> configured(fixture,this::snapshot,List.of(binding,binding)));
            assertThrows(IllegalArgumentException.class, () -> configured(fixture,this::snapshot,Collections.nCopies(257,binding)));
        }
    }
}
