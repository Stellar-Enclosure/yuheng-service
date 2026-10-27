package top.egon.cola.component.yuheng.engine.http.filter;

import java.util.List;
import java.util.function.BiFunction;
import org.reactivestreams.Publisher;
import top.egon.cola.component.yuheng.core.exchange.GatewayExchange;
import top.egon.cola.component.yuheng.core.exchange.GatewayResponse;
import top.egon.cola.component.yuheng.core.filter.GatewayFilter;
import top.egon.cola.component.yuheng.core.filter.GatewayFilterChain;
import top.egon.cola.component.yuheng.core.filter.GatewayFilterStage;
import top.egon.cola.component.yuheng.engine.http.service.AbstractGatewayHttpStageExchange;

/** Creates the mandatory stages once when the HTTP pipeline is assembled. */
public final class HttpFilterFactory {
    private HttpFilterFactory() { }

    public static List<GatewayFilter> mandatoryFilters() {
        return List.of(
                new StageFilter("yuheng-http-cors", GatewayFilterStage.CORS,
                        AbstractGatewayHttpStageExchange::cors),
                new StageFilter("yuheng-http-security", GatewayFilterStage.AUTHENTICATION,
                        AbstractGatewayHttpStageExchange::security),
                new StageFilter("yuheng-http-governance", GatewayFilterStage.RATE_CONCURRENCY,
                        AbstractGatewayHttpStageExchange::governance),
                new StageFilter("yuheng-http-invocation", GatewayFilterStage.INVOCATION,
                        (exchange, chain) -> exchange.invoke()));
    }

    private record StageFilter(String id, GatewayFilterStage stage,
            BiFunction<AbstractGatewayHttpStageExchange, GatewayFilterChain,
                    Publisher<GatewayResponse>> invocation) implements GatewayFilter {
        @Override
        public int order() {
            return 0;
        }

        @Override
        public Publisher<GatewayResponse> filter(GatewayExchange exchange, GatewayFilterChain chain) {
            if (!(exchange instanceof AbstractGatewayHttpStageExchange staged)) {
                throw new IllegalArgumentException("gateway HTTP pipeline requires a staged HTTP exchange");
            }
            return invocation.apply(staged, chain);
        }
    }
}
