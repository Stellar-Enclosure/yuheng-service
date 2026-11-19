package top.egon.cola.component.yuheng.mcp.tool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpJsonRpcRequest;
import top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTaskPolicy;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvoker;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityGate;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingContextVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingPinVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.OperationVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.SelectionVO;
import top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules;
import top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext;
import top.egon.cola.component.yuheng.mcp.task.domain.McpTask;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskBindingVO;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskService;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskStore;

/** Unit fixtures replace the trusted resolver only; they never supply an externally selectable production binding. */
class McpToolsCallHandlerTaskTest {
    private static final String TOOL_ID = "a".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private final McpToolCatalog catalog = mock(McpToolCatalog.class);
    private final McpSecurityGate security = mock(McpSecurityGate.class);
    private final GatewayOperationInvoker generic = mock(GatewayOperationInvoker.class);
    private final McpTaskStore store = mock(McpTaskStore.class);
    private final CompiledMcpRules rules = mock(CompiledMcpRules.class);
    private final McpRuntimeTool tool = new McpRuntimeTool(TOOL_ID, "billing", "pay", "Pay", "LOCAL_OPERATION",
        "operation-1", "HTTP", null, "{}", "{}", Map.of(), Set.of("invoice:pay"), "LOW", true, true);

    @Test
    void acceptedTaskPersistsTheExactTrustedPinWithoutRequestBearer() {
        prepare();
        BindingContextVO admitted = admitted();
        var resolver = mock(McpToolsCallHandler.TaskBindingResolver.class);
        when(resolver.require(eq(tool), any())).thenReturn(admitted);
        Mono.from(handler(resolver).handle(request(), context())).block();
        ArgumentCaptor<McpTask> captured = ArgumentCaptor.forClass(McpTask.class);
        verify(store).create(captured.capture());
        assertThat(McpTaskBindingVO.require(captured.getValue().inputPayload()))
            .isEqualTo(new McpTaskBindingVO(admitted.selection(), admitted.pin()));
        assertThat(captured.getValue().inputPayload())
            .containsEntry("operationId", "operation-1")
            .containsEntry("body", Map.of("amount", 42));
        assertThat(captured.getValue().inputPayload().toString())
            .doesNotContain("secret-mcp-token", "originalBearerToken");
        assertThat(McpTaskBindingVO.require(captured.getValue().inputPayload()).selection().ownerTenantId())
            .isEqualTo(9001L);
        verify(resolver).require(eq(tool), any());
        verify(generic, never()).invoke(any());
    }

    @Test
    void durableTaskWithoutTheTrustedResolverCannotFallThroughToSynchronousInvocation() {
        prepare();
        assertThatThrownBy(() -> Mono.from(handler(null).handle(request(), context())).block())
            .hasMessageContaining("durable task binding is unavailable");
        verify(store, never()).create(any());
        verify(generic, never()).invoke(any());
    }

    @Test
    void currentAuthorityRejectionLeavesNoTaskAndNoBusinessInvocation() {
        prepare();
        assertThatThrownBy(()
                               -> Mono
                                   .from(handler((tool, context) -> {
                                       throw new IllegalStateException("MCP_SI_UNAVAILABLE");
                                   }).handle(request(), context()))
                                   .block())
            .hasMessage("MCP_SI_UNAVAILABLE");
        verify(store, never()).create(any());
        verify(generic, never()).invoke(any());
    }

    @Test
    void selectedToolMismatchIsNeverStoredAsAnAuthorizedTask() {
        prepare();
        BindingContextVO wrong = admitted();
        when(wrong.selection()).thenReturn(new SelectionVO(9001L, "billing", "e".repeat(64)));
        assertThatThrownBy(() -> Mono.from(handler((tool, context) -> wrong).handle(request(), context())).block())
            .hasMessageContaining("binding does not match its tool");
        verify(store, never()).create(any());
        verify(generic, never()).invoke(any());
    }

