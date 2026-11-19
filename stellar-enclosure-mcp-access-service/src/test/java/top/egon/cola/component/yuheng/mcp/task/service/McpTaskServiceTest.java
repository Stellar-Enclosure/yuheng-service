package top.egon.cola.component.yuheng.mcp.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingPinVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.SelectionVO;
import top.egon.cola.component.yuheng.mcp.task.domain.McpTask;

/**
 * Exercises immutable JSONB pins and the existing owner/revision/worker lease contract without replacing production
 * stores.
 */
class McpTaskServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final McpTaskService.Owner OWNER = new McpTaskService.Owner("subject", "tenant-a", "client-a");
    private static final String TOOL_ID = "a".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private final McpTaskStore store = mock(McpTaskStore.class);
    private final McpTaskService service =
        new McpTaskService(store, json, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(90));

    @Test
    void pinnedExecutionHasAnExplicitVersionedEnvelope() throws Exception {
        assertThat(McpTaskBindingVO.class).isRecord();
        Map<String, Object> encoded = Map.of(McpTaskBindingVO.PAYLOAD_KEY, binding().toPayload());
        Map<String, Object> roundTrip = json.readValue(json.writeValueAsBytes(encoded), new TypeReference<>() {});
        assertThat(McpTaskBindingVO.require(roundTrip)).isEqualTo(binding());
        assertThat(json.writeValueAsString(encoded))
            .contains("\"ownerTenantId\":\"9001\"", "\"bindingVersion\":\"1\"", "\"versionNo\":\"1\"");
    }

    @Test
    void envelopeRejectsMissingPinNumericTypeDriftAndUnknownAuthorityFields() {
        assertThatThrownBy(() -> McpTaskBindingVO.require(Map.of())).hasMessage("MCP_TASK_BINDING_REQUIRED");
        Map<String, Object> root = new LinkedHashMap<>(binding().toPayload());
        Map<String, Object> pin = new LinkedHashMap<>((Map<String, Object>) root.get("pin"));
        pin.put("bindingVersion", 1);
        root.put("pin", pin);
        assertThatThrownBy(() -> McpTaskBindingVO.require(Map.of(McpTaskBindingVO.PAYLOAD_KEY, root)))
            .hasMessage("MCP_TASK_BINDING_INVALID");
        Map<String, Object> invalid = new LinkedHashMap<>(binding().toPayload());
        invalid.put("uri", "http://unapproved.invalid");
        assertThatThrownBy(() -> McpTaskBindingVO.require(Map.of(McpTaskBindingVO.PAYLOAD_KEY, invalid)))
            .hasMessage("MCP_TASK_BINDING_INVALID");
    }

    @Test
    void acceptedNestedBindingCannotBeMutatedByTheCallerAfterCreation() {
        Map<String, Object> pin = new LinkedHashMap<>(Map.of("bindingVersion", "1"));
        McpTask task = task(Map.of("executionBinding", pin), McpTask.State.WORKING);
        pin.put("bindingVersion", "2");
        assertThat(((Map<?, ?>) task.inputPayload().get("executionBinding")).get("bindingVersion")).isEqualTo("1");
        assertThatThrownBy(
            () -> ((Map<String, Object>) task.inputPayload().get("executionBinding")).put("bindingVersion", "3"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotPreservesNestedJsonNullListsAndDetachesMutableJsonNodes() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nullable", null);
        List<Object> items = new ArrayList<>();
        items.add(null);
        var node = json.createObjectNode().put("version", "one");
        body.put("items", items);
        body.put("node", node);
        McpTask task = task(Map.of("body", body), McpTask.State.WORKING);
        node.put("version", "two");
        items.add("later");
        Map<?, ?> saved = (Map<?, ?>) task.inputPayload().get("body");
        assertThat(saved.containsKey("nullable")).isTrue();
        assertThat(saved.get("nullable")).isNull();
        assertThat((List<?>) saved.get("items")).hasSize(1);
        assertThat(((com.fasterxml.jackson.databind.JsonNode) saved.get("node")).path("version").asText())
            .isEqualTo("one");
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved.get("node")).put("version", "changed-by-reader");
        assertThat(((com.fasterxml.jackson.databind.JsonNode) ((Map<?, ?>) task.inputPayload().get("body"))
                .get("node")).path("version").asText()).isEqualTo("one");
        assertThat(task.resultPayload()).isNull();
        assertThat(task.errorPayload()).isNull();
    }

    @Test
    void newTasksRequirePinsAndNeverUseTheHistoricalCurrentVersionFallback() {
        assertThatThrownBy(() -> request(Map.of("operationId", "operation-1"))).hasMessage("MCP_TASK_BINDING_REQUIRED");
        verify(store, never()).create(any());
    }

    @Test
    void creatingTaskFreezesTheEntireAcceptedInputBeforeLazyStoreSubscription() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("value", "original"));
        Map<String, Object> input = input();
        input.put("body", body);
        when(store.create(any())).thenReturn(Mono.empty());
        var command = request(input);
        body.put("value", "changed-after-command");
        var accepted = service.create(command, OWNER);
        body.put("value", "changed");
        McpTask created = Mono.from(accepted).block();
        assertThat(McpTaskBindingVO.require(created.inputPayload())).isEqualTo(binding());
        assertThat(((Map<?, ?>) created.inputPayload().get("body")).get("value")).isEqualTo("original");
        assertThat(created.principalFingerprint()).isEqualTo(OWNER.fingerprint());
    }

    @Test
    void inputContinuationCannotOverrideRootBindingAndKeepsRevisionCas() {
        Map<String, Object> original = input();
        original.put("inputRequestKey", "question-1");
        McpTask current = task(original, McpTask.State.INPUT_REQUIRED);
        when(store.find("task-1")).thenReturn(Mono.just(current));
        when(store.transition(any())).thenReturn(Mono.just(true));
        Mono.from(service.provideInput("task-1", "question-1",
                      Map.of("executionBinding", Map.of("malicious", "replacement"), "answer", "yes"), OWNER))
            .block();
        ArgumentCaptor<McpTaskStore.Transition> transition = ArgumentCaptor.forClass(McpTaskStore.Transition.class);
        verify(store).transition(transition.capture());
        assertThat(McpTaskBindingVO.require(transition.getValue().inputPayload())).isEqualTo(binding());
        assertThat(transition.getValue().expectedRevision()).isEqualTo(7);
        assertThat(transition.getValue().expectedState()).isEqualTo(McpTask.State.INPUT_REQUIRED);
        assertThat(transition.getValue().targetState()).isEqualTo(McpTask.State.WORKING);
    }

    @Test
    void oversizedInputContinuationDoesNotReachTransition() {
        Map<String, Object> original = input();
        original.put("inputRequestKey", "question-1");
        when(store.find("task-1")).thenReturn(Mono.just(task(original, McpTask.State.INPUT_REQUIRED)));
        assertThatThrownBy(
            ()
                -> Mono
                    .from(service.provideInput("task-1", "question-1", Map.of("answer", "x".repeat(1_048_577)), OWNER))
                    .block())
            .hasMessageContaining("too large");
        verify(store, never()).transition(any());
    }

    @Test
    void archivedUnpinnedTaskCanBeReadAndCancelledButCannotContinueOrExecute() {
        McpTask archived = task(Map.of("inputRequestKey", "question-1"), McpTask.State.INPUT_REQUIRED);
        when(store.find("task-1")).thenReturn(Mono.just(archived));
        assertThat(Mono.from(service.get("task-1", OWNER)).block()).isEqualTo(archived);
        when(store.cancel("task-1", McpTask.State.INPUT_REQUIRED, 7L, NOW)).thenReturn(Mono.just(true));
        assertThat(Mono.from(service.cancel("task-1", OWNER)).block()).isEqualTo(archived);
        assertThatThrownBy(() -> Mono.from(service.provideInput("task-1", "question-1", Map.of(), OWNER)).block())
            .hasMessage("MCP_TASK_BINDING_REQUIRED");
        verify(store, never()).transition(any());
    }

    @Test
    void leasedUnpinnedTaskFailsWithoutCallingTheBusinessExecutor() {
        McpTask old = task(Map.of("operationId", "operation-1"), McpTask.State.WORKING);
        when(store.leaseNext(anyString(), any(), any())).thenReturn(Mono.just(old));
        when(store.transition(any())).thenReturn(Mono.just(true));
        when(store.find("task-1")).thenReturn(Mono.just(old));
        McpTaskExecutor executor = mock(McpTaskExecutor.class);
        Mono.from(service.executeNext("worker", executor)).block();
        verify(executor, never()).execute(any());
        ArgumentCaptor<McpTaskStore.Transition> transition = ArgumentCaptor.forClass(McpTaskStore.Transition.class);
        verify(store).transition(transition.capture());
        assertThat(transition.getValue().targetState()).isEqualTo(McpTask.State.FAILED);
        assertThat(transition.getValue().expectedRevision()).isEqualTo(7);
        assertThat(transition.getValue().expectedWorkerOwner()).isEqualTo("worker");
    }

    @Test
    void pinnedExecutionRetainsHeartbeatAndOwnerFencedCompletion() {
        McpTask current = task(input(), McpTask.State.WORKING);
        when(store.leaseNext(anyString(), any(), any())).thenReturn(Mono.just(current));
        when(store.renewLease(eq("task-1"), eq("worker"), any(), any())).thenReturn(Mono.just(true));
        when(store.transition(any())).thenReturn(Mono.just(true));
        when(store.find("task-1")).thenReturn(Mono.just(current));
        Mono.from(service.executeNext("worker",
                      task
                      -> Mono.delay(Duration.ofMillis(120))
                          .map(ignored -> McpTaskExecutor.Outcome.completed(Map.of("done", true)))))
            .block(Duration.ofSeconds(5));
        verify(store, atLeastOnce()).renewLease(eq("task-1"), eq("worker"), any(), any());
        ArgumentCaptor<McpTaskStore.Transition> transition = ArgumentCaptor.forClass(McpTaskStore.Transition.class);
        verify(store).transition(transition.capture());
        assertThat(transition.getValue().expectedWorkerOwner()).isEqualTo("worker");
        assertThat(transition.getValue().expectedRevision()).isEqualTo(7);
        assertThat(McpTaskBindingVO.require(transition.getValue().inputPayload())).isEqualTo(binding());
    }

    private static McpTaskService.CreateRequest request(Map<String, Object> input) {
        return new McpTaskService.CreateRequest(
            "billing", "pay", "a".repeat(64), input, Duration.ofSeconds(60), Duration.ofSeconds(300), 3);
    }

    private static Map<String, Object> input() {
        return new LinkedHashMap<>(
            Map.of(McpTaskBindingVO.PAYLOAD_KEY, binding().toPayload(), "operationId", "operation-1"));
    }

    private static McpTaskBindingVO binding() {
        return new McpTaskBindingVO(new SelectionVO(9001L, "billing", TOOL_ID),
            new BindingPinVO("binding-1", 1L, "11", "22", 1L, "b".repeat(64), "33", "c".repeat(64), "d".repeat(64)));
    }

    private static McpTask task(Map<String, Object> input, McpTask.State state) {
        return new McpTask("task-1", OWNER.fingerprint(), OWNER.subjectId(), OWNER.tenantId(), OWNER.clientId(),
            "billing", "pay", "a".repeat(64), state, input, null, null, "worker", NOW.plusSeconds(30),
            NOW.plusSeconds(60), NOW.plusSeconds(300), 1, 3, 7, NOW, NOW);
    }
}
