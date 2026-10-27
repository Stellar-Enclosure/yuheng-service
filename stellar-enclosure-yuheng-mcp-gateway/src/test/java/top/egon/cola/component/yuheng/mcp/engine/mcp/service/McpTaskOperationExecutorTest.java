package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvocation;
import top.egon.cola.component.yuheng.mcp.task.domain.McpTask;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class McpTaskOperationExecutorTest {

    @Test
    @SuppressWarnings("unchecked")
    void workerPinsServerResourceAndOperationForOneExecution() {
        var activation = (top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier<
                top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO>)
                mock(top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier.class);
        var lease = (top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier.Lease<
                top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO>)
                mock(top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier.Lease.class);
        var snapshot = mock(top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO.class);
        var rules = mock(top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules.class);
        var server = new top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer(
                "server", "local", "Local", null, null,
                java.util.Set.of(top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect.STABLE_2025_11_25),
                "https://resource.example/old", 0, true);
        when(activation.acquire()).thenReturn(lease);
        when(lease.rules()).thenReturn(snapshot);
        when(snapshot.mcpRules()).thenReturn(rules);
        when(rules.server("local")).thenReturn(java.util.Optional.of(server));
        var resource = new AtomicReference<URI>();
        var seen = new AtomicReference<top.egon.cola.component.yuheng.runtime.rule.domain.GatewayCompiledRulesDTO>();
        McpTaskOperationExecutor executor = new McpTaskOperationExecutor(
                invocation -> Mono.deferContextual(context -> {
                    seen.set(context.get(top.egon.cola.component.yuheng.runtime.rule.domain.GatewayCompiledRulesDTO.class));
                    return Mono.just(new GatewayInvocationResult(200, Map.of(), "{}".getBytes(StandardCharsets.UTF_8)));
                }), new ObjectMapper(), ignored -> { throw new AssertionError("active resolver must not be read"); },
                (tenant, uri) -> { resource.set(uri); return "service-token"; }, activation);

        assertThat(Mono.from(executor.execute(task())).block()).isNotNull();
        assertThat(resource).hasValue(URI.create("https://resource.example/old"));
        assertThat(seen).hasValue(snapshot);
        verify(lease).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancellingWorkerExecutionReleasesItsSnapshot() {
        var activation = (top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier<
                top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO>)
                mock(top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier.class);
        var lease = (top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier.Lease<
                top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO>)
                mock(top.egon.cola.component.yuheng.runtime.rule.service.GatewayRuleActivationApplier.Lease.class);
        var snapshot = mock(top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO.class);
        var rules = mock(top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules.class);
        var server = new top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeServer(
                "server", "local", "Local", null, null,
                java.util.Set.of(top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect.STABLE_2025_11_25),
                "https://resource.example/old", 0, true);
        when(activation.acquire()).thenReturn(lease);
        when(lease.rules()).thenReturn(snapshot);
        when(snapshot.mcpRules()).thenReturn(rules);
        when(rules.server("local")).thenReturn(java.util.Optional.of(server));
        var executor = new McpTaskOperationExecutor(ignored -> Mono.never(), new ObjectMapper(),
                ignored -> URI.create("https://unused.example"), (tenant, uri) -> "service-token", activation);

        var subscription = Mono.from(executor.execute(task())).subscribe();
        verify(lease, never()).close();
        subscription.dispose();
        verify(lease).close();
    }

    @Test
    void exchangesTheDurableUserTaskForAnExactResourceServiceToken() {
        AtomicReference<GatewayOperationInvocation> captured =
                new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<URI> resource = new AtomicReference<>();
        URI providerResource = URI.create(
                "https://api.egon.internal/identity/mcp-provider"
        );
        McpTaskOperationExecutor executor = new McpTaskOperationExecutor(
                invocation -> {
                    captured.set(invocation);
                    return Mono.just(new GatewayInvocationResult(
                            200,
                            Map.of(),
                            "{\"value\":\"done\"}".getBytes(
                                    StandardCharsets.UTF_8)
                    ));
                },
                new ObjectMapper(),
                serverCode -> providerResource,
                (tenantId, resourceUri) -> {
                    tenant.set(tenantId);
                    resource.set(resourceUri);
                    return "service-token";
                }
        );

        var outcome = Mono.from(executor.execute(task())).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.payload()).containsEntry("value", "done");
        assertThat(tenant).hasValue("tenant-b");
        assertThat(resource).hasValue(providerResource);
        assertThat(captured.get().originalBearerToken())
                .isEqualTo("Bearer service-token");
        assertThat(captured.get().callerId()).isEqualTo("user-42");
        assertThat(captured.get().call().body())
                .isEqualTo(Map.of("value", "task"));
    }

    private McpTask task() {
        Instant created = Instant.parse("2026-08-11T12:00:00Z");
        return new McpTask(
                "task-1",
                "fingerprint",
                "user-42",
                "tenant-b",
                "mock-backend",
                "local",
                "local_echo_task",
                "a".repeat(64),
                McpTask.State.WORKING,
                Map.of(
                        "operationId", "local-echo-task-operation",
                        "body", Map.of("value", "task")
                ),
                null,
                null,
                "engine-a",
                created.plusSeconds(30),
                created.plusSeconds(60),
                created.plusSeconds(300),
                1,
                3,
                1,
                created,
                created
        );
    }
}
