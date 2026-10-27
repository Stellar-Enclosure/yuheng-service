package top.egon.cola.component.yuheng.engine.http.response;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import top.egon.cola.component.yuheng.runtime.http.service.GatewayOutboundHttpResponse;
import top.egon.cola.component.yuheng.runtime.operation.adapter.HttpRpcUpstreamAdapter;
import top.egon.cola.component.yuheng.runtime.traffic.domain.GatewayTrafficRejectedException;

/** Builds the existing HTTP error envelope and preserves security response headers. */
public final class HttpErrorResponseFactory {
    private HttpErrorResponseFactory() { }

    public static GatewayOutboundHttpResponse error(int status, String code, String traceId) {
        return error(status, code, traceId, Map.of());
    }

    public static GatewayOutboundHttpResponse error(int status, String code, String traceId,
            Map<String, List<String>> extraHeaders) {
        String body = "{\"success\":false,\"code\":\"" + code + "\",\"traceId\":\"" + traceId + "\"}";
        var response = new GatewayOutboundHttpResponse(status,
                Map.of("content-type", List.of("application/json; charset=UTF-8")),
                Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body.getBytes(StandardCharsets.UTF_8))));
        return withSecurityHeaders(response, extraHeaders);
    }

    public static GatewayOutboundHttpResponse trafficError(GatewayTrafficRejectedException rejected, String traceId) {
        var response = error(rejected.httpStatus(), rejected.code(), traceId);
        if (rejected.retryAfterMillis() == 0) {
            return response;
        }
        Map<String, List<String>> headers = new LinkedHashMap<>(response.headers());
        headers.put("retry-after", List.of(Long.toString(Math.max(1, (rejected.retryAfterMillis() + 999) / 1000))));
        return response.withHeadersAndBody(headers, response.body());
    }

    public static GatewayOutboundHttpResponse rpcError(
            HttpRpcUpstreamAdapter.HttpRpcUpstreamException failure, String traceId) {
        int status = switch (failure.status().getCode()) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION -> 400;
            case UNAUTHENTICATED -> 401;
            case PERMISSION_DENIED -> 403;
            case NOT_FOUND -> 404;
            case ALREADY_EXISTS, ABORTED -> 409;
            case RESOURCE_EXHAUSTED -> 429;
            case DEADLINE_EXCEEDED -> 504;
            case UNAVAILABLE -> 503;
            default -> 502;
        };
        return error(status, "YUHENG_RPC_UPSTREAM_" + failure.status().getCode().name(), traceId);
    }

    public static GatewayOutboundHttpResponse withSecurityHeaders(
            GatewayOutboundHttpResponse response, Map<String, List<String>> securityHeaders) {
        if (securityHeaders == null || securityHeaders.isEmpty()) {
            return response;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>(response.headers());
        securityHeaders.forEach((name, values) -> merged.merge(name.toLowerCase(Locale.ROOT),
                List.copyOf(values), (existing, replacement) -> {
                    List<String> combined = new ArrayList<>(existing);
                    combined.addAll(replacement);
                    return List.copyOf(combined);
                }));
        return response.withHeadersAndBody(merged, response.body());
    }
}
