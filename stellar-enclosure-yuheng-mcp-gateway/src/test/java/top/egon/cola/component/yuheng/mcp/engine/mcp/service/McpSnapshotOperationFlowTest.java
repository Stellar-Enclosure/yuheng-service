package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimePrompt;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeResource;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;
import top.egon.cola.component.yuheng.contract.rule.GatewayProviderServiceRef;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuleContent;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuleSnapshot;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuntimeOperation;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult;
import top.egon.cola.component.yuheng.core.provider.ProviderHealthState;
import top.egon.cola.component.yuheng.core.provider.ProviderInstance;
import top.egon.cola.component.yuheng.core.provider.ProviderRegistryState;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityGate;
import top.egon.cola.component.yuheng.mcp.common.transport.McpHttpRequest;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.SharedMcpTransportStore;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.mcp.prompt.service.McpPromptsGetHandler;
import top.egon.cola.component.yuheng.mcp.prompt.service.OperationPromptDriver;
import top.egon.cola.component.yuheng.mcp.resource.adapter.OperationResourceDriver;
import top.egon.cola.component.yuheng.mcp.resource.domain.McpResourceUriValidator;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourceCatalog;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourcesReadHandler;
import top.egon.cola.component.yuheng.mcp.rule.service.McpRuleCompiler;
import top.egon.cola.component.yuheng.mcp.server.service.McpMethodDispatcher;
import top.egon.cola.component.yuheng.mcp.tool.service.McpResultBinder;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolCatalog;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolsCallHandler;
import top.egon.cola.component.yuheng.runtime.operation.service.EngineGatewayOperationInvoker;
import top.egon.cola.component.yuheng.runtime.provider.domain.ProviderSelectionHandle;
import top.egon.cola.component.yuheng.runtime.rule.service.GatewayTrafficGovernance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSnapshotOperationFlowTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolResourceAndPromptInvokeTheOperationFromTheAuthenticatedServerVersion() throws Exception {
        var old = snapshot("old");
        var newer = snapshot("new");
        var active = new AtomicReference<>(old);
        var paths = new ArrayList<String>();
        var invoker = new EngineGatewayOperationInvoker(active::get,
                key -> new ProviderSelectionHandle(new ProviderInstance(key, "instance", "lease", "127.0.0.1",
                        8080, false, Map.of(), Instant.now().plusSeconds(60), ProviderRegistryState.REGISTERED,
                        ProviderHealthState.HEALTHY, ProviderHealthState.HEALTHY), () -> { }),
                GatewayTrafficGovernance.noop(), (provider, request, timeout) -> {
                    paths.add(request.pathAndQuery());
                    String value = request.pathAndQuery().contains("/tool") ? "{\"version\":\"old\"}"
                            : request.pathAndQuery().contains("/resource") ? "old-resource" : "old-prompt";
                    return Mono.just(new GatewayInvocationResult(200,
                            Map.of("content-type", List.of("application/json")), value.getBytes(StandardCharsets.UTF_8)));
                }, mapper, Duration.ofSeconds(2), 4096, 4096);
        var security = new McpSecurityGate(
                ignored -> Mono.just(McpAuthorizationPort.Decision.allowed(1L, 1L, 1L)),
                ignored -> Mono.just(McpApprovalPort.Result.APPROVED), mapper);
        var catalog = new McpToolCatalog(() -> active.get().mcpRules());
        var resources = new McpResourceCatalog(() -> active.get().mcpRules(), new McpResourceUriValidator());
        var dispatcher = new McpMethodDispatcher(List.of(
                new McpToolsCallHandler(catalog, new McpResultBinder(mapper), invoker, security,
                        mapper, null, () -> active.get().mcpRules()),
                new McpResourcesReadHandler(resources, List.of(new OperationResourceDriver(invoker)), security),
                new McpPromptsGetHandler(() -> active.get().mcpRules(),
                        List.of(new OperationPromptDriver(invoker)), security)));
        var store = new SharedMcpTransportStore();
        var handler = new McpEngineHttpHandler(() -> active.get().mcpRules(), dispatcher, store, store,
                (request, server) -> Mono.fromSupplier(() -> {
                    assertEquals("https://resource.example/old", server.resourceUri());
                    active.set(newer);
                    return identity();
                }), mapper, Clock.systemUTC(), Duration.ofMinutes(1), Duration.ofMillis(20), 4096);

        for (var call : List.of(
                Map.entry("tools/call", Map.<String, Object>of("name", "lookup", "arguments", Map.of())),
                Map.entry("resources/read", Map.<String, Object>of("uri", "egon://billing/report")),
                Map.entry("prompts/get", Map.<String, Object>of("name", "review", "arguments", Map.of())))) {
            active.set(old);
            var params = new java.util.LinkedHashMap<>(call.getValue());
            params.put("_meta", Map.of("client", "test"));
            var headers = new java.util.LinkedHashMap<String, String>();
            headers.put("content-type", "application/json");
            headers.put("mcp-protocol-version", McpProtocolDialect.RC_2026_07_28.protocolVersion());
            headers.put("mcp-method", call.getKey());
            if (params.get("name") instanceof String name) {
                headers.put("mcp-name", name);
            }
            String request = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1,
                    "method", call.getKey(), "params", params));
            var response = handler.handle(new McpHttpRequest("POST", "/mcp/billing", headers,
                    request, Map.of()), old).block(Duration.ofSeconds(2));
            String body = Flux.from(response.body()).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(2));
            assertEquals(200, response.status(), body);
            assertFalse(mapper.readTree(body).has("error"), body);
            assertTrue(body.contains("old"), body);
            assertEquals(newer, active.get());
        }
        assertEquals(3, paths.size());
        assertTrue(paths.get(0).startsWith("/old/tool"));
        assertTrue(paths.get(1).startsWith("/old/resource"));
        assertTrue(paths.get(2).startsWith("/old/prompt"));
    }

    private McpGatewayCompiledRulesDTO snapshot(String version) {
        var server = new McpRuntimeServer("server-" + version, "billing", "Billing " + version,
                null, null, Set.of(McpProtocolDialect.RC_2026_07_28),
                "https://resource.example/" + version, 0, true);
        var tool = new McpRuntimeTool("tool-" + version, "billing", "lookup", version,
                "LOCAL_OPERATION", "op-tool", "HTTP", null, "{\"type\":\"object\"}",
                "{\"type\":\"object\"}", Map.of(), Set.of(), "LOW", true, true);
        var resource = new McpRuntimeResource("resource-" + version, "billing", "report",
                "egon://billing/report", version, "text/plain", "LOCAL_OPERATION", "op-resource",
                null, Map.of(), Set.of(), 4096, true);
        var prompt = new McpRuntimePrompt("prompt-" + version, "billing", "review", version,
                "LOCAL_OPERATION", null, "op-prompt", null, List.of(), Set.of(), true);
        var mcp = new McpRuleContent(List.of(server), List.of(tool), List.of(resource), List.of(),
                List.of(prompt), List.of(), List.of(), List.of(), List.of());
        var content = new GatewayRuleContent("group", "billing", "local", "default",
                List.of(operation("op-tool", version, "tool"), operation("op-resource", version, "resource"),
                        operation("op-prompt", version, "prompt")), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), mcp);
        var snapshot = new GatewayRuleSnapshot("v1", "release-" + version, Instant.now(),
                "content-sha", "artifact-sha", content);
        return new McpGatewayCompiledRulesDTO(snapshot, Set.of(), Map.of(), Map.of(),
                new McpRuleCompiler().compile(mcp, Set.of("op-tool", "op-resource", "op-prompt")));
    }

    private GatewayRuntimeOperation operation(String id, String version, String path) {
        return new GatewayRuntimeOperation(id, id, GatewayProtocol.HTTP, "GET /" + version + "/" + path,
                "{}", "{}", true, new GatewayProviderServiceRef("biz", "app", "local", "default",
                GatewayProtocol.HTTP, "provider", "default", "v1", "http"), "TRANSPARENT",
                Set.of(), Map.of("idempotent", "true"), false);
    }

    private Map<String, Object> identity() {
        return Map.ofEntries(Map.entry("callerId", "user-1"), Map.entry("tenantId", "tenant-1"),
                Map.entry("tianquan-shoubing.issuer", "https://idp.example"),
                Map.entry("tianquan-shoubing.client-id", "client-1"),
                Map.entry("tianquan-shoubing.token-id", "token-1"),
                Map.entry("tianquan-shoubing.resource-uri", "https://resource.example/old"),
                Map.entry("tianquan-shoubing.issued-at", "2026-08-02T04:59:30Z"),
                Map.entry("tianquan-shoubing.expires-at", "2026-08-02T05:05:00Z"));
    }
}
