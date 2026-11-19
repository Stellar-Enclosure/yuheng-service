package top.egon.cola.component.yuheng.contract.mcp;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeRemoteProvider;
import top.egon.cola.component.yuheng.core.mcp.app.McpAppArtifactStore;
import top.egon.cola.component.yuheng.core.mcp.remote.RemoteAuthProvider;
import top.egon.cola.component.yuheng.core.mcp.remote.RemoteMcpClient;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationRequest;
import top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationCall;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvocation;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvoker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The contract-only test classpath cannot obtain any port from core or a runtime implementation. */
class McpProtocolPortBoundaryTest {
    private static final List<Class<?>> PORT_TYPES = List.of(McpAppArtifactStore.class, RemoteAuthProvider.class,
            RemoteMcpClient.class, McpApprovalPort.class, McpAuthorizationPort.class, McpAuthorizationRequest.class,
            GatewayInvocationResult.class, GatewayOperationCall.class, GatewayOperationInvocation.class, GatewayOperationInvoker.class);
    private static final String SHA = "a".repeat(64);
    private static final Instant ISSUED = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    void theTenPublicTypesHaveOneDefinitionOwnedByTheContractArtifact() throws Exception {
        URI contractLocation = McpRuntimeRemoteProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path sources = Path.of("src/main/java");
        Path coreSources = Path.of("../stellar-enclosure-yuheng-core/src/main/java");
        for (Class<?> type : PORT_TYPES) {
            assertEquals(contractLocation, type.getProtectionDomain().getCodeSource().getLocation().toURI(), type.getName());
            String resource = type.getName().replace('.', '/') + ".class";
            assertEquals(1, Collections.list(type.getClassLoader().getResources(resource)).size(), resource);
            Path relativeSource = Path.of(type.getName().replace('.', '/') + ".java");
            assertTrue(Files.isRegularFile(sources.resolve(relativeSource)), relativeSource.toString());
            assertFalse(Files.exists(coreSources.resolve(relativeSource)), "the old source must not supply a competing class");
        }
    }

