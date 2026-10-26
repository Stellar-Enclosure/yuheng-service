package top.egon.cola.component.yuheng.edge.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
@Slf4j
public class EdgeProtocolExceptionHandler implements WebExceptionHandler {
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (exchange.getResponse().isCommitted()) {
            log.warn("EDGE response was committed before failure reporting; failureType={}", failure.getClass().getSimpleName());
            return Mono.empty();
        }
        return EdgeProtocolErrors.write(exchange, failure);
    }
}
