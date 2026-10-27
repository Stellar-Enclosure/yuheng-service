package top.egon.cola.component.yuheng.engine.rpc.service;

import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import top.egon.cola.component.yuheng.runtime.rpc.adapter.RpcProviderChannelCache;
import top.egon.cola.component.yuheng.engine.rpc.service.RpcMethodIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RpcGatewayHandlerRegistryTest {

    @BeforeAll
    static void bindTheProcessWideEngine() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    @Test
    void readsLatestRuleIndexWithoutRebuildingGrpcServer() {
        AtomicReference<RpcMethodIndex> rules =
                new AtomicReference<>(new RpcMethodIndex(Map.of()));
        RpcGatewayHandlerRegistry registry = new RpcGatewayHandlerRegistry(
                new RpcGatewayForwarder(
                        service -> {
                            throw new IllegalStateException();
                        },
                        new RpcProviderChannelCache(Duration.ofMillis(10)),
                        Duration.ofSeconds(1),
                        1024),
                rules::get
        );
        RpcMethodIndex replacement = new RpcMethodIndex(Map.of());

        rules.set(replacement);

        assertSame(replacement, registry.activeIndex());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pinsTheMethodAndPoliciesAtCallStartAndReleasesOnCancel() {
        String method = "orders.OrderService/Get";
        var oldRoute = route("old", method);
        var newRoute = route("new", method);
        var lookup = new RpcMethodIndex(Map.of(method, oldRoute));
        var rules = mock(top.egon.cola.component.yuheng.engine.rule.domain.ApiRpcGatewayCompiledRulesDTO.class);
        when(rules.rpcMethods()).thenReturn(new RpcMethodIndex(Map.of(method, newRoute)));
        var lease = (top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier.Lease<
                top.egon.cola.component.yuheng.engine.rule.domain.ApiRpcGatewayCompiledRulesDTO>)
                mock(top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier.Lease.class);
        when(lease.rules()).thenReturn(rules);
        var forwarder = mock(RpcGatewayForwarder.class);
        io.grpc.ServerCall.Listener<byte[]> downstream = mock(io.grpc.ServerCall.Listener.class);
        when(forwarder.handler(eq(newRoute), eq(rules))).thenReturn((call, headers) -> downstream);
        var registry = new RpcGatewayHandlerRegistry(forwarder, () -> lookup, () -> lease);
        var definition = registry.lookupMethod(method, null);
        var handler = (io.grpc.ServerCallHandler<byte[], byte[]>) definition.getServerCallHandler();
        io.grpc.ServerCall<byte[], byte[]> call = mock(io.grpc.ServerCall.class);

        var listener = handler.startCall(call, new io.grpc.Metadata());
        verify(forwarder).handler(newRoute, rules);
        verify(lease, never()).close();
        listener.onCancel();
        verify(downstream).onCancel();
        verify(lease).close();
    }

    private top.egon.cola.component.yuheng.engine.rpc.domain.RuntimeRpcRoute route(String id, String method) {
        var service = new top.egon.cola.component.yuheng.core.provider.ProviderServiceKey(
                "biz", "app", "local", "default",
                top.egon.cola.component.yuheng.core.provider.ProviderProtocolType.RPC,
                "orders", "default", "v1", "grpc");
        return new top.egon.cola.component.yuheng.engine.rpc.domain.RuntimeRpcRoute(
                id, id, method, service, "Request", "Response", "sha", java.util.Set.of(),
                top.egon.cola.component.yuheng.core.route.GatewayResponseMode.TRANSPARENT,
                false, Duration.ofSeconds(1));
    }
}
