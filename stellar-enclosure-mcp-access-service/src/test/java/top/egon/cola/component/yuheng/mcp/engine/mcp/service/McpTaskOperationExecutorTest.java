package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.core.operation.GatewayInvocationResult;
import top.egon.cola.component.yuheng.core.operation.GatewayOperationInvocation;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingPinVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.SelectionVO;
import top.egon.cola.component.yuheng.mcp.task.domain.McpTask;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskBindingVO;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskExecutor;

/** The worker passes the exact durable pin to the real authority/send boundary, never an activation or URI resolver. */
class McpTaskOperationExecutorTest {
    private final McpBoundOperationInvoker invoker = mock(McpBoundOperationInvoker.class);
    private final McpTaskOperationExecutor executor = new McpTaskOperationExecutor(invoker, new ObjectMapper());

    @Test
    void unpinnedHistoricalExecutionFailsBeforeAnyBusinessInvocation() {
        var result = Mono.from(executor.execute(task(Map.of("operationId", "operation-1")))).block();
        assertThat(result.type()).isEqualTo(McpTaskExecutor.Type.FAILED);
        assertThat(result.payload()).containsEntry("code", "MCP_TASK_BINDING_REQUIRED");
        verifyNoInteractions(invoker);
    }

    @Test
    void malformedOrCrossServerPinNeverReachesTheInvoker() {
        assertThat(
            Mono.from(executor.execute(task(Map.of("executionBinding", Map.of("bad", "pin"))))).block().payload())
            .containsEntry("code", "MCP_TASK_BINDING_INVALID");
        McpTaskBindingVO foreign =
            new McpTaskBindingVO(new SelectionVO(9001L, "another-server", "a".repeat(64)), pin());
        assertThat(Mono.from(executor.execute(
                                 task(Map.of("executionBinding", foreign.toPayload(), "operationId", "operation-1"))))
                       .block()
                       .payload())
            .containsEntry("code", "MCP_TASK_BINDING_INVALID");
        verifyNoInteractions(invoker);
    }

    @Test
    void workerPreservesTheExactV1PinAndNeverForwardsOrManufacturesUserCredentials() {
        when(invoker.invokeBound(eq(binding().selection()), eq(pin()), any())).thenReturn(Mono.just(success()));
        var outcome = Mono.from(executor.execute(task(input()))).block();
        assertThat(outcome.type()).isEqualTo(McpTaskExecutor.Type.COMPLETED);
        assertThat(outcome.payload()).containsEntry("value", "done");
        ArgumentCaptor<GatewayOperationInvocation> sent = ArgumentCaptor.forClass(GatewayOperationInvocation.class);
        verify(invoker).invokeBound(eq(binding().selection()), eq(pin()), sent.capture());
        assertThat(sent.getValue().originalBearerToken()).isNull();
        assertThat(sent.getValue().callerId()).isNull();
        assertThat(sent.getValue().call().operationId()).isEqualTo("operation-1");
        verify(invoker, never()).invoke(any());
    }

    @Test
    void authorityFailureIsNotRetriedThroughCurrentVersionOrGenericInvocation() {
        when(invoker.invokeBound(eq(binding().selection()), eq(pin()), any()))
            .thenReturn(Mono.error(new IllegalStateException("MCP_BINDING_PIN_UNAVAILABLE")));
        assertThatThrownBy(() -> Mono.from(executor.execute(task(input()))).block())
            .hasMessage("MCP_BINDING_PIN_UNAVAILABLE");
        verify(invoker).invokeBound(eq(binding().selection()), eq(pin()), any());
        verify(invoker, never()).invoke(any());
    }

    @Test
    void continuationInputChangesOnlyTheOperationBodyWhileThePinRemainsV1() {
        Map<String, Object> input = input();
        input.put("body", Map.of("amount", 42));
        input.put("inputResponse", Map.of("approved", true));
        when(invoker.invokeBound(eq(binding().selection()), eq(pin()), any())).thenReturn(Mono.just(success()));
        Mono.from(executor.execute(task(input))).block();
        ArgumentCaptor<GatewayOperationInvocation> sent = ArgumentCaptor.forClass(GatewayOperationInvocation.class);
        verify(invoker).invokeBound(eq(binding().selection()), eq(pin()), sent.capture());
        assertThat(sent.getValue().call().body()).isEqualTo(Map.of("amount", 42, "input", Map.of("approved", true)));
    }

    @Test
    void cancellingTheWorkerSubscriptionCancelsTheBoundInvocation() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(invoker.invokeBound(any(), any(), any()))
            .thenReturn(Mono.<GatewayInvocationResult>never().doOnCancel(() -> cancelled.set(true)));
        var pending = Mono.from(executor.execute(task(input()))).subscribe();
        assertThat(cancelled).isFalse();
        pending.dispose();
        assertThat(cancelled).isTrue();
    }

    private static GatewayInvocationResult success() {
        return new GatewayInvocationResult(200, Map.of(), "{\"value\":\"done\"}".getBytes(StandardCharsets.UTF_8));
    }

    private static BindingPinVO pin() {
        return new BindingPinVO("binding-1", 1L, "11", "22", 1L, "b".repeat(64), "33", "c".repeat(64), "d".repeat(64));
    }

    private static McpTaskBindingVO binding() {
        return new McpTaskBindingVO(new SelectionVO(9001L, "billing", "a".repeat(64)), pin());
    }

    private static Map<String, Object> input() {
        return new LinkedHashMap<>(Map.of("executionBinding", binding().toPayload(), "operationId", "operation-1"));
    }

    private static McpTask task(Map<String, Object> input) {
        Instant created = Instant.parse("2026-11-19T12:00:00Z");
        return new McpTask("task-1", "fingerprint", "user-42", "tenant-a", "client-a", "billing", "pay", "a".repeat(64),
            McpTask.State.WORKING, input, null, null, "worker", created.plusSeconds(30), created.plusSeconds(60),
            created.plusSeconds(300), 1, 3, 7L, created, created);
    }
}
