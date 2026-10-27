package top.egon.cola.component.yuheng.engine.http.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.core.execution.DefaultGatewayExecutor;
import top.egon.cola.component.yuheng.core.execution.GatewayExecutor;
import top.egon.cola.component.yuheng.core.exchange.GatewayExchange;
import top.egon.cola.component.yuheng.core.filter.DefaultGatewayFilterChain;
import top.egon.cola.component.yuheng.core.filter.GatewayFilter;
import top.egon.cola.component.yuheng.core.filter.GatewayFilterStage;
import top.egon.cola.component.yuheng.engine.http.filter.HttpFilterFactory;
import top.egon.cola.component.yuheng.runtime.http.service.GatewayOutboundHttpResponse;
import top.egon.cola.component.yuheng.runtime.http.websocket.domain.GatewayWebSocketHandshakeResult;

/** Executes mandatory HTTP stages and any explicitly supplied additional filters. */
public final class GatewayHttpExecutionPipeline {
    private final GatewayExecutor executor;

    public GatewayHttpExecutionPipeline() {
        this(List.of());
    }

    public GatewayHttpExecutionPipeline(List<GatewayFilter> additionalFilters) {
        List<GatewayFilter> filters = new ArrayList<>(HttpFilterFactory.mandatoryFilters());
        for (GatewayFilter filter : Objects.requireNonNull(additionalFilters, "additionalFilters")) {
            Objects.requireNonNull(filter, "filter");
            if (filter.stage().order() >= GatewayFilterStage.INVOCATION.order()) {
                throw new IllegalArgumentException("additional HTTP filters must precede invocation");
            }
            filters.add(filter);
        }
        executor = new DefaultGatewayExecutor(new DefaultGatewayFilterChain(filters),
                (exchange, failure) -> stage(exchange).fail(failure));
    }

    public Mono<GatewayOutboundHttpResponse> execute(AbstractGatewayHttpStageExchange exchange) {
        return Mono.from(executor.execute(exchange)).map(ignored -> exchange.outbound());
    }

    public Mono<GatewayWebSocketHandshakeResult> executeWebSocket(AbstractGatewayHttpStageExchange exchange) {
        return Mono.from(executor.execute(exchange)).map(ignored -> exchange.webSocketResult());
    }

    private static AbstractGatewayHttpStageExchange stage(GatewayExchange exchange) {
        if (exchange instanceof AbstractGatewayHttpStageExchange staged) {
            return staged;
        }
        throw new IllegalArgumentException("gateway HTTP pipeline requires a staged HTTP exchange");
    }
}
