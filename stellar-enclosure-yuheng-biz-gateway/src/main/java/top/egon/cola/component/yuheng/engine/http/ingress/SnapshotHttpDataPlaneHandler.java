package top.egon.cola.component.yuheng.engine.http.ingress;

import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.core.route.CompiledHttpRouteIndex;
import top.egon.cola.component.yuheng.core.route.HttpRouteCompiler;
import top.egon.cola.component.yuheng.engine.http.service.DefaultGatewayHttpDataPlaneHandler;
import top.egon.cola.component.yuheng.engine.rule.domain.ApiRpcGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.runtime.http.domain.GatewayInboundHttpRequest;
import top.egon.cola.component.yuheng.runtime.http.service.GatewayHttpDataPlaneHandler;
import top.egon.cola.component.yuheng.runtime.http.service.GatewayOutboundHttpResponse;
import top.egon.cola.component.yuheng.runtime.http.websocket.domain.GatewayPreparedWebSocketSession;
import top.egon.cola.component.yuheng.runtime.http.websocket.domain.GatewayWebSocketHandshakeResult;
import top.egon.cola.component.yuheng.runtime.http.websocket.service.GatewayWebSocketPeer;
import top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier;

/** Keeps routing and all policy decisions on the same activated generation. */
public final class SnapshotHttpDataPlaneHandler implements GatewayHttpDataPlaneHandler {
    private static final CompiledHttpRouteIndex EMPTY_ROUTES =
            new HttpRouteCompiler().compile(List.of());

    private final DefaultGatewayHttpDataPlaneHandler delegate;
    private final GatewayRuleActivationApplier<ApiRpcGatewayCompiledRulesDTO> activation;

    public SnapshotHttpDataPlaneHandler(
            DefaultGatewayHttpDataPlaneHandler delegate,
            GatewayRuleActivationApplier<ApiRpcGatewayCompiledRulesDTO> activation) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.activation = Objects.requireNonNull(activation, "activation");
    }

    @Override
    public Mono<GatewayOutboundHttpResponse> handle(
            AccessZone zone, GatewayInboundHttpRequest request) {
        return Mono.defer(() -> {
            var lease = activation.acquire();
            if (lease == null) {
                return delegate.handle(zone, request, null, EMPTY_ROUTES);
            }
            var rules = lease.rules();
            return Mono.defer(() -> delegate.handle(zone, request, rules, rules.httpRoutes()))
                    .map(response -> response
                            .withBody(response.body().doFinally(signal -> lease.close()))
                            .onAbandon(lease::close))
                    .doOnSuccess(response -> {
                        if (response == null) {
                            lease.close();
                        }
                    })
                    .doOnError(failure -> lease.close())
                    .doOnCancel(lease::close)
                    .doOnDiscard(GatewayOutboundHttpResponse.class,
                            GatewayOutboundHttpResponse::abandon);
        });
    }

    @Override
    public Mono<GatewayWebSocketHandshakeResult> prepareWebSocket(
            AccessZone zone, GatewayInboundHttpRequest request) {
        return Mono.defer(() -> {
            var lease = activation.acquire();
            if (lease == null) {
                return delegate.prepareWebSocket(zone, request, null, EMPTY_ROUTES);
            }
            var rules = lease.rules();
            return Mono.defer(() -> delegate.prepareWebSocket(
                            zone, request, rules, rules.httpRoutes()))
                    .map(result -> {
                        if (result instanceof GatewayWebSocketHandshakeResult.Accepted accepted) {
                            return (GatewayWebSocketHandshakeResult)
                                    new GatewayWebSocketHandshakeResult.Accepted(
                                            accepted.session().onDispose(lease::close));
                        }
                        lease.close();
                        return result;
                    })
                    .doOnSuccess(result -> {
                        if (result == null) {
                            lease.close();
                        }
                    })
                    .doOnError(failure -> lease.close())
                    .doOnCancel(lease::close)
                    .doOnDiscard(GatewayWebSocketHandshakeResult.Accepted.class,
                            accepted -> accepted.session().dispose());
        });
    }

    @Override
    public Mono<Void> bridgeWebSocket(
            GatewayPreparedWebSocketSession upstream, GatewayWebSocketPeer downstream) {
        return delegate.bridgeWebSocket(upstream, downstream);
    }
}