    @Test
    void extractedSourceAndDependencyClosureContainNoRuntimeImplementation() throws Exception {
        Set<String> allowedPortImports = PORT_TYPES.stream().map(Class::getName).collect(java.util.stream.Collectors.toSet());
        for (Class<?> type : PORT_TYPES) {
            Path source = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
            for (String line : Files.readAllLines(source)) {
                if (!line.startsWith("import ")) continue;
                String imported = line.substring(7, line.length() - 1);
                assertTrue(imported.startsWith("java.") || imported.startsWith("org.reactivestreams.")
                        || imported.startsWith("top.egon.cola.component.yuheng.contract.")
                        || allowedPortImports.contains(imported), "unexpected implementation import: " + imported);
            }
        }
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        boolean publisherIsAnExplicitCompileContract = false;
        Set<String> allowed = Set.of("stellar-enclosure-component-common-crypto", "protobuf-java", "jakarta.validation-api", "reactive-streams", "junit-jupiter");
        for (int index = 0; index < dependencies.getLength(); index++) {
            var dependency = (org.w3c.dom.Element) dependencies.item(index);
            String artifact = dependency.getElementsByTagName("artifactId").item(0).getTextContent().strip();
            assertTrue(allowed.contains(artifact), "runtime dependency introduced into contract: " + artifact);
            if (artifact.equals("reactive-streams")) {
                var scope = dependency.getElementsByTagName("scope");
                assertTrue(scope.getLength() == 0 || "compile".equals(scope.item(0).getTextContent().strip()));
                publisherIsAnExplicitCompileContract = true;
            }
        }
        assertTrue(publisherIsAnExplicitCompileContract);
        for (String implementation : List.of("org.springframework.context.ApplicationContext", "reactor.core.publisher.Mono",
                "top.egon.cola.component.yuheng.core.execution.DefaultGatewayExecutor")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(implementation, false, getClass().getClassLoader()), implementation);
        }
    }

    @Test
    void aTypedOperationInvocationPreservesPositionTraceAndBackpressureWithoutAnEngine() {
        Map<String, Object> path = new HashMap<>(Map.of("invoiceId", "i-7"));
        var call = new GatewayOperationCall("invoice.read", path, Map.of("expand", "items"), Map.of("mode", "summary"));
        path.put("invoiceId", "changed");
        var invocation = new GatewayOperationInvocation(call, "caller-token", "caller-1", "192.0.2.1",
                Map.of("TraceParent", "00-trace", "Authorization", "must-not-be-a-trace"));
        AtomicInteger executions = new AtomicInteger();
        GatewayOperationInvoker invoker = actual -> deferred(() -> {
            executions.incrementAndGet();
            assertSame(invocation, actual);
            assertEquals("i-7", actual.call().pathArguments().get("invoiceId"));
            assertEquals("items", actual.call().queryArguments().get("expand"));
            assertEquals(Map.of("traceparent", "00-trace"), actual.traceHeaders());
            return new GatewayInvocationResult(200, Map.of("content-type", List.of("application/json")), new byte[]{7});
        });
        var probe = new Probe<GatewayInvocationResult>();
        invoker.invoke(invocation).subscribe(probe);
        assertEquals(0, executions.get());
        assertTrue(probe.values.isEmpty());
        probe.subscription.request(1);
        assertEquals(1, executions.get());
        assertTrue(probe.completed);
        assertEquals("invoice.read", invocation.operationId());
        byte[] returned = probe.values.getFirst().body();
        returned[0] = 9;
        assertEquals(7, probe.values.getFirst().body()[0]);
        assertThrows(UnsupportedOperationException.class, () -> call.pathArguments().put("other", "x"));
        var cancelled = new Probe<GatewayInvocationResult>();
        invoker.invoke(invocation).subscribe(cancelled);
        cancelled.subscription.cancel();
        cancelled.subscription.request(1);
        assertEquals(1, executions.get());
        assertTrue(cancelled.values.isEmpty());
    }

    @Test
    void authorizationAndSingleUseApprovalRemainTypedPortsWithExactVersionEvidence() {
        var request = new McpAuthorizationRequest("https://issuer.example", "subject", "tenant", "client", "jti",
                "https://mcp.example", ISSUED, ISSUED.plusSeconds(60), Set.of("tool:read"), 3, 4, 5, "verified-user-token");
        McpAuthorizationPort authorization = actual -> deferred(() -> {
            assertSame(request, actual);
            assertEquals(Set.of("tool:read"), actual.requiredPermissions());
            assertEquals("verified-user-token", actual.userAccessToken());
            return McpAuthorizationPort.Decision.allowed(actual.minimumAuthVersion(), actual.minimumContextVersion(), actual.minimumPolicyVersion());
        });
        var decision = first(authorization.authorize(request));
        assertTrue(decision.allowed());
        assertEquals(3, decision.authVersion()); assertEquals(4, decision.contextVersion()); assertEquals(5, decision.policyVersion());
        var consume = new McpApprovalPort.ConsumptionRequest(SHA, "subject", "tenant", "client", "server", "invoice.read", SHA);
        AtomicBoolean consumed = new AtomicBoolean();
        McpApprovalPort approvals = actual -> deferred(() -> {
            assertSame(consume, actual);
            return consumed.compareAndSet(false, true) ? McpApprovalPort.Result.APPROVED : McpApprovalPort.Result.CONSUMED;
        });
        assertEquals(McpApprovalPort.Result.APPROVED, first(approvals.consume(consume)));
        assertEquals(McpApprovalPort.Result.CONSUMED, first(approvals.consume(consume)));
        assertThrows(IllegalArgumentException.class, () -> new McpAuthorizationRequest("issuer", "subject", "tenant", "client", "jti",
                "resource", ISSUED, ISSUED, Set.of("permission"), 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> McpAuthorizationPort.Decision.allowed(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new McpApprovalPort.ConsumptionRequest("short", "subject", "tenant", "client", "server", "tool", SHA));
    }

    @Test
    void artifactReaderAndWriterKeepDigestSizeAndDefensiveByteOwnership() {
        AtomicReference<McpAppArtifactStore.WriteRequest> persisted = new AtomicReference<>();
        McpAppArtifactStore.Writer writer = request -> {
            persisted.set(request);
            return new McpAppArtifactStore.StoredArtifact("artifact:immutable-7", request.expectedSha256(), request.content().length);
        };
        McpAppArtifactStore.Reader reader = request -> {
            assertEquals("artifact:immutable-7", request.artifactReference());
            assertEquals(SHA, request.expectedSha256());
            return new McpAppArtifactStore.ArtifactContent(persisted.get().content(), request.expectedSha256(), request.expectedSizeBytes());
        };
        byte[] source = "<main>approved</main>".getBytes(StandardCharsets.UTF_8);
        var write = new McpAppArtifactStore.WriteRequest("app", "1", source, SHA.toUpperCase(java.util.Locale.ROOT));
        source[0] = 'x';
        var stored = writer.write(write);
        var content = reader.read(new McpAppArtifactStore.ReadRequest(stored.artifactReference(), stored.sha256(), stored.sizeBytes()));
        assertEquals("<main>approved</main>", new String(content.content(), StandardCharsets.UTF_8));
        content.content()[0] = 'x';
        assertEquals('<', content.content()[0]);
        assertEquals("text/html;profile=mcp-app", McpAppArtifactStore.MCP_APP_MIME_TYPE);
        assertThrows(McpAppArtifactStore.ArtifactRejectedException.class, () -> new McpAppArtifactStore.ArtifactContent(new byte[]{1}, SHA, 2));
        assertThrows(McpAppArtifactStore.ArtifactRejectedException.class, () -> new McpAppArtifactStore.ReadRequest("artifact", SHA, 0));
    }

    @Test
    void remoteAuthenticationAndExchangePreserveOutboundCredentialsErrorsAndClose() {
        var provider = new McpRuntimeRemoteProvider("provider-1", "catalog", "Catalog", McpProtocolDialect.STABLE_2025_11_25,
                "STREAMABLE_HTTP", "endpoint:catalog", "secret:catalog", null, SHA, true);
        var authRequest = new RemoteAuthProvider.AuthRequest(provider, new RemoteAuthProvider.AuthContext("subject", "tenant", "client"));
        RemoteAuthProvider auth = actual -> deferred(() -> {
            assertSame(authRequest, actual);
            return new RemoteAuthProvider.OutboundAuthentication(Map.of("Authorization", "Bearer outbound-only"), null);
        });
        var approvedAuth = first(auth.resolve(authRequest));
        AtomicInteger closes = new AtomicInteger();
        RemoteMcpClient.Factory clients = actual -> {
            assertSame(provider, actual);
            return new RemoteMcpClient() {
                @Override public Publisher<ExchangeResponse> exchange(ExchangeRequest request) {
                    return deferred(() -> {
                        assertEquals("Bearer outbound-only", request.headers().get("authorization"));
                        assertEquals("tools/call", request.method());
                        assertEquals(Duration.ofSeconds(2), request.timeout());
                        return ExchangeResponse.success(Map.of("content", "approved result"), Map.of("mcp-session-id", "session-7"));
                    });
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
        };
        try (RemoteMcpClient client = clients.create(provider)) {
            var exchange = new RemoteMcpClient.ExchangeRequest(provider, 7L, "tools/call", Map.of("name", "invoice.read"), Map.of(),
                    approvedAuth.headers(), approvedAuth.tlsProfileReference(), Duration.ofSeconds(2));
            var response = first(client.exchange(exchange));
            assertEquals("approved result", response.result().get("content"));
            assertEquals("session-7", response.headers().get("mcp-session-id"));
            assertNull(response.error());
        }
        assertEquals(1, closes.get());
        var error = RemoteMcpClient.ExchangeResponse.failure(-32602, "invalid params", Map.of("reason", "invalid"), Map.of());
        assertNull(error.result()); assertEquals(-32602, error.error().code());
        assertThrows(IllegalArgumentException.class, () -> new RemoteAuthProvider.OutboundAuthentication(Map.of("Cookie", "session=private"), null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteAuthProvider.OutboundAuthentication(Map.of("Authorization", "Bearer x\r\ninjected: yes"), null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteMcpClient.ExchangeRequest(provider, 7, "tools/list", Map.of(), Map.of(), Map.of(), null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new GatewayInvocationResult(99, Map.of(), new byte[0]));
    }

    private static <T> T first(Publisher<T> publisher) {
        var probe = new Probe<T>();
        publisher.subscribe(probe);
        assertTrue(probe.values.isEmpty(), "a contract publisher must wait for demand");
        probe.subscription.request(1);
        assertNull(probe.failure);
        assertTrue(probe.completed);
        assertEquals(1, probe.values.size());
        return probe.values.getFirst();
    }

    private static <T> Publisher<T> deferred(Supplier<T> value) {
        return subscriber -> subscriber.onSubscribe(new Subscription() {
            private boolean done;
            @Override public void request(long count) {
                if (done) return;
                done = true;
                if (count <= 0) { subscriber.onError(new IllegalArgumentException("positive demand required")); return; }
                subscriber.onNext(value.get());
                subscriber.onComplete();
            }
            @Override public void cancel() { done = true; }
        });
    }

    private static final class Probe<T> implements Subscriber<T> {
        private final List<T> values = new ArrayList<>();
        private Subscription subscription;
        private Throwable failure;
        private boolean completed;
        @Override public void onSubscribe(Subscription value) { subscription = value; }
        @Override public void onNext(T value) { values.add(value); }
        @Override public void onError(Throwable value) { failure = value; }
        @Override public void onComplete() { completed = true; }
    }
}
