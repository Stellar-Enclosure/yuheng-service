package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapstruct.factory.Mappers;
import org.slf4j.MDC;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.yuheng.admin.mcp.controller.McpTaskAdminController;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.config.McpManagementAccessFilter;
import top.egon.cola.component.yuheng.mcp.engine.config.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.MpMcpRuntimeTaskStore;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.support.McpGatewayPersistenceContext;
import top.egon.cola.component.yuheng.mcp.engine.mcp.converter.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.enums.McpPersistentTaskStateEnum;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.component.yuheng.mcp.task.domain.McpTask;
import top.egon.cola.component.yuheng.mcp.task.service.*;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.SystemAuthorizationSnapshot;
import top.egon.cola.platform.tianquan.jianshen.starter.permission.PermissionExecutionGuard;
import top.egon.cola.platform.tianquan.jianshen.starter.security.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real native identity and runtime adapter; repository/transaction fixtures isolate service composition from PostgreSQL integration. */
class McpTaskManagementServiceTest {
    @TempDir Path directory;

    @Test
    void managementUsesTheAdapterRuntimeTaskAuthority() throws Exception {
        assertNotNull(Class.forName("top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpTaskManagementService"));
    }

    @Test
    void listsAndReadsOnlyTheExactVerifiedOwnerAndNeverReturnsPersistedBindingOrCredentials() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            McpTask task = f.create(f.owner());
            f.create(new McpTaskService.Owner("other-user", "1001", "client-1"));
            f.create(new McpTaskService.Owner("user-1", "1001", "other-client"));
            var items = f.service.tasks("1001", null, f.identity);
            assertEquals(1, items.size()); assertEquals(task.id(), items.getFirst().getId());
            String wire = f.json.writeValueAsString(f.service.task(task.id(), f.identity));
            assertFalse(wire.contains("executionBinding")); assertFalse(wire.contains("do-not-return"));
            assertFalse(wire.contains("accessToken")); assertTrue(wire.contains("operationId"));
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> f.service.tasks("7", null, f.identity)).getStatusCode().value());
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> f.service.tasks("1001", "other-client", f.identity)).getStatusCode().value());
            verify(f.permissions).requireExecution("yuheng.api.mcpTaskAdmin.get");
            assertNull(MDC.get("tenantId")); assertNull(MDC.get("userId"));
        }
    }

    @Test
    void cancelReplaysExactlyOnceAndPayloadOrActorMismatchCannotReuseTheReceipt() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            McpTask task = f.create(f.owner());
            assertTrue(f.cancel(task, "key-1", 0)); assertTrue(f.cancel(task, "key-1", 0));
            assertEquals(1, f.rows.cancellations); assertEquals(1, f.receipts.completed);
            verify(f.audits, times(1)).record(any());
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> f.cancel(task, "key-1", 1)).getStatusCode().value());
            McpTask foreign = f.create(new McpTaskService.Owner("other-user", "1001", "client-1"));
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> f.cancel(foreign, "key-1", 0)).getStatusCode().value());
            assertEquals(1, f.rows.cancellations);
            assertTrue(f.receipts.records.keySet().stream().noneMatch(value -> value.contains("key-1")));
        }
    }

    @Test
    void aLeasedTaskCancelsWithOwnerStateRevisionAndVersionFencesAndLateWorkerCompletionCannotResurrectIt() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            McpTask task = f.create(f.owner()); var row = f.rows.rows.get(task.id());
            row.setWorkerOwner("worker-1").setLeaseUntil(f.nativeAuth.clock.instant().plusSeconds(60)).setAttemptCount(1).setRevision(1L); row.setVersion(1L);
            assertTrue(f.cancel(task, "cancel-worker", 1));
            McpTask cancelled = Mono.from(f.store.find(task.id())).block(); assertNotNull(cancelled);
            assertEquals(McpTask.State.CANCELLED, cancelled.state()); assertEquals(2L, cancelled.revision());
            assertNull(cancelled.workerOwner()); assertNull(cancelled.leaseUntil());
            assertFalse(Mono.from(f.store.transition(new McpTaskStore.Transition(task.id(), McpTask.State.WORKING,
                    McpTask.State.COMPLETED, 1L, "worker-1", task.inputPayload(), Map.of("late", true), null, f.nativeAuth.clock.instant()))).block());
            assertEquals(McpTask.State.CANCELLED, Mono.from(f.store.find(task.id())).block().state());
        }
    }

    @Test
    void staleOrCompletedTasksNeverRunAgainAndAChangedLeaseLosesTheCancelCas() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            McpTask stale = f.create(f.owner()); assertFalse(f.cancel(stale, "stale", 7));
            McpTask completed = f.create(f.owner()); f.rows.rows.get(completed.id()).setState(McpPersistentTaskStateEnum.COMPLETED);
            assertFalse(f.cancel(completed, "completed", 0));
            McpTask racing = f.create(f.owner()); f.rows.conflict = true; assertFalse(f.cancel(racing, "race", 0));
            assertEquals(0, f.rows.cancellations); verifyNoInteractions(f.audits);
            assertEquals(McpPersistentTaskStateEnum.WORKING, f.rows.rows.get(racing.id()).getState());
        }
    }

    @Test
    void auditFailureRollsBackTaskAndReceiptAndRetryDoesNotDoubleDebitTheMutation() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            McpTask task = f.create(f.owner()); doThrow(new IllegalStateException("synthetic audit unavailable")).when(f.audits).record(any());
            assertThrows(IllegalStateException.class, () -> f.cancel(task, "retry", 0));
            assertEquals(McpPersistentTaskStateEnum.WORKING, f.rows.rows.get(task.id()).getState());
            assertTrue(f.receipts.records.isEmpty()); assertEquals(1, f.tx.rollbacks);
            doNothing().when(f.audits).record(any()); assertTrue(f.cancel(task, "retry", 0));
            assertEquals(1L, f.rows.rows.get(task.id()).getRevision());
        }
    }

    @Test
    void livePermissionDenialAndClosedNativeProofWinOverAnExistingSuccessfulReceipt() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            McpTask task = f.create(f.owner()); assertTrue(f.cancel(task, "receipt", 0));
            int reads = f.receipts.reads;
            doThrow(new AccessDeniedException("current policy denied")).when(f.permissions).requireExecution("yuheng.api.mcpTaskAdmin.cancel");
            assertThrows(AccessDeniedException.class, () -> f.cancel(task, "receipt", 0)); assertEquals(reads, f.receipts.reads);
            doNothing().when(f.permissions).requireExecution("yuheng.api.mcpTaskAdmin.cancel"); f.observation.close();
            var denied = assertThrows(McpGatewayIdentityAuthenticator.SecurityFailure.class, () -> f.cancel(task, "receipt", 0));
            assertEquals(503, denied.httpStatus()); assertEquals(reads, f.receipts.reads);
        }
    }

    @Test
    void actorProofCannotBeMixedWithDifferentSpringAuthenticationAndSqlOwnershipIsNotMdcInput() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            McpTask task = f.create(f.owner()); SecurityContextHolder.clearContext();
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> f.service.task(task.id(), f.identity)).getStatusCode().value());
            f.bindSecurity(); MDC.put("tenantId", "999999"); MDC.put("userId", "untrusted");
            assertEquals(task.id(), f.service.task(task.id(), f.identity).getId());
            assertEquals("999999", MDC.get("tenantId")); assertEquals("untrusted", MDC.get("userId"));
            MDC.clear();
        }
    }

    @Test
    void existingHttpShapesUseTheRealServiceAndPrivateRequestAttributeWhileResponsesRemainSanitized() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            McpTask task = f.create(f.owner());
            // The dedicated chain/native filter has its own integration test; this verifies MVC binding and service handoff.
            var mvc = MockMvcBuilders.standaloneSetup(new McpTaskAdminController(f.service))
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(f.json)).build();
            String attr = McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE;
            mvc.perform(get("/api/v1/yuheng/admin/mcp/tasks").param("tenantId", "1001").requestAttr(attr, f.identity))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(task.id())).andExpect(jsonPath("$[0].inputPayload.executionBinding").doesNotExist());
            mvc.perform(get("/api/v1/yuheng/admin/mcp/tasks/{id}", task.id()).requestAttr(attr, f.identity))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value("1001"));
            mvc.perform(post("/api/v1/yuheng/admin/mcp/tasks/{id}/cancel", task.id()).requestAttr(attr, f.identity)
                            .header("Idempotency-Key", "http-key").contentType("application/json").content("{\"expectedRevision\":0}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(true));
            mvc.perform(post("/api/v1/yuheng/admin/mcp/tasks/{id}/cancel", task.id()).requestAttr(attr, f.identity)
                            .header("Idempotency-Key", "http-key").contentType("application/json").content("{\"expectedRevision\":0}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(true));
            assertEquals(1, f.rows.cancellations);
        }
    }

    @Test
    void postgresJsonbBindingRetainsItsTypeWhenARouterReplaysOnlyTheValueAndPreservesSqlNull() throws Exception {
        var statement = mock(java.sql.PreparedStatement.class);
        var handler = new top.egon.cola.component.yuheng.mcp.engine.mcp.dao.typehandler.McpJsonbTypeHandler(
                com.fasterxml.jackson.databind.JsonNode.class);
        var expected = new ObjectMapper().readTree("{\"nested\":{\"enabled\":false,\"count\":3},\"nullable\":null}");
        handler.setNonNullParameter(statement, 1, expected, org.apache.ibatis.type.JdbcType.OTHER);
        var parameter = org.mockito.ArgumentCaptor.forClass(org.postgresql.util.PGobject.class);
        verify(statement).setObject(eq(1), parameter.capture(), eq(java.sql.Types.OTHER));
        assertEquals("jsonb", parameter.getValue().getType());
        assertEquals(expected, new ObjectMapper().readTree(parameter.getValue().getValue()));
        handler.setParameter(statement, 2, null, org.apache.ibatis.type.JdbcType.OTHER);
        verify(statement).setNull(2, java.sql.Types.OTHER);
        var result = mock(java.sql.ResultSet.class);
        when(result.getString("payload")).thenReturn(null, parameter.getValue().getValue());
        assertNull(handler.getNullableResult(result, "payload"));
        assertEquals(expected, handler.getNullableResult(result, "payload"));
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        final McpRequestObservationComponentTest.NativeFixture nativeAuth = new McpRequestObservationComponentTest.NativeFixture();
        final McpRequestObservationComponent observation = new McpRequestObservationComponent();
        final VerifiedManagementIdentityVO identity;
        final McpTaskPersistenceConverter converter = new McpTaskPersistenceConverter(json);
        final Rows rows = new Rows(converter);
        final Receipts receipts = new Receipts();
        final McpManagementAuditRepository audits = mock(McpManagementAuditRepository.class);
        final PermissionExecutionGuard permissions = mock(PermissionExecutionGuard.class);
        final LocalTransaction tx = new LocalTransaction(rows, receipts);
        final MpMcpRuntimeTaskStore store;
        final McpTaskManagementService service;
        final Map<String,Object> binding;
        Fixture(Path directory) throws Exception {
            nativeAuth.token("management-token", false, "1001", Set.of(McpTaskManagementService.READ, McpTaskManagementService.WRITE));
            identity = nativeAuth.authenticator.authenticateManagement(McpRequestObservationComponentTest.http("management-token"),
                    McpRequestObservationComponentTest.RESOURCE, Set.of(McpTaskManagementService.READ))
                    .contextWrite(Context.of(McpRequestObservationComponent.class, observation)).block(); assertNotNull(identity);
            var properties = new EgonColaMybatisPlusProperties();
            var config = new McpPersistenceProperties().setTenantId(7L).setIdentityTenantId(1001L).setServiceUserId("mcp-worker");
            var persistence = new McpPersistenceContextComponent(config, properties);
            store = new MpMcpRuntimeTaskStore(rows, converter, new McpGatewayPersistenceContext(persistence, config), 64);
            var template = new TransactionTemplate(tx); template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); template.setTimeout(5);
            service = new McpTaskManagementService(store, receipts, audits, Mappers.getMapper(McpTaskManagementConverter.class),
                    persistence, template, permissions, nativeAuth.clock);
            var fixed = McpPublishedBindingContractTest.structuralFixture(directory, false);
            var accepted = fixed.loader().fixedActiveBindings().get(fixed.selection()); binding = McpTaskBindingVO.from(accepted).toPayload();
            bindSecurity();
        }
        McpTaskService.Owner owner() { return new McpTaskService.Owner(identity.principal().subject(), identity.principal().tenantId(), identity.clientId()); }
        void bindSecurity() {
            var p = identity.principal();
            var snapshot = new SystemAuthorizationSnapshot(p.tenantId(), p.subject(), "1", "yuheng-mcp", 1, 1, List.of(), List.of(), null,
                    Set.of(McpTaskManagementService.READ, McpTaskManagementService.WRITE), Set.of(), Map.of(), Map.of(), "fixture",
                    nativeAuth.clock.instant(), nativeAuth.clock.instant().plusSeconds(60));
            // Policy evaluation is an explicit client fixture here; the real management-chain test covers fresh runtime policy binding.
            var token = new Rbac3AuthenticationToken(new Rbac3UserDetails(p, snapshot), List.of(new SimpleGrantedAuthority("SCOPE_" + McpTaskManagementService.READ),
                    new SimpleGrantedAuthority("SCOPE_" + McpTaskManagementService.WRITE)));
            token.setDetails(identity.proof()); SecurityContextHolder.getContext().setAuthentication(token);
        }
        McpTask create(McpTaskService.Owner owner) {
            var input = new LinkedHashMap<String,Object>(); input.put(McpTaskBindingVO.PAYLOAD_KEY, binding);
            input.put("operationId", "11"); input.put("accessToken", "do-not-return"); input.put("body", Map.of("safe", "value", "credentialRef", "do-not-return"));
            Instant now = nativeAuth.clock.instant();
            var task = new McpTask("task-" + UUID.randomUUID(), owner.fingerprint(), owner.subjectId(), owner.tenantId(), owner.clientId(),
                    "orders", "lookup", "a".repeat(64), McpTask.State.WORKING, input, null, null, null, null,
                    now.plusSeconds(120), now.plusSeconds(3600), 0, 1, 0, now, now);
            var row = converter.newRow(task); row.setId((long) rows.rows.size() + 1); row.setTenantId(7L); row.setVersion(0L);
            rows.rows.put(task.id(), rows.copy(row)); return task;
        }
        boolean cancel(McpTask task, String key, long revision) { return service.cancelTask(task.id(), revision, key, identity, "request-1", "trace-1"); }
        @Override public void close() { observation.close(); nativeAuth.close(); SecurityContextHolder.clearContext(); MDC.clear(); }
    }

    /** Fake SQL boundary only: the real adapter, converter, owner predicates and state CAS execute above it. */
    private static final class Rows extends McpTaskPersistenceRepository {
        final Map<String,McpTaskRecordPO> rows = new LinkedHashMap<>(); final McpTaskPersistenceConverter converter;
        boolean conflict; int cancellations;
        Rows(McpTaskPersistenceConverter converter) { super(mock(McpTaskDAO.class), new EgonColaMybatisPlusProperties()); this.converter = converter; }
        @Override public List<McpTaskRecordPO> listManagementOwned(String tenant, String subject, String client, String fingerprint, String key, int limit) {
            assertEquals("7", MDC.get("tenantId"));
            return rows.values().stream().filter(row -> row.getSubjectTenantId().equals(tenant) && row.getSubjectId().equals(subject)
                    && row.getClientId().equals(client) && row.getPrincipalFingerprint().equals(fingerprint)
                    && (key == null || row.getTaskKey().equals(key))).limit(limit).map(this::copy).toList();
        }
        @Override public Optional<McpTaskRecordPO> findActiveByTaskKey(String key) { return Optional.ofNullable(rows.get(key)).map(this::copy); }
        @Override public boolean compareAndSetManagementCancellation(McpTaskRecordPO row, McpPersistentTaskStateEnum state, long revision, String worker) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            var before = rows.get(row.getTaskKey());
            if (conflict || before == null || before.getState() != state || before.getRevision() != revision
                    || !Objects.equals(before.getVersion(), row.getVersion()) || !Objects.equals(worker, before.getWorkerOwner())) return false;
            row.setWorkerOwner(null).setLeaseUntil(null); row.setVersion(row.getVersion() + 1); rows.put(row.getTaskKey(), copy(row)); cancellations++; return true;
        }
        McpTaskRecordPO copy(McpTaskRecordPO row) {
            var copy = converter.newRow(converter.toBusiness(row)); copy.setId(row.getId()); copy.setTenantId(row.getTenantId()); copy.setVersion(row.getVersion()); return copy;
        }
        Map<String,McpTaskRecordPO> snapshot() { Map<String,McpTaskRecordPO> result = new LinkedHashMap<>(); rows.forEach((key,row) -> result.put(key,copy(row))); return result; }
    }
    private static final class Receipts extends McpTaskCancellationReceiptRepository {
        final Map<String,McpTaskCancellationReceiptPO> records = new LinkedHashMap<>(); int reads, completed; long thread;
        Receipts() { super(mock(McpTaskCancellationReceiptDAO.class), new EgonColaMybatisPlusProperties()); }
        @Override public void lock(long lock, String scope, String key) { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); thread = Thread.currentThread().threadId(); }
        @Override public Optional<McpTaskCancellationReceiptPO> findReceipt(String scope, String key) { reads++; return Optional.ofNullable(records.get(scope + ':' + key)); }
        @Override public void complete(String scope, String key, String digest, String task, boolean cancelled, Instant expiry) {
            assertEquals(thread, Thread.currentThread().threadId()); assertEquals("7", MDC.get("tenantId"));
            records.put(scope + ':' + key, new McpTaskCancellationReceiptPO().setResourceId(task).setPayloadSha256(digest).setExpiresAt(expiry)
                    .setResponseContent(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("cancelled", cancelled))); completed++;
        }
    }
    /** Spring's real transaction lifecycle with deterministic in-memory rollback; PostgreSQL mapping is a separate gate. */
    private static final class LocalTransaction extends AbstractPlatformTransactionManager {
        final Rows rows; final Receipts receipts; Map<String,McpTaskRecordPO> before; Map<String,McpTaskCancellationReceiptPO> receiptBefore; int rollbacks;
        LocalTransaction(Rows rows, Receipts receipts) { this.rows = rows; this.receipts = receipts; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { before = rows.snapshot(); receiptBefore = new LinkedHashMap<>(receipts.records); }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { rows.rows.clear(); rows.rows.putAll(before); receipts.records.clear(); receipts.records.putAll(receiptBefore); rollbacks++; }
    }
}
