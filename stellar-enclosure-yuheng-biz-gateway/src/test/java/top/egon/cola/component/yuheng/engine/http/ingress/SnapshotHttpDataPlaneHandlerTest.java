package top.egon.cola.component.yuheng.engine.http.ingress;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.core.route.HttpRouteCompiler;
import top.egon.cola.component.yuheng.engine.http.service.DefaultGatewayHttpDataPlaneHandler;
import top.egon.cola.component.yuheng.engine.rule.domain.ApiRpcGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.runtime.http.domain.GatewayInboundHttpRequest;
import top.egon.cola.component.yuheng.runtime.http.service.GatewayOutboundHttpResponse;
import top.egon.cola.component.yuheng.runtime.http.websocket.domain.GatewayPreparedWebSocketSession;
import top.egon.cola.component.yuheng.runtime.http.websocket.domain.GatewayWebSocketHandshakeResult;
import top.egon.cola.component.yuheng.runtime.http.websocket.domain.GatewayWebSocketProxyContext;
import top.egon.cola.component.yuheng.runtime.http.websocket.service.GatewayWebSocketPeer;
import top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotHttpDataPlaneHandlerTest {
    @SuppressWarnings("unchecked")
    private final GatewayRuleActivationApplier<ApiRpcGatewayCompiledRulesDTO> activation =
            mock(GatewayRuleActivationApplier.class);
    @SuppressWarnings("unchecked")
    private final GatewayRuleActivationApplier.Lease<ApiRpcGatewayCompiledRulesDTO> lease =
            mock(GatewayRuleActivationApplier.Lease.class);
    private final ApiRpcGatewayCompiledRulesDTO rules = mock(ApiRpcGatewayCompiledRulesDTO.class);
    private final DefaultGatewayHttpDataPlaneHandler delegate = mock(DefaultGatewayHttpDataPlaneHandler.class);
    private final GatewayInboundHttpRequest request = new GatewayInboundHttpRequest("GET", "api.example.com",
            "/orders", Map.of(), new InetSocketAddress("127.0.0.1", 1234), Flux.empty());

    private SnapshotHttpDataPlaneHandler handler() {
        when(activation.acquire()).thenReturn(lease);
        when(lease.rules()).thenReturn(rules);
        when(rules.httpRoutes()).thenReturn(new HttpRouteCompiler().compile(List.of()));
        return new SnapshotHttpDataPlaneHandler(delegate, activation);
    }

    @Test
    void holdsLeaseUntilBodyCompletesOrIsAbandoned() {
        var response = new GatewayOutboundHttpResponse(200, Map.of(), Flux.empty());
        when(delegate.handle(eq(AccessZone.PUBLIC), eq(request), eq(rules), any()))
                .thenReturn(Mono.just(response));
        var managed = handler().handle(AccessZone.PUBLIC, request).block();
        verify(lease, never()).close();
        managed.body().then().block();
        verify(lease).close();
    }

    @Test
    void releasesLeaseWhenRequestIsCancelledBeforeAResponse() {
        when(delegate.handle(eq(AccessZone.PUBLIC), eq(request), eq(rules), any()))
                .thenReturn(Mono.never());
        var subscription = handler().handle(AccessZone.PUBLIC, request).subscribe();
        subscription.dispose();
        verify(lease).close();
    }

    @Test
    void webSocketKeepsLeaseUntilSessionIsDisposed() {
        var session = new GatewayPreparedWebSocketSession(mock(GatewayWebSocketProxyContext.class),
                mock(GatewayWebSocketPeer.class), null);
        when(delegate.prepareWebSocket(eq(AccessZone.PUBLIC), eq(request), eq(rules), any()))
                .thenReturn(Mono.just(new GatewayWebSocketHandshakeResult.Accepted(session)));
        var result = (GatewayWebSocketHandshakeResult.Accepted)
                handler().prepareWebSocket(AccessZone.PUBLIC, request).block();
        verify(lease, never()).close();
        result.session().dispose();
        verify(lease).close();
    }
}
