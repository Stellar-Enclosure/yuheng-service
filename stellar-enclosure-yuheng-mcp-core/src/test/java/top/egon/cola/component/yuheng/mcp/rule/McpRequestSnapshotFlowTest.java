package top.egon.cola.component.yuheng.mcp.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpJsonRpcRequest;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimePrompt;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeResource;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort;
import top.egon.cola.component.yuheng.core.mcp.security.McpAuthorizationPort;
import top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvocation;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityGate;
import top.egon.cola.component.yuheng.mcp.prompt.domain.StrictPromptTemplate;
import top.egon.cola.component.yuheng.mcp.prompt.service.McpPromptsGetHandler;
import top.egon.cola.component.yuheng.mcp.prompt.service.McpPromptsListHandler;
import top.egon.cola.component.yuheng.mcp.prompt.service.StaticPromptDriver;
import top.egon.cola.component.yuheng.mcp.resource.adapter.StaticTextResourceDriver;
import top.egon.cola.component.yuheng.mcp.resource.domain.McpResourceUriValidator;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourceCatalog;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourcesListHandler;
import top.egon.cola.component.yuheng.mcp.resource.service.McpResourcesReadHandler;
import top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules;
import top.egon.cola.component.yuheng.mcp.rule.domain.McpRequestRules;
import top.egon.cola.component.yuheng.mcp.rule.service.McpRuleCompiler;
import top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext;
import top.egon.cola.component.yuheng.mcp.tool.service.McpResultBinder;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolCatalog;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolsCallHandler;
import top.egon.cola.component.yuheng.mcp.tool.service.McpToolsListHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRequestSnapshotFlowTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void boundToolResourceAndPromptRemainOnTheRequestVersion() {
        CompiledMcpRules old = rules("old");
        CompiledMcpRules newer = rules("new");
        AtomicReference<CompiledMcpRules> current = new AtomicReference<>(newer);
        McpRuntimeServer server = old.server("billing").orElseThrow();
        Map<String, Object> attributes = McpRequestRules.bind(identity(), old);
        McpRequestContext context = new McpRequestContext(server,
                McpProtocolDialect.STABLE_2025_11_25, "session", attributes);
        assertFalse(McpRequestRules.withoutRules(attributes).toString().contains("McpRequestRules"));

        McpSecurityGate security = new McpSecurityGate(
                ignored -> Mono.just(McpAuthorizationPort.Decision.allowed(1L, 1L, 1L)),
                ignored -> Mono.just(McpApprovalPort.Result.APPROVED), mapper);
        var tools = new McpToolCatalog(current::get);
        AtomicReference<GatewayOperationInvocation> operation = new AtomicReference<>();
        var call = new McpToolsCallHandler(tools, new McpResultBinder(mapper), invocation -> {
            operation.set(invocation);
            return Mono.just(new GatewayInvocationResult(200, Map.of("content-type", List.of("application/json")),
                    "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8)));
        }, security, mapper, null, current::get);
        var toolList = Mono.from(new McpToolsListHandler(tools, mapper).handle(
                request("tools/list", Map.of()), context)).block();
        assertTrue(toolList.result().toString().contains("old-description"));
        Mono.from(call.handle(request("tools/call", Map.of("name", "lookup", "arguments", Map.of())),
                context)).block();
        assertEquals("operation-old", operation.get().operationId());

        var resources = new McpResourceCatalog(current::get, new McpResourceUriValidator());
        var resourceList = Mono.from(new McpResourcesListHandler(resources, security).handle(
                request("resources/list", Map.of()), context)).block();
        assertTrue(resourceList.result().toString().contains("old-description"));
        var resource = Mono.from(new McpResourcesReadHandler(resources,
                List.of(new StaticTextResourceDriver()), security).handle(
                request("resources/read", Map.of("uri", "egon://billing/report")), context)).block();
        assertTrue(resource.result().toString().contains("content-old"));
        assertFalse(resource.result().toString().contains("content-new"));

        var promptList = Mono.from(new McpPromptsListHandler(current::get, security).handle(
                request("prompts/list", Map.of()), context)).block();
        assertTrue(promptList.result().toString().contains("old-description"));
        var prompt = Mono.from(new McpPromptsGetHandler(current::get,
                List.of(new StaticPromptDriver(new StrictPromptTemplate())), security).handle(
                request("prompts/get", Map.of("name", "review", "arguments", Map.of())), context)).block();
        assertTrue(prompt.result().toString().contains("prompt-old"));
        assertFalse(prompt.result().toString().contains("prompt-new"));
        var dictionary = new top.egon.cola.component.yuheng.mcp.completion.service.DictionaryCompletionProvider(
                Map.of(new top.egon.cola.component.yuheng.mcp.completion.service.DictionaryCompletionProvider.Key(
                        "ref/prompt", "review", "subject"), List.of("old-value")));
        var completion = Mono.from(new top.egon.cola.component.yuheng.mcp.completion.service.McpCompletionHandler(
                current::get, resources, List.of(dictionary), security).handle(request("completion/complete", Map.of(
                "ref", Map.of("type", "ref/prompt", "name", "review"),
                "argument", Map.of("name", "subject", "value", "old"))), context)).block();
        assertTrue(completion.result().toString().contains("old-value"));
    }

    private CompiledMcpRules rules(String version) {
        var server = new McpRuntimeServer("server-" + version, "billing", "Billing " + version,
                null, null, Set.of(McpProtocolDialect.STABLE_2025_11_25),
                "https://resource.example/" + version, 0, true);
        var tool = new McpRuntimeTool("tool-" + version, "billing", "lookup", version + "-description",
                "LOCAL_OPERATION", "operation-" + version, "HTTP", null,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", Map.of(), Set.of(), "LOW", true, true);
        var resource = new McpRuntimeResource("resource-" + version, "billing", "report",
                "egon://billing/report", version + "-description", "text/plain",
                StaticTextResourceDriver.DRIVER_TYPE, null, null, Map.of("text", "content-" + version),
                Set.of(), 1024, true);
        var prompt = new McpRuntimePrompt("prompt-" + version, "billing", "review",
                version + "-description", "STRICT_TEMPLATE", "prompt-" + version,
                null, null, List.of("old".equals(version) ? "subject" : "newArgument"), Set.of(), true);
        return new McpRuleCompiler().compile(new McpRuleContent(List.of(server), List.of(tool),
                List.of(resource), List.of(), List.of(prompt), List.of(), List.of(), List.of(), List.of()),
                Set.of("operation-" + version));
    }

    private McpJsonRpcRequest request(String method, Map<String, Object> params) {
        return new McpJsonRpcRequest("2.0", 1, method, params, Map.of());
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