    @Test
    void synchronousProductionCallAlsoCarriesTheExactTypedCatalogBinding() {
        prepare();
        when(rules.taskPoliciesByQualifiedTool()).thenReturn(Map.of());
        BindingContextVO admitted = admitted();
        java.util.concurrent.atomic.AtomicReference<BindingContextVO> seen =
            new java.util.concurrent.atomic.AtomicReference<>();
        when(generic.invoke(any())).thenReturn(Mono.deferContextual(reactor -> {
            seen.set(reactor.get(BindingContextVO.class));
            return Mono.just(new top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult(
                200, Map.of(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }));
        Mono.from(handler((tool, context) -> admitted).handle(request(), context())).block();
        assertThat(seen).hasValue(admitted);
        var sent =
            ArgumentCaptor.forClass(top.egon.cola.component.yuheng.core.operation.GatewayOperationInvocation.class);
        verify(generic).invoke(sent.capture());
        assertThat(sent.getValue().originalBearerToken()).isNull();
        assertThat(sent.getValue().callerId()).isNull();
        verify(store, never()).create(any());
    }

    private void prepare() {
        when(catalog.tool(eq("billing"), eq("pay"), any())).thenReturn(Optional.of(tool));
        when(security.authorizeToolCall(eq(tool), any(), any(), any())).thenReturn(Mono.empty());
        when(rules.taskPoliciesByQualifiedTool())
            .thenReturn(Map.of(CompiledMcpRules.qualified("billing", "pay"),
                new McpRuntimeTaskPolicy("task-policy", "billing", "pay", true, true, 60, 300, 3, true)));
        when(store.create(any())).thenReturn(Mono.empty());
    }

    private McpToolsCallHandler handler(McpToolsCallHandler.TaskBindingResolver resolver) {
        return new McpToolsCallHandler(catalog, new McpResultBinder(json), generic, security, json,
            new McpTaskService(store, json, Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30)),
            () -> rules, null, resolver);
    }

    private BindingContextVO admitted() {
        BindingContextVO binding = mock(BindingContextVO.class);
        when(binding.selection()).thenReturn(new SelectionVO(9001L, "billing", TOOL_ID));
        when(binding.pin())
            .thenReturn(new BindingPinVO(
                "binding-1", 1L, "11", "22", 1L, "b".repeat(64), "33", "c".repeat(64), "d".repeat(64)));
        when(binding.tool()).thenReturn(tool);
        OperationVO operation = mock(OperationVO.class);
        when(operation.operationId()).thenReturn("operation-1");
        when(binding.operation()).thenReturn(operation);
        return binding;
    }

    private static McpJsonRpcRequest request() {
        return new McpJsonRpcRequest("2.0", 1L, "tools/call",
            Map.of("name", "pay", "arguments", Map.of("body", Map.of("amount", 42))), Map.of());
    }

    private static McpRequestContext context() {
        McpRuntimeServer server = new McpRuntimeServer("server-id", "billing", "Billing", null, null,
            Set.of(McpProtocolDialect.STABLE_2025_11_25), "https://resource.example/mcp", 0, true);
        return new McpRequestContext(server, McpProtocolDialect.STABLE_2025_11_25, "session-1",
            Map.ofEntries(Map.entry("originalBearerToken", "Bearer secret-mcp-token"), Map.entry("callerId", "subject"),
                Map.entry("tenantId", "tenant-a"), Map.entry("tianquan-shoubing.issuer", "https://issuer.example"),
                Map.entry("tianquan-shoubing.client-id", "client-a"), Map.entry("tianquan-shoubing.token-id", "jti-1"),
                Map.entry("tianquan-shoubing.resource-uri", "https://resource.example/mcp"),
                Map.entry("tianquan-shoubing.issued-at", "2026-11-19T11:59:00Z"),
                Map.entry("tianquan-shoubing.expires-at", "2026-11-19T12:05:00Z")));
    }
}
