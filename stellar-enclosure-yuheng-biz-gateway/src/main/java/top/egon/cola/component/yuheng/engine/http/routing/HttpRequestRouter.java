package top.egon.cola.component.yuheng.engine.http.routing;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.core.http.HttpRequestNormalizer;
import top.egon.cola.component.yuheng.core.http.NormalizedHttpRequest;
import top.egon.cola.component.yuheng.core.route.CompiledHttpRouteIndex;
import top.egon.cola.component.yuheng.core.route.HttpRouteMatch;
import top.egon.cola.component.yuheng.runtime.http.domain.GatewayInboundHttpRequest;
import top.egon.cola.component.yuheng.runtime.traffic.service.GatewayRequestResourceGuard;

/** Matches request headers before the request body is consumed. */
public final class HttpRequestRouter {
    private final HttpRequestNormalizer normalizer;
    private final GatewayRequestResourceGuard resourceGuard;

    public HttpRequestRouter(HttpRequestNormalizer normalizer, GatewayRequestResourceGuard resourceGuard) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.resourceGuard = Objects.requireNonNull(resourceGuard, "resourceGuard");
    }

    public Match resolve(AccessZone zone, GatewayInboundHttpRequest request,
            CompiledHttpRouteIndex routes, boolean preflightAware) {
        NormalizedHttpRequest normalized = normalizer.normalize(
                request.method(), request.host(), request.uri(), request.headers());
        resourceGuard.validate(normalized);
        String method = normalized.method();
        if (preflightAware && "OPTIONS".equalsIgnoreCase(method)) {
            String requested = request.headers().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("access-control-request-method"))
                    .map(Map.Entry::getValue)
                    .filter(values -> values != null && !values.isEmpty())
                    .map(List::getFirst)
                    .findFirst().orElse(null);
            if (requested != null && !requested.isBlank()) {
                method = requested.trim().toUpperCase(Locale.ROOT);
            }
        }
        HttpRouteMatch route = routes.match(normalized.host(), method,
                normalized.normalizedPath(), zone).orElse(null);
        return new Match(normalized, method, route);
    }

    public record Match(NormalizedHttpRequest request, String method, HttpRouteMatch route) { }
}
