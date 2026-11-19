package top.egon.cola.component.yuheng.mcp.engine.mcp.controller;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpResponse;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Dedicated servlet transport for the MCP protocol kernel. The kernel remains
 * responsible for authentication, authorization, dialects and session ownership.
 * Only the servlet peer address becomes transport metadata; forwarded identity
 * and caller-selected access zones are never promoted into trusted attributes.
 */
@Slf4j
@RestController("mcpProtocolController")
@ConditionalOnProperty(prefix = "egon.cola.component.yuheng.engine.mcp", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class McpProtocolController {
    private static final Set<String> SINGLE_VALUE_HEADERS = Set.of(
            "authorization", "mcp-session-id", "mcp-protocol-version", "mcp-method",
            "mcp-name", "last-event-id", "content-type", "content-length", "host", "origin");
    private static final Set<String> UNTRUSTED_IDENTITY_HEADERS = Set.of(
            "forwarded", "x-real-ip", "x-access-zone", "access-zone", "x-user-id",
            "x-tenant-id", "x-client-id", "x-authenticated-user", "x-authenticated-tenant");

    @Qualifier("gatewayMcpHttpHandler")
    private final McpEngineHttpHandler handler;
    @Qualifier("mcpGatewayListenerProperties")
    private final McpGatewayEngineProperties.ListenerProperties properties;

    /** Exact protocol endpoints; unsupported HTTP verbs still receive the kernel's error. */
    @RequestMapping(path = {"/mcp/{serverCode}", "/legacy/mcp/{serverCode}",
            "/.well-known/oauth-protected-resource/mcp/{serverCode}"})
    public Mono<ResponseEntity<StreamingResponseBody>> protocol(
            HttpServletRequest request, HttpServletResponse servletResponse) {
        Map<String, String> headers = requestHeaders(request);
        if (headers == null) {
            return Mono.just(adapt(request, servletResponse, failure(400, "MCP_AMBIGUOUS_HEADER")));
        }
        long maximum = properties.maximumRequestBytes();
        if (maximum < 1 || maximum > Integer.MAX_VALUE) {
            return Mono.just(adapt(request, servletResponse, failure(503, "MCP_LISTENER_UNAVAILABLE")));
        }
        if (request.getContentLengthLong() > maximum) {
            return Mono.just(adapt(request, servletResponse, failure(413, "MCP_BODY_TOO_LARGE")));
        }
        String body;
        try {
            body = readBody(request, (int) maximum);
        } catch (BodyLimitException ignored) {
            return Mono.just(adapt(request, servletResponse, failure(413, "MCP_BODY_TOO_LARGE")));
        } catch (IOException ignored) {
            return Mono.just(adapt(request, servletResponse, failure(400, "MCP_BODY_INVALID")));
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (request.getQueryString() != null) {
            path += "?" + request.getQueryString();
        }
        String peer = request.getRemoteAddr();
        var input = new McpHttpRequest(request.getMethod(), path, headers, body,
                Map.of("remoteAddress", peer == null ? "unknown" : peer));
        return Mono.defer(() -> handler.handle(input))
                .map(response -> adapt(request, servletResponse, response));
    }

    private static Map<String, String> requestHeaders(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            List<String> values = Collections.list(request.getHeaders(name));
            if (values.isEmpty()) {
                continue;
            }
            if (SINGLE_VALUE_HEADERS.contains(lower) && values.size() != 1) {
                return null;
            }
            if (UNTRUSTED_IDENTITY_HEADERS.contains(lower) || lower.startsWith("x-forwarded-")
                    || lower.startsWith("x-envoy-") || lower.startsWith("x-auth-request-")) {
                continue;
            }
            // List-valued protocol negotiation headers retain all their values.
            String value = String.join(",", values);
            if (!value.isBlank()) {
                result.put(lower, value);
            }
        }
        return Map.copyOf(result);
    }

    private static String readBody(HttpServletRequest request, int maximum) throws IOException {
        var bytes = new ByteArrayOutputStream(Math.min(maximum, 8192));
        byte[] buffer = new byte[Math.min(maximum, 8192)];
        long total = 0;
        var input = request.getInputStream();
        for (int read; (read = input.read(buffer)) != -1;) {
            total += read;
            if (total > maximum) {
                throw new BodyLimitException();
            }
            bytes.write(buffer, 0, read);
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }

    private static ResponseEntity<StreamingResponseBody> adapt(
            HttpServletRequest request, HttpServletResponse servletResponse, McpHttpResponse response) {
        var headers = new HttpHeaders();
        response.headers().forEach(headers::addAll);
        StreamingResponseBody body = output -> {
            Sinks.Empty<Void> stopped = Sinks.empty();
            AsyncListener cancellation = new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) { stopped.tryEmitEmpty(); }
                @Override public void onTimeout(AsyncEvent event) { stopped.tryEmitEmpty(); }
                @Override public void onError(AsyncEvent event) { stopped.tryEmitEmpty(); }
                @Override public void onStartAsync(AsyncEvent event) {
                    event.getAsyncContext().addListener(this);
                }
            };
            // The subscriber belongs to the MVC writer, not to the request thread.
            // Closing/timeout completes the bridge before disposal, which also
            // wakes a writer waiting for the next event without interrupting a pool thread.
            if (request.isAsyncStarted()) {
                try {
                    request.getAsyncContext().addListener(cancellation);
                } catch (IllegalStateException completed) {
                    stopped.tryEmitEmpty();
                }
            }
            try (Stream<byte[]> stream = Flux.from(response.body())
                    .takeUntilOther(stopped.asMono()).toStream(1)) {
                var frames = stream.iterator();
                while (frames.hasNext()) {
                    output.write(frames.next());
                    if (response.flushPerEvent()) {
                        output.flush();
                        // Spring 7 may supply a non-flushing OutputStream. Flush the
                        // dedicated servlet response rather than changing global behavior.
                        servletResponse.flushBuffer();
                    }
                }
            } finally {
                stopped.tryEmitEmpty();
            }
        };
        return new ResponseEntity<>(body, headers, response.status());
    }

    private static McpHttpResponse failure(int status, String code) {
        return McpHttpResponse.json(status, "{\"error\":\"" + code + "\"}",
                Map.of("cache-control", List.of("no-store")));
    }

    private static final class BodyLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
