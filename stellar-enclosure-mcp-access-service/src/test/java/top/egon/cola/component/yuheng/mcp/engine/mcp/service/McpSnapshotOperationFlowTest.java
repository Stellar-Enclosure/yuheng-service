package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvoker;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityGate;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.SharedMcpTransportStore;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingContextVO;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.mcp.prompt.service.McpPromptsGetHandler;
import top.egon.cola.component.yuheng.mcp.prompt.service.OperationPromptDriver;
import top.egon.cola.component.yuheng.mcp.resource.adapter.OperationResourceDriver;
import top.egon.cola.component.yuheng.mcp.resource.domain.McpResourceUriValidator;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourceCatalog;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourcesReadHandler;
import top.egon.cola.component.yuheng.mcp.server.service.McpMethodDispatcher;
import top.egon.cola.component.yuheng.mcp.tool.service.McpResultBinder;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolCatalog;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolsCallHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Domain snapshot handoff test. Inbound OAuth verification and real downstream transport have separate native tests. */
class McpSnapshotOperationFlowTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolResourceAndPromptKeepTheSelectedMountedVersionWhenActiveChangesDuringAuthentication() throws Exception {
        var fixture = McpLkgRecoveryIT.publication(directory, true); var runtime = fixture.runtime(); runtime.start();
        try {
            var oldFile = fixture.mounts().historicalCatalogs().getFirst();
            var old = fixture.compiler().loadHistorical(oldFile.file(), oldFile.sha256());
            var newer = runtime.active(); var active = new AtomicReference<>(old);
            List<String> selectedCatalogues = new ArrayList<>(); List<Long> selectedBindings = new ArrayList<>();
            AtomicInteger authCalls = new AtomicInteger();
            // Capture only the protocol's domain port. Selection and pins come from real verified files and fresh SI reads.
            GatewayOperationInvoker invoker = request -> Mono.deferContextual(context -> {
                McpGatewayCompiledRulesDTO snapshot = context.get(McpGatewayCompiledRulesDTO.class);
                assertSame(old, snapshot); assertSame(newer, active.get());
                var entries = snapshot.bindingPins().entrySet().stream().toList(); assertEquals(1, entries.size());
                var entry = entries.getFirst();
                BindingContextVO binding = fixture.bindings().requirePinned(entry.getKey(), entry.getValue());
                assertEquals(binding.operation().operationId(), request.operationId());
                BindingContextVO passed = context.getOrDefault(BindingContextVO.class, null);
                if (passed != null) assertEquals(binding.pin(), passed.pin());
                selectedCatalogues.add(snapshot.identity().key()); selectedBindings.add(binding.bindingVersion());
                byte[] body = ("{\"bindingVersion\":" + binding.bindingVersion() + "}").getBytes(StandardCharsets.UTF_8);
                return Mono.just(new GatewayInvocationResult(200, Map.of("content-type", List.of("application/json")), body));
            });
            var security = new McpSecurityGate(ignored -> Mono.just(McpAuthorizationPort.Decision.allowed(1L, 1L, 1L)),
                    ignored -> Mono.just(McpApprovalPort.Result.APPROVED), mapper);
            var catalog = new McpToolCatalog(() -> active.get().mcpRules());
            var resources = new McpResourceCatalog(() -> active.get().mcpRules(), new McpResourceUriValidator());
            var tools = new McpToolsCallHandler(catalog, new McpResultBinder(mapper), invoker, security, mapper, null,
                    () -> active.get().mcpRules(), null, (tool, request) -> {
                        Object selected = request.attributes().get(McpGatewayCompiledRulesDTO.class.getName());
                        assertSame(old, selected);
                        var selection = new McpPublishedBindingLoader.SelectionVO(old.ownerTenantId(), tool.serverCode(), tool.toolId());
                        return fixture.bindings().requirePinned(selection, old.bindingPins().get(selection));
                    });
            var dispatcher = new McpMethodDispatcher(List.of(tools,
                    new McpResourcesReadHandler(resources, List.of(new OperationResourceDriver(invoker)), security),
                    new McpPromptsGetHandler(() -> active.get().mcpRules(), List.of(new OperationPromptDriver(invoker)), security)));
            var store = new SharedMcpTransportStore();
            // Explicit protocol-unit authenticator fixture; it does not fabricate the formal role's private verified identity capability.
            var handler = new McpEngineHttpHandler(() -> active.get().mcpRules(), dispatcher, store, store,
                    (request, server) -> Mono.fromSupplier(() -> {
                        authCalls.incrementAndGet(); assertEquals("https://mcp.example.test/orders/v1", server.resourceUri());
                        active.set(newer); return identity(server.resourceUri());
                    }), mapper, fixture.clock(), Duration.ofMinutes(1), Duration.ofMillis(20), 4096);
            for (var call : List.of(
                    Map.entry("tools/call", Map.<String,Object>of("name", "lookup", "arguments", Map.of())),
                    Map.entry("resources/read", Map.<String,Object>of("uri", "egon://orders/report")),
                    Map.entry("prompts/get", Map.<String,Object>of("name", "review", "arguments", Map.of())))) {
                active.set(old);
                var response = handler.handle(request(call.getKey(), call.getValue()), old).block(Duration.ofSeconds(2));
                assertNotNull(response); String body = Flux.from(response.body()).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                        .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(2));
                assertEquals(200, response.status(), body); assertFalse(mapper.readTree(body).has("error"), body);
                assertSame(newer, active.get());
            }
            assertEquals(3, authCalls.get()); assertEquals(List.of(1L, 1L, 1L), selectedBindings);
            assertEquals(List.of(old.identity().key(), old.identity().key(), old.identity().key()), selectedCatalogues);
            var absent = handler.handle(request("tools/call", Map.of("name", "lookup", "arguments", Map.of())),
                    (McpGatewayCompiledRulesDTO) null).block(Duration.ofSeconds(2));
            assertNotNull(absent); assertEquals(503, absent.status()); assertEquals(3, authCalls.get());
        } finally { runtime.stop(); }
    }

    private McpHttpRequest request(String method, Map<String,Object> params) throws Exception {
        Map<String,String> headers = new LinkedHashMap<>(); headers.put("content-type", "application/json");
        headers.put("mcp-protocol-version", McpProtocolDialect.RC_2026_07_28.protocolVersion()); headers.put("mcp-method", method);
        if (params.get("name") instanceof String name) headers.put("mcp-name", name);
        Map<String,Object> boundedParams = new LinkedHashMap<>(params); boundedParams.put("_meta", Map.of("client", "test"));
        return new McpHttpRequest("POST", "/mcp/orders", headers,
                mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", boundedParams)), Map.of());
    }
    private Map<String,Object> identity(String resource) {
        return Map.ofEntries(Map.entry("callerId", "fixture-user"), Map.entry("tenantId", "7"),
                Map.entry("tianquan-shoubing.issuer", "https://identity.example.test/tenants/90001"),
                Map.entry("tianquan-shoubing.client-id", "fixture-client"), Map.entry("tianquan-shoubing.token-id", "fixture-token"),
                Map.entry("tianquan-shoubing.resource-uri", resource), Map.entry("tianquan-shoubing.issued-at", "2026-11-19T11:59:30Z"),
                Map.entry("tianquan-shoubing.expires-at", "2026-11-19T12:05:00Z"));
    }
}
