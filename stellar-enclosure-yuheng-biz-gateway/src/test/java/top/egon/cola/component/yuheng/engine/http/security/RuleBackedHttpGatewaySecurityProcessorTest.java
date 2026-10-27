package top.egon.cola.component.yuheng.engine.http.security;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.core.provider.ProviderProtocolType;
import top.egon.cola.component.yuheng.core.provider.ProviderServiceKey;
import top.egon.cola.component.yuheng.core.route.GatewayResponseMode;
import top.egon.cola.component.yuheng.core.route.HttpRouteMatch;
import top.egon.cola.component.yuheng.core.route.RuntimeHttpRoute;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleBackedHttpGatewaySecurityProcessorTest {

    @Test
    void pinnedSecurityPolicyCannotBecomeAnonymousAfterActivationChanges() {
        top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator.initialize(
                0L, java.time.Duration.ofMillis(5));
        var chain = mock(top.egon.cola.component.yuheng.runtime.security.service.GatewaySecurityChain.class);
        var policy = mock(top.egon.cola.component.yuheng.core.security.GatewaySecurityPolicy.class);
        when(policy.policyId()).thenReturn("identity");
        when(policy.providerTimeout()).thenReturn(java.time.Duration.ofSeconds(1));
        var old = mock(top.egon.cola.component.yuheng.engine.rule.domain.ApiRpcGatewayCompiledRulesDTO.class);
        var newer = mock(top.egon.cola.component.yuheng.engine.rule.domain.ApiRpcGatewayCompiledRulesDTO.class);
        var snapshot = mock(top.egon.cola.component.yuheng.contract.rule.GatewayRuleSnapshot.class);
        when(snapshot.releaseId()).thenReturn("old");
        when(old.snapshot()).thenReturn(snapshot);
        when(old.securityPolicies()).thenReturn(Map.of("identity", policy));
        when(newer.securityPolicies()).thenReturn(Map.of());
        when(chain.execute(any(), any(), same(policy), eq(top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol.HTTP)))
                .thenReturn(reactor.core.publisher.Mono.error(new IllegalStateException("authentication denied")));
        var processor = new RuleBackedHttpGatewaySecurityProcessor(chain, () -> newer,
                new top.egon.cola.component.yuheng.runtime.security.service.TrustedClientAddressResolver(List.of()),
                "engine-1");
        var route = new RuntimeHttpRoute("route", "operation", "group", Set.of(AccessZone.INTERNAL),
                "api.internal", Set.of("GET"), "/orders", false,
                new ProviderServiceKey("biz", "app", "prod", "default", ProviderProtocolType.HTTP,
                        "orders", "default", "v1", "http"), Set.of("identity"), 0,
                GatewayResponseMode.TRANSPARENT, Map.of());
        var request = new top.egon.cola.component.yuheng.runtime.http.domain.GatewayInboundHttpRequest(
                "GET", "api.internal", "/orders", Map.of(),
                new java.net.InetSocketAddress("127.0.0.1", 1234), reactor.core.publisher.Flux.empty());
        var normalized = new top.egon.cola.component.yuheng.core.http.HttpRequestNormalizer(32, 8192)
                .normalize(request.method(), request.host(), request.uri(), request.headers());
        assertThrows(IllegalStateException.class, () -> processor.authorize(AccessZone.INTERNAL, request,
                normalized, new HttpRouteMatch(route, Map.of()), "trace", old).block());
        verify(chain).execute(any(), any(), same(policy),
                eq(top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol.HTTP));
    }

    @Test
    void usesOnlyServerRouteBizAppIdentityForAuthorization() {
        RuntimeHttpRoute route = new RuntimeHttpRoute(
                "route-1", "orders.get", "group-1",
                Set.of(AccessZone.INTERNAL), "api.internal",
                Set.of("GET"), "/orders/{id}", false,
                new ProviderServiceKey(
                        "commerce", "orders", "prod", "default",
                        ProviderProtocolType.HTTP, "orders-api", "default",
                        "v1", "http"),
                Set.of("identity"), 0, GatewayResponseMode.TRANSPARENT,
                Map.of(
                        "applicationCode", "orders-admin",
                        "definitionSetId", "definition-1",
                        "mappingVersion", "5")
        );

        Map<String, String> attributes =
                RuleBackedHttpGatewaySecurityProcessor.securityAttributes(
                        new HttpRouteMatch(route, Map.of("id", "1")));

        assertEquals(Map.of(
                "tianquan-shoubing.biz-code", "commerce",
                "tianquan-shoubing.app-code", "orders",
                "tianquan-shoubing.env", "prod"), attributes);
    }
}
