package top.egon.cola.component.yuheng.mcp.engine.mcp.controller;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.apache.catalina.startup.Tomcat;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import reactor.core.publisher.Sinks;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpResponse;
import top.egon.cola.component.yuheng.mcp.engine.config.McpGatewayEngineProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Timeout(10)
class McpProtocolControllerTest {
    @Test
    void dedicatedProtocolControllerUsesQualifiedActualMvcInjection() throws Exception {
        assertNotNull(McpProtocolController.class.getAnnotation(RestController.class));
        var ctor = McpProtocolController.class.getDeclaredConstructors()[0];
        assertNotNull(ctor.getAnnotation(Autowired.class));
        assertEquals(List.of("gatewayMcpHttpHandler", "mcpGatewayListenerProperties"),
                java.util.Arrays.stream(ctor.getParameters())
                        .map(p -> p.getAnnotation(Qualifier.class).value()).toList());
    }

    @Test
    void mvcRoutesExactStableLegacyAndMetadataPathsAndPreservesWireResponse() throws Exception {
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(McpHttpResponse.json(202,
                "{\"jsonrpc\":\"2.0\",\"result\":{}}",
                Map.of("mcp-session-id", List.of("session-1"),
                        "www-authenticate", List.of("Bearer realm=\"a\"", "Bearer realm=\"b\"")))));
        MockMvc mvc = mvc(handler, 1024);
        for (var request : List.of(post("/mcp/main").content("{\"id\":1}"),
                post("/legacy/mcp/main?sessionId=session-1").content("{}"),
                get("/.well-known/oauth-protected-resource/mcp/main"), delete("/mcp/main"))) {
            var response = finish(mvc, mvc.perform(request.header("Authorization", "Bearer synthetic")
                    .contentType("application/json")).andReturn()).getResponse();
            assertEquals(202, response.getStatus());
            assertEquals("session-1", response.getHeader("mcp-session-id"));
            assertEquals(2, response.getHeaders("www-authenticate").size());
            assertEquals("{\"jsonrpc\":\"2.0\",\"result\":{}}", response.getContentAsString());
        }
        var captured = ArgumentCaptor.forClass(McpHttpRequest.class);
        verify(handler, times(4)).handle(captured.capture());
        assertEquals("/legacy/mcp/main?sessionId=session-1", captured.getAllValues().get(1).path());
        assertEquals("{\"id\":1}", captured.getAllValues().getFirst().body());
        assertEquals("DELETE", captured.getAllValues().getLast().method());
        assertEquals(404, mvc.perform(get("/ordinary/api")).andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(get("/mcp/main/another-path")).andReturn().getResponse().getStatus());
        verifyNoMoreInteractions(handler);
    }

    @Test
    void mvcPreservesBodyAndMethodMirrorWithoutTransportRewriting() throws Exception {
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(McpHttpResponse.empty(204)));
        var mvc = mvc(handler, 1024);
        var result = finish(mvc, mvc.perform(post("/mcp/main")
                .header("Mcp-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call").header("Mcp-Name", "different")
                .contentType("application/json")
                .content("{\"method\":\"tools/call\",\"params\":{\"name\":\"body-name\"}}"))
                .andReturn());
        assertEquals(204, result.getResponse().getStatus());
        var captor = ArgumentCaptor.forClass(McpHttpRequest.class);
        verify(handler).handle(captor.capture());
        assertEquals("different", captor.getValue().header("mcp-name"));
        assertTrue(captor.getValue().body().contains("body-name"));
    }

    @Test
    void rejectsRepeatedSecurityAndProtocolHeadersBeforeCallingKernel() throws Exception {
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(McpHttpResponse.empty(204)));
        var mvc = mvc(handler, 1024);
        for (String header : List.of("Authorization", "Mcp-Session-Id", "Mcp-Protocol-Version",
                "Mcp-Method", "Mcp-Name", "Last-Event-ID", "Origin")) {
            var response = finish(mvc, mvc.perform(post("/mcp/main")
                    .header(header, "first", "second").content("{}")).andReturn()).getResponse();
            assertEquals(400, response.getStatus(), header);
            assertEquals("no-store", response.getHeader("cache-control"));
            assertTrue(response.getContentAsString().contains("MCP_AMBIGUOUS_HEADER"));
        }
        verifyNoInteractions(handler);
    }

    @Test
    void repeatedContainerContentHeadersAreRejectedBeforeBodyRead() {
        var handler = mock(McpEngineHttpHandler.class);
        for (String header : List.of("Content-Type", "Content-Length", "Host")) {
            var request = new MockHttpServletRequest("POST", "/mcp/main") {
                @Override public java.util.Enumeration<String> getHeaderNames() {
                    return java.util.Collections.enumeration(List.of(header));
                }
                @Override public java.util.Enumeration<String> getHeaders(String name) {
                    return java.util.Collections.enumeration(List.of("first", "second"));
                }
                @Override public ServletInputStream getInputStream() { throw new AssertionError("must not read"); }
            };
            assertEquals(400, new McpProtocolController(handler, properties(1024))
                    .protocol(request, new MockHttpServletResponse()).block().getStatusCode().value());
        }
        verifyNoInteractions(handler);
    }

    @Test
    void servletPeerIsTheOnlyTrustedAttributeAndForwardedIdentityCannotChooseZone() {
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(McpHttpResponse.empty(204)));
        var request = request("GET", "/mcp/main");
        request.setRemoteAddr("192.0.2.1");
        request.addHeader("Authorization", "Bearer synthetic");
        request.addHeader("Accept", "application/json");
        request.addHeader("Accept", "text/event-stream");
        request.addHeader("Forwarded", "for=198.51.100.1");
        request.addHeader("X-Forwarded-For", "198.51.100.2");
        request.addHeader("X-Access-Zone", "INTERNAL");
        request.addHeader("X-Tenant-Id", "victim");
        request.addHeader("X-Envoy-Peer-Metadata", "forged");
        request.setAttribute("accessZone", "INTERNAL");
        request.setAttribute("principal", "forged");
        new McpProtocolController(handler, properties(1024)).protocol(request, new MockHttpServletResponse()).block();
        var captured = ArgumentCaptor.forClass(McpHttpRequest.class);
        verify(handler).handle(captured.capture());
        var input = captured.getValue();
        assertEquals(Map.of("remoteAddress", "192.0.2.1"), input.attributes());
        assertEquals("Bearer synthetic", input.header("authorization"));
        assertEquals("application/json,text/event-stream", input.header("accept"));
        assertNull(input.header("x-forwarded-for"));
        assertNull(input.header("forwarded"));
        assertNull(input.header("x-access-zone"));
        assertNull(input.header("x-tenant-id"));
        assertNull(input.header("x-envoy-peer-metadata"));
    }

    @Test
    void emptyOptionalHeaderIsAbsentAndEmptyBearerDoesNotBecomeAnIdentity() {
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(McpHttpResponse.empty(401)));
        var request = request("GET", "/mcp/main");
        request.addHeader("Accept-Encoding", "");
        request.addHeader("Authorization", "");
        assertEquals(401, new McpProtocolController(handler, properties(1024))
                .protocol(request, new MockHttpServletResponse()).block().getStatusCode().value());
        var captured = ArgumentCaptor.forClass(McpHttpRequest.class);
        verify(handler).handle(captured.capture());
        assertNull(captured.getValue().header("accept-encoding"));
        assertNull(captured.getValue().header("authorization"));
    }

    @Test
    void declaredOversizeIsRejectedBeforeInputRead() {
        var handler = mock(McpEngineHttpHandler.class);
        var request = new MockHttpServletRequest("POST", "/mcp/main") {
            @Override public long getContentLengthLong() { return 1025; }
            @Override public ServletInputStream getInputStream() { throw new AssertionError("must not read"); }
        };
        var result = new McpProtocolController(handler, properties(1024)).protocol(request, new MockHttpServletResponse()).block();
        assertEquals(413, result.getStatusCode().value());
        verifyNoInteractions(handler);
    }

    @Test
    void unknownLengthUtf8IsBoundedInBytesAndInvalidEncodingFailsClosed() {
        var handler = mock(McpEngineHttpHandler.class);
        var controller = new McpProtocolController(handler, properties(3));
        var oversized = new MockHttpServletRequest("POST", "/mcp/main") {
            @Override public long getContentLengthLong() { return -1; }
        };
        oversized.setContent("中a".getBytes(StandardCharsets.UTF_8));
        assertEquals(413, controller.protocol(oversized, new MockHttpServletResponse()).block().getStatusCode().value());
        var invalid = request("POST", "/mcp/main");
        invalid.setContent(new byte[]{(byte) 0xc3, 0x28});
        assertEquals(400, controller.protocol(invalid, new MockHttpServletResponse()).block().getStatusCode().value());
        verifyNoInteractions(handler);
    }

    @Test
    void sseFramesFlushBeforeCompletionWithBoundedDemandAndNoReencoding() throws Exception {
        var sinkRef = new AtomicReference<FluxSink<byte[]>>();
        var subscribed = new CountDownLatch(1);
        var flushed = new CountDownLatch(1);
        var requested = new ArrayList<Long>();
        var cancelled = new AtomicBoolean();
        Flux<byte[]> flux = Flux.<byte[]>create(sink -> {
            sinkRef.set(sink); sink.onRequest(n -> { synchronized (requested) { requested.add(n); } });
            sink.onCancel(() -> cancelled.set(true)); subscribed.countDown();
        });
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(new McpHttpResponse(200,
                Map.of("content-type", List.of("text/event-stream")), flux, true)));
        var body = new McpProtocolController(handler, properties(1024))
                .protocol(request("GET", "/mcp/main"), new MockHttpServletResponse()).block().getBody();
        var bytes = new ByteArrayOutputStream();
        var output = new OutputStream() {
            @Override public void write(int b) { bytes.write(b); }
            @Override public void write(byte[] b) { bytes.writeBytes(b); }
            @Override public void flush() { flushed.countDown(); }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> { body.writeTo(output); return null; });
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));
            byte[] first = "id:7\nevent:message\ndata:{\"字\":1}\n\n".getBytes(StandardCharsets.UTF_8);
            sinkRef.get().next(first);
            assertTrue(flushed.await(2, TimeUnit.SECONDS));
            assertArrayEquals(first, bytes.toByteArray());
            assertFalse(future.isDone(), "first frame must flush while the stream remains open");
            byte[] second = ":heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
            sinkRef.get().next(second); sinkRef.get().complete();
            future.get(2, TimeUnit.SECONDS);
            assertArrayEquals(concat(first, second), bytes.toByteArray());
            synchronized (requested) { assertFalse(requested.isEmpty()); assertTrue(requested.stream().allMatch(n -> n == 1)); }
            assertFalse(cancelled.get(), "normal completion is distinct from disconnect");
        } finally { executor.shutdownNow(); }
    }

    @Test
    void outputFailureCancelsUpstreamAndPropagatesOriginalIoFailure() {
        var cancelled = new AtomicBoolean();
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(new McpHttpResponse(200, Map.of(),
                Flux.just(new byte[]{1, 2}).concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true)), true)));
        var body = new McpProtocolController(handler, properties(1024))
                .protocol(request("GET", "/mcp/main"), new MockHttpServletResponse()).block().getBody();
        var failure = new IOException("synthetic disconnected peer");
        var actual = assertThrows(IOException.class, () -> body.writeTo(new OutputStream() {
            @Override public void write(int b) throws IOException { throw failure; }
        }));
        assertSame(failure, actual);
        assertTrue(cancelled.get());
    }

    @Test
    void asyncTimeoutCancelsQuietPublisherAndReleasesWaitingWriter() throws Exception {
        var cancelled = new AtomicBoolean();
        var subscribed = new CountDownLatch(1);
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(new McpHttpResponse(200, Map.of(),
                Flux.<byte[]>never().doOnSubscribe(s -> subscribed.countDown())
                        .doOnCancel(() -> cancelled.set(true)), true)));
        var request = request("GET", "/mcp/main");
        request.setAsyncSupported(true);
        var context = (MockAsyncContext) request.startAsync(request, new MockHttpServletResponse());
        var body = new McpProtocolController(handler, properties(1024)).protocol(request, new MockHttpServletResponse()).block().getBody();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> { body.writeTo(OutputStream.nullOutputStream()); return null; });
            assertTrue(subscribed.await(2, TimeUnit.SECONDS));
            assertFalse(context.getListeners().isEmpty());
            for (var listener : context.getListeners()) { listener.onTimeout(new AsyncEvent(context)); }
            future.get(2, TimeUnit.SECONDS);
            assertTrue(cancelled.get());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void publisherFailureIsNotTurnedIntoASuccessfulCompletedStream() {
        var handler = mock(McpEngineHttpHandler.class);
        var error = new IllegalStateException("synthetic publisher failure");
        when(handler.handle(any())).thenReturn(Mono.just(new McpHttpResponse(200, Map.of(),
                Flux.error(error), true)));
        var body = new McpProtocolController(handler, properties(1024))
                .protocol(request("GET", "/mcp/main"), new MockHttpServletResponse()).block().getBody();
        assertSame(error, assertThrows(IllegalStateException.class,
                () -> body.writeTo(OutputStream.nullOutputStream())));
    }

    @Test
    void actualLoopbackServletFlushesFirstSseFrameBeforeSecondExists(@TempDir Path directory) throws Exception {
        byte[] first = "id:11\nevent:message\ndata:{\"result\":1}\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = ":heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
        Sinks.One<byte[]> later = Sinks.one();
        var handler = mock(McpEngineHttpHandler.class);
        when(handler.handle(any())).thenReturn(Mono.just(new McpHttpResponse(200,
                Map.of("content-type", List.of("text/event-stream"),
                        "cache-control", List.of("no-cache"), "mcp-session-id", List.of("tcp-session")),
                Flux.just(first).concatWith(later.asMono()), true)));
        var spring = new AnnotationConfigWebApplicationContext();
        spring.register(ServletFixtureConfiguration.class);
        spring.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("gatewayMcpHttpHandler", handler);
            factory.registerSingleton("mcpGatewayListenerProperties", properties(1024));
            factory.registerSingleton("mcpProtocolController", new McpProtocolController(handler, properties(1024)));
        });
        var tomcat = new Tomcat();
        tomcat.setBaseDir(directory.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", directory.toString());
        context.setParentClassLoader(getClass().getClassLoader());
        var wrapper = Tomcat.addServlet(context, "mcp", new DispatcherServlet(spring));
        wrapper.setAsyncSupported(true); wrapper.setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "mcp");
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()) {
            tomcat.start();
            var response = client.sendAsync(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/mcp/main"))
                    .GET().timeout(Duration.ofSeconds(4)).header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofInputStream()).get(4, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertEquals("tcp-session", response.headers().firstValue("mcp-session-id").orElseThrow());
            try (var body = response.body()) {
                // No second signal has been emitted: an aggregate response would deadlock here.
                assertArrayEquals(first, body.readNBytes(first.length));
                assertEquals(Sinks.EmitResult.OK, later.tryEmitValue(second));
                assertArrayEquals(second, body.readNBytes(second.length));
                assertEquals(-1, body.read());
            }
        } finally {
            later.tryEmitEmpty();
            tomcat.stop(); tomcat.destroy(); spring.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class ServletFixtureConfiguration { }

    private static MockMvc mvc(McpEngineHttpHandler handler, int maximum) {
        return MockMvcBuilders.standaloneSetup(new McpProtocolController(handler, properties(maximum))).build();
    }

    private static MvcResult finish(MockMvc mvc, MvcResult result) throws Exception {
        for (int i = 0; i < 3 && result.getRequest().isAsyncStarted(); i++) {
            result.getAsyncResult(3000);
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertFalse(result.getRequest().isAsyncStarted(), "MVC dispatch must complete");
        return result;
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static McpGatewayEngineProperties.ListenerProperties properties(int maximum) {
        return new Binder(new MapConfigurationPropertySource(Map.of(
                "listener.maximum-request-bytes", Integer.toString(maximum))))
                .bind("listener", Bindable.of(McpGatewayEngineProperties.ListenerProperties.class)).get();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
