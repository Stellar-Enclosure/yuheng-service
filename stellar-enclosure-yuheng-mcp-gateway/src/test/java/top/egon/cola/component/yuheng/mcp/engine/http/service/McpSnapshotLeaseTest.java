package top.egon.cola.component.yuheng.mcp.engine.http.service;

import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpResponse;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.runtime.http.domain.GatewayInboundHttpRequest;
import top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSnapshotLeaseTest {
    @SuppressWarnings("unchecked")
    private final GatewayRuleActivationApplier<McpGatewayCompiledRulesDTO> activation =
            mock(GatewayRuleActivationApplier.class);
    @SuppressWarnings("unchecked")
    private final GatewayRuleActivationApplier.Lease<McpGatewayCompiledRulesDTO> lease =
            mock(GatewayRuleActivationApplier.Lease.class);
    private final McpGatewayCompiledRulesDTO snapshot = mock(McpGatewayCompiledRulesDTO.class);
    private final McpEngineHttpHandler handler = mock(McpEngineHttpHandler.class);
    private final McpGatewayEngineProperties.ListenerProperties properties =
            mock(McpGatewayEngineProperties.ListenerProperties.class);

    private McpGatewayHttpDataPlaneHandlerAdapter adapter() {
        when(handler.supports("/mcp/billing")).thenReturn(true);
        when(properties.maximumRequestBytes()).thenReturn(4096L);
        when(activation.acquire()).thenReturn(lease);
        when(lease.rules()).thenReturn(snapshot);
        when(snapshot.mcpRules()).thenReturn(mock(top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules.class));
        return new McpGatewayHttpDataPlaneHandlerAdapter(handler, properties, activation);
    }

    @Test
    void keepsSnapshotUntilStreamingResponseIsAbandoned() {
        when(handler.handle(any(McpHttpRequest.class), eq(snapshot)))
                .thenReturn(Mono.just(new McpHttpResponse(200, Map.of(), Flux.never(), true)));
        var response = adapter().handle(AccessZone.PUBLIC, request(Flux.empty())).block();
        assertEquals(200, response.status());
        verify(lease, never()).close();
        response.abandon();
        verify(lease).close();
    }

    @Test
    void releasesSnapshotWhenStreamingBodyCompletes() {
        when(handler.handle(any(McpHttpRequest.class), eq(snapshot)))
                .thenReturn(Mono.just(new McpHttpResponse(200, Map.of(),
                        Flux.deferContextual(context -> {
                            org.junit.jupiter.api.Assertions.assertSame(snapshot, context.get(
                                    top.egon.cola.component.yuheng.runtime.rule.domain.GatewayCompiledRulesDTO.class));
                            return Flux.just("event".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }), true)));
        var response = adapter().handle(AccessZone.PUBLIC, request(Flux.empty())).block();
        verify(lease, never()).close();
        response.body().doOnNext(org.springframework.core.io.buffer.DataBufferUtils::release).then().block();
        verify(lease).close();
    }

    @Test
    void cancellationWhileReadingTheBodyReleasesTheSnapshot() {
        var subscription = adapter().handle(AccessZone.PUBLIC, request(Flux.never())).subscribe();
        subscription.dispose();
        verify(lease).close();
        verify(handler, never()).handle(any(McpHttpRequest.class), any());
    }

    @Test
    void missingSnapshotStaysMissingEvenIfActivationChangesDuringBodyRead() {
        var body = reactor.core.publisher.Sinks.<org.springframework.core.io.buffer.DataBuffer>one();
        when(handler.handle(any(McpHttpRequest.class), isNull()))
                .thenReturn(Mono.just(McpHttpResponse.json(503, "{}", Map.of())));
        var adapter = adapter();
        when(activation.acquire()).thenReturn(null, lease);
        var future = adapter.handle(AccessZone.PUBLIC, request(body.asMono().flux())).toFuture();
        body.tryEmitEmpty();
        assertEquals(503, future.join().status());
        verify(handler).handle(any(McpHttpRequest.class), isNull());
        verify(lease, never()).close();
    }

    private GatewayInboundHttpRequest request(Flux<org.springframework.core.io.buffer.DataBuffer> body) {
        return new GatewayInboundHttpRequest("POST", "api.example.com", "/mcp/billing", Map.of(),
                new InetSocketAddress("127.0.0.1", 1234), body);
    }
}
