package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.outbox.api.OutboxMessage;
import top.egon.cola.component.outbox.api.OutboxReceipt;
import top.egon.cola.component.outbox.api.TenantScopedOutboxKey;
import top.egon.cola.component.outbox.api.TransactionalOutbox;
import top.egon.cola.component.yuheng.admin.exposure.converter.GatewayUrlExposureViewConverter;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.domain.command.GatewayUrlExposureStateCommand;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.exposure.domain.event.ExposureDesiredStateChangedEvent;
import top.egon.cola.component.yuheng.admin.exposure.domain.exception.GatewayUrlExposureException;
import top.egon.cola.component.yuheng.admin.exposure.domain.query.GatewayUrlExposureQuery;
import top.egon.cola.component.yuheng.admin.exposure.domain.vo.GatewayUrlExposureVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.service.impl.GatewayUrlExposureServiceImpl;
import top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO;
import top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminIdempotencyConflictException;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Business ordering and participation tests; the separate PostgreSQL integration verifies physical atomicity. */
class GatewayUrlExposureServiceTest {

    private static final long OWNER = 101L;
    private static final long ID = 810001L;
    private static final Instant NOW = Instant.parse("2026-11-18T20:00:00Z");
    private static final String WRITE = "yuheng:exposures:write";
    private static final String REOPEN = "yuheng:exposures:reopen";
    private static final String READ = "yuheng:read";
    private static final AdminActor ADMIN = new AdminActor("admin-a", AdminActorTypeEnum.USER,
            Set.of(READ, WRITE, REOPEN), Set.of(), Set.of(READ, WRITE, REOPEN));

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final GatewayUrlExposureRepository exposures = mock(GatewayUrlExposureRepository.class);
    private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
    private final GatewayAuditLogRepository audits = mock(GatewayAuditLogRepository.class);
    private final TransactionalOutbox outbox = mock(TransactionalOutbox.class);
    private final GatewayUrlExposureViewConverter converter = mock(GatewayUrlExposureViewConverter.class);
    private final Map<Long, GatewayUrlExposureBO> rows = new LinkedHashMap<>();
    private final Map<String, IdempotencyBO> receipts = new LinkedHashMap<>();
    private final List<GatewayAuditLogBO> auditRows = new ArrayList<>();
    private final List<OutboxMessage> messages = new ArrayList<>();
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private final FixtureTransactionManager manager = new FixtureTransactionManager();
    private GatewayUrlExposureService service;

    @BeforeEach
    void setUp() {
        MDC.put("tenantId", Long.toString(OWNER));
        validator.afterPropertiesSet();
        rows.put(ID, initial(ID));
        when(exposures.findOwned(anyLong(), anyLong())).thenAnswer(call -> {
            GatewayUrlExposureBO row = rows.get(call.getArgument(1, Long.class));
            return row != null && row.getOwnerTenantId().equals(call.getArgument(0, Long.class))
                    ? Optional.of(copy(row)) : Optional.empty();
        });
        when(exposures.compareAndSetDesired(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(), any()))
                .thenAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    var row = rows.get(call.getArgument(1, Long.class));
                    if (!row.getOwnerTenantId().equals(call.getArgument(0, Long.class))
                            || !row.getVersion().equals(call.getArgument(2, Long.class))
                            || !row.getDesiredGeneration().equals(call.getArgument(3, Long.class))
                            || row.getDesiredState() != call.getArgument(4)) return false;
                    row.setDesiredState(call.getArgument(5)).setDesiredGeneration(row.getDesiredGeneration() + 1)
                            .setVersion(row.getVersion() + 1).setReconcileStatus("PENDING")
                            .setChangeReason(call.getArgument(6)).setUpdateTime(call.getArgument(7));
                    return true;
                });
        when(idempotency.find(anyString(), anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(
                receipts.get(receiptKey(call.getArgument(0), call.getArgument(1), call.getArgument(2)))));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            IdempotencyBO receipt = call.getArgument(0);
            receipts.put(receiptKey(receipt.getScopeType(), receipt.getScopeId(), receipt.getKey()), receipt);
            return null;
        }).when(idempotency).save(any());
        when(audits.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            GatewayAuditLogBO audit = call.getArgument(0);
            auditRows.add(audit);
            return audit;
        });
        when(outbox.enqueue(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            OutboxMessage message = call.getArgument(0);
            messages.add(message);
            return new OutboxReceipt(message.messageId(), message.idempotencyKey(), true);
        });
        when(converter.toView(any())).thenAnswer(call -> view(call.getArgument(0)));
        service = service(null);
    }

    @AfterEach
    void cleanUp() {
        validator.close();
        MDC.clear();
    }

    @Test
    void stateCommandAndOwnedQueryHaveAnExplicitLocalServiceBoundary() throws Exception {
        assertThat(GatewayUrlExposureServiceImpl.class).isAssignableTo(GatewayUrlExposureService.class);
        assertThat(GatewayUrlExposureService.class.getMethod("putState", AdminActor.class,
                GatewayUrlExposureStateCommand.class).getReturnType().getSimpleName()).isEqualTo("CommandResult");
        assertThat(GatewayUrlExposureService.class.getMethod("get", AdminActor.class,
                GatewayUrlExposureQuery.class).getReturnType().getSimpleName()).isEqualTo("QueryResult");
    }

    @Test
    void closeCommitsOneGenerationWithCurrentVersionAuditReceiptAndTrustedOutboxFact() {
        var result = service.putState(ADMIN, command("close-a", GatewayUrlExposureStateEnum.CLOSED));
        assertThat(result.status()).isEqualTo(202);
        assertThat(result.etag()).isEqualTo(tag(ID, 13));
        assertThat(result.view().getDesiredGeneration()).isEqualTo("6");
        assertThat(result.view().getObservedGeneration()).isEqualTo("5");
        assertThat(rows.get(ID).getReleaseId()).isEqualTo(820001L);
        assertThat(receipts).hasSize(1);
        assertThat(auditRows).hasSize(1);
        assertThat(messages).hasSize(1);
        var event = (ExposureDesiredStateChangedEvent) messages.getFirst().payload();
        assertThat(event.ownerTenantId()).isEqualTo(OWNER);
        assertThat(event.exposureId()).isEqualTo(ID);
        assertThat(event.desiredGeneration()).isEqualTo(6L);
        assertThat(event.desiredState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(messages.getFirst().channel()).isEqualTo(ExposureDesiredStateChangedEvent.CHANNEL);
        assertThat(messages.getFirst().destination()).isEqualTo(ExposureDesiredStateChangedEvent.DESTINATION);
        assertThat(messages.getFirst().schemaVersion()).isEqualTo("1");
        assertThat(messages.getFirst().idempotencyKey()).isEqualTo(
                TenantScopedOutboxKey.of(OWNER, "exposure-desired-state", ID + ":6"));
        assertThat(messages.getFirst().headers()).containsEntry("tenant-id", "101").containsEntry("event-id", event.eventId());
        verify(exposures).compareAndSetDesired(OWNER, ID, 12L, 5L, GatewayUrlExposureStateEnum.OPEN,
                GatewayUrlExposureStateEnum.CLOSED, "maintenance", NOW);
    }

    @Test
    void exactCommittedReplayPrecedesStaleTagAndKeepsTheOriginalStatusBodyAndTag() {
        var command = command("close-replay", GatewayUrlExposureStateEnum.CLOSED);
        var accepted = service.putState(ADMIN, command);
        rows.get(ID).setVersion(30L).setDesiredState(GatewayUrlExposureStateEnum.WITHDRAWN).setDesiredGeneration(8L);
        var replay = service.putState(ADMIN, command);
        assertThat(replay.status()).isEqualTo(accepted.status());
        assertThat(replay.etag()).isEqualTo(accepted.etag());
        assertThat((JsonNode) json.valueToTree(replay.view())).isEqualTo((JsonNode) json.valueToTree(accepted.view()));
        assertThat(auditRows).hasSize(1);
        assertThat(messages).hasSize(1);
    }

    @Test
    void replayStillRequiresCurrentScopesCapabilitiesAndOwnedResource() {
        var command = command("authorized-replay", GatewayUrlExposureStateEnum.CLOSED);
        service.putState(ADMIN, command);
        var noOauth = new AdminActor("admin-a", AdminActorTypeEnum.USER, Set.of("*"), Set.of(), Set.of());
        assertThatThrownBy(() -> service.putState(noOauth, command)).isInstanceOf(GatewayUrlExposureException.class)
                .extracting(failure -> ((GatewayUrlExposureException) failure).status()).isEqualTo(403);
        var noCapability = new AdminActor("admin-a", AdminActorTypeEnum.USER, Set.of(), Set.of(), Set.of(WRITE));
        assertThatThrownBy(() -> service.putState(noCapability, command)).isInstanceOf(GatewayUrlExposureException.class);
        MDC.put("tenantId", "202");
        assertThatThrownBy(() -> service.putState(ADMIN, command)).isInstanceOf(GatewayAdminNotFoundException.class);
        assertThat(messages).hasSize(1);
    }

    @Test
    void changedBodyOrIfMatchUnderTheSameKeyIsAConflictBeforeTheTagCheck() {
        service.putState(ADMIN, command("conflict", GatewayUrlExposureStateEnum.CLOSED));
        assertThatThrownBy(() -> service.putState(ADMIN, new GatewayUrlExposureStateCommand(ID, tag(ID, 12), "conflict",
                GatewayUrlExposureStateEnum.CLOSED, "different reason"))).isInstanceOf(GatewayAdminIdempotencyConflictException.class);
        assertThatThrownBy(() -> service.putState(ADMIN, new GatewayUrlExposureStateCommand(ID, tag(ID, 13), "conflict",
                GatewayUrlExposureStateEnum.CLOSED, "maintenance"))).isInstanceOf(GatewayAdminIdempotencyConflictException.class);
        assertThat(messages).hasSize(1);
    }

    @Test
    void normalizedReasonHasTheSameFingerprintAndKeysAreScopedPerExposure() {
        var first = new GatewayUrlExposureStateCommand(ID, tag(ID, 12), "same-key", GatewayUrlExposureStateEnum.CLOSED, " maintenance ");
        service.putState(ADMIN, first);
        service.putState(ADMIN, command("same-key", GatewayUrlExposureStateEnum.CLOSED));
        rows.put(ID + 1, initial(ID + 1));
        service.putState(ADMIN, new GatewayUrlExposureStateCommand(ID + 1, tag(ID + 1, 12), "same-key",
                GatewayUrlExposureStateEnum.CLOSED, "maintenance"));
        assertThat(receipts).hasSize(2);
        assertThat(receipts.values()).extracting(IdempotencyBO::getScopeId).containsExactlyInAnyOrder("101:810001", "101:810002");
        assertThat(messages).hasSize(2);
    }

    @Test
    void missingWeakWildcardMultipleWhitespaceAndOverflowTagsAreRejectedPrecisely() {
        assertStatus(new GatewayUrlExposureStateCommand(ID, null, "missing", GatewayUrlExposureStateEnum.CLOSED, "maintenance"), 428);
        for (String value : new String[]{"W/" + tag(ID, 12), "*", tag(ID, 12) + "," + tag(ID, 13),
                " " + tag(ID, 12), "\"exposure-9999999999999999999-v12\""}) {
            assertStatus(new GatewayUrlExposureStateCommand(ID, value, "invalid", GatewayUrlExposureStateEnum.CLOSED, "maintenance"), 400);
        }
        assertStatus(new GatewayUrlExposureStateCommand(ID, tag(ID, 11), "stale", GatewayUrlExposureStateEnum.CLOSED, "maintenance"), 412);
        assertStatus(new GatewayUrlExposureStateCommand(ID, tag(ID + 1, 12), "other-tag", GatewayUrlExposureStateEnum.CLOSED, "maintenance"), 412);
        assertThat(messages).isEmpty();
        assertThat(receipts).isEmpty();
    }

    @Test
    void observationVersionConflictUsesTheActualNewestVersionWithoutChangingIntent() {
        doAnswer(call -> {
            rows.get(ID).setVersion(13L);
            return false;
        }).when(exposures).compareAndSetDesired(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(), any());
        assertThatThrownBy(() -> service.putState(ADMIN, command("watch-race", GatewayUrlExposureStateEnum.CLOSED)))
                .isInstanceOf(GatewayUrlExposureException.class).satisfies(failure -> {
                    assertThat(((GatewayUrlExposureException) failure).status()).isEqualTo(412);
                    assertThat(((GatewayUrlExposureException) failure).currentRevision()).isEqualTo(13L);
                });
        assertThat(messages).isEmpty();
    }

    @Test
    void withdrawnIsTerminalAndCannotBeReopenedOrClosed() {
        rows.get(ID).setDesiredState(GatewayUrlExposureStateEnum.WITHDRAWN);
        for (var state : new GatewayUrlExposureStateEnum[]{GatewayUrlExposureStateEnum.OPEN, GatewayUrlExposureStateEnum.CLOSED}) {
            assertThatThrownBy(() -> service.putState(ADMIN, command("terminal-" + state.wireValue(), state)))
                    .isInstanceOf(GatewayUrlExposureException.class).satisfies(failure ->
                            assertThat(((GatewayUrlExposureException) failure).code()).isEqualTo("YUHENG_EXPOSURE_WITHDRAWN"));
        }
        verifyNoInteractions(outbox, audits);
    }

    @Test
    void sameClosedStateIs200OnlyWithAllCurrentGenerationCloseEvidence() {
        var row = rows.get(ID);
        row.setDesiredState(GatewayUrlExposureStateEnum.CLOSED).setObservedState("CLOSED").setReconcileStatus("SYNCED");
        row.setObservation(closeEvidence(5L));
        var result = service.putState(ADMIN, command("same-close", GatewayUrlExposureStateEnum.CLOSED));
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.etag()).isEqualTo(tag(ID, 12));
        assertThat(rows.get(ID).getDesiredGeneration()).isEqualTo(5L);
        verify(exposures, never()).compareAndSetDesired(anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(), any());
        verifyNoInteractions(outbox, audits);
        assertThat(receipts).hasSize(1);
    }

    @Test
    void staleOrIncompleteCloseEvidenceNeverTurnsANoChangeInto200() {
        rows.get(ID).setDesiredState(GatewayUrlExposureStateEnum.CLOSED).setObservedState("CLOSED").setReconcileStatus("SYNCED")
                .setObservation(closeEvidence(4L));
        assertThat(service.putState(ADMIN, command("old-evidence", GatewayUrlExposureStateEnum.CLOSED)).status()).isEqualTo(202);
        rows.get(ID).setObservation(null).setReconcileStatus("PENDING");
        assertThat(service.putState(ADMIN, command("pending", GatewayUrlExposureStateEnum.CLOSED)).status()).isEqualTo(202);
        assertThat(rows.get(ID).getDesiredGeneration()).isEqualTo(5L);
    }

    @Test
    void explicitReopenAndNoChangeOpenAlwaysRequireReopenAuthorizationAndCurrentBinding() {
        var onlyWrite = new AdminActor("admin-a", AdminActorTypeEnum.USER, Set.of(WRITE), Set.of(), Set.of(WRITE));
        assertThatThrownBy(() -> service.putState(onlyWrite, command("forbidden-open", GatewayUrlExposureStateEnum.OPEN)))
                .isInstanceOf(GatewayUrlExposureException.class).satisfies(failure ->
                        assertThat(((GatewayUrlExposureException) failure).status()).isEqualTo(403));
        assertStatus(command("no-validator", GatewayUrlExposureStateEnum.OPEN), 409);
        rows.get(ID).setDesiredState(GatewayUrlExposureStateEnum.CLOSED);
        assertStatus(command("reopen-no-validator", GatewayUrlExposureStateEnum.OPEN), 409);
        assertThat(messages).isEmpty();
    }

    @Test
    void foreignOpenRemains404BeforeTheExtraReopenPermissionCheck() {
        var onlyWrite = new AdminActor("admin-a", AdminActorTypeEnum.USER, Set.of(WRITE), Set.of(), Set.of(WRITE));
        MDC.put("tenantId", "202");
        assertThatThrownBy(() -> service.putState(onlyWrite, command("foreign-open", GatewayUrlExposureStateEnum.OPEN)))
                .isInstanceOf(GatewayAdminNotFoundException.class);
        verifyNoInteractions(idempotency, audits, outbox);
    }

    @Test
    void approvedOpenIsAuditedOnceAndCommittedReplayDoesNotRepeatPhysicalValidation() {
        var reopen = mock(GatewayUrlExposureService.ReopenValidator.class);
        service = service(reopen);
        rows.get(ID).setDesiredState(GatewayUrlExposureStateEnum.CLOSED);
        var command = command("approved-open", GatewayUrlExposureStateEnum.OPEN);
        assertThat(service.putState(ADMIN, command).status()).isEqualTo(202);
        verify(reopen).validate(eq(OWNER), any());
        clearInvocations(reopen);
        doThrow(GatewayUrlExposureException.bindingConflict()).when(reopen).validate(anyLong(), any());
        assertThat(service.putState(ADMIN, command).status()).isEqualTo(202);
        verifyNoInteractions(reopen);
        assertThat(messages).hasSize(1);
    }

    @Test
    void queryReturnsTheLatestOwnedControlVersionAndNeverWritesAnything() {
        rows.get(ID).setVersion(19L);
        var result = service.get(ADMIN, new GatewayUrlExposureQuery(ID));
        assertThat(result.etag()).isEqualTo(tag(ID, 19));
        assertThat(result.view().getDesiredGeneration()).isEqualTo("5");
        verifyNoInteractions(idempotency, audits, outbox);
        MDC.put("tenantId", "202");
        assertThatThrownBy(() -> service.get(ADMIN, new GatewayUrlExposureQuery(ID))).isInstanceOf(GatewayAdminNotFoundException.class);
    }

    @Test
    void beanValidationRejectsInvalidNonHttpCommandsBeforeMutation() {
        for (var invalid : List.of(new GatewayUrlExposureStateCommand(0L, tag(ID, 12), "key", GatewayUrlExposureStateEnum.CLOSED, "reason"),
                new GatewayUrlExposureStateCommand(ID, tag(ID, 12), "key", null, "reason"),
                new GatewayUrlExposureStateCommand(ID, tag(ID, 12), "bad key", GatewayUrlExposureStateEnum.CLOSED, "reason"),
                new GatewayUrlExposureStateCommand(ID, tag(ID, 12), "key", GatewayUrlExposureStateEnum.CLOSED, "   "))) {
            assertThatThrownBy(() -> service.putState(ADMIN, invalid)).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(exposures, idempotency, audits, outbox);
    }

    @Test
    void outboxFailureRollsBackAllParticipantsAndReturnsStableControlUnavailable() {
        doThrow(new IllegalStateException("synthetic outbox unavailable")).when(outbox).enqueue(any());
        assertStatus(command("rolled-back", GatewayUrlExposureStateEnum.CLOSED), 503);
        assertThat(rows.get(ID).getDesiredState()).isEqualTo(GatewayUrlExposureStateEnum.OPEN);
        assertThat(rows.get(ID).getVersion()).isEqualTo(12L);
        assertThat(receipts).isEmpty();
        assertThat(auditRows).isEmpty();
        assertThat(messages).isEmpty();
    }

    @Test
    void aCommitUnknownReturns503AndRetryUsesTheCommittedReceiptWithoutRepeatingEffects() {
        manager.failAfterNextCommit = true;
        var command = command("unknown-commit", GatewayUrlExposureStateEnum.CLOSED);
        assertStatus(command, 503);
        assertThat(rows.get(ID).getDesiredState()).isEqualTo(GatewayUrlExposureStateEnum.CLOSED);
        assertThat(service.putState(ADMIN, command).status()).isEqualTo(202);
        assertThat(messages).hasSize(1);
        assertThat(auditRows).hasSize(1);
    }

    @Test
    void queryDatabaseFailureIs503AndNeverTouchesAnOutboxOrKubernetesPath() {
        doThrow(new DataAccessResourceFailureException("synthetic authority unavailable")).when(exposures).findOwned(OWNER, ID);
        assertThatThrownBy(() -> service.get(ADMIN, new GatewayUrlExposureQuery(ID)))
                .isInstanceOf(GatewayUrlExposureException.class).satisfies(failure ->
                        assertThat(((GatewayUrlExposureException) failure).status()).isEqualTo(503));
        verifyNoInteractions(idempotency, audits, outbox);
    }

    private GatewayUrlExposureService service(GatewayUrlExposureService.ReopenValidator reopen) {
        var beans = new StaticListableBeanFactory();
        if (reopen != null) beans.addBean("gatewayExposureReopenValidator", reopen);
        return new GatewayUrlExposureServiceImpl(exposures, idempotency, audits, outbox, converter,
                new TransactionTemplate(manager), Clock.fixed(NOW, ZoneOffset.UTC), json, new ValidationUtils(validator),
                beans.getBeanProvider(GatewayUrlExposureService.ReopenValidator.class));
    }

    private static GatewayUrlExposureStateCommand command(String key, GatewayUrlExposureStateEnum state) {
        return new GatewayUrlExposureStateCommand(ID, tag(ID, 12), key, state, "maintenance");
    }

    private void assertStatus(GatewayUrlExposureStateCommand command, int expected) {
        assertThatThrownBy(() -> service.putState(ADMIN, command)).isInstanceOf(GatewayUrlExposureException.class)
                .satisfies(failure -> assertThat(((GatewayUrlExposureException) failure).status()).isEqualTo(expected));
    }

    private static String tag(long id, long version) {
        return "\"exposure-" + id + "-v" + version + "\"";
    }

    private static String timestamp(Instant value) {
        return DateTimeFormatter.ofPattern("uuuu-MM-dd\'T\'HH:mm:ss.SSSSSS\'Z\'").withZone(ZoneOffset.UTC).format(value);
    }

    private static String receiptKey(String scope, String id, String key) {
        return scope + ":" + id + ":" + key;
    }

    private GatewayUrlExposureBO copy(GatewayUrlExposureBO row) {
        return json.convertValue(json.valueToTree(row), GatewayUrlExposureBO.class);
    }

    private static GatewayUrlExposureBO initial(long id) {
        return GatewayUrlExposureBO.builder().id(id).tenantId(0L).ownerTenantId(OWNER).gatewayGroupId(800001L)
                .routeId("orders-read").version(12L).desiredState(GatewayUrlExposureStateEnum.OPEN)
                .desiredGeneration(5L).observedGeneration(5L).observedState("OPEN").reconcileStatus("SYNCED")
                .releaseId(820001L).contentSha256("a".repeat(64)).updateTime(NOW.minusSeconds(1)).changeReason("initial")
                .hostname("api.example.com").httpMethod("GET").pathValue("/orders").build();
    }

    private ObjectNode closeEvidence(long generation) {
        ObjectNode observation = json.createObjectNode();
        for (String type : new String[]{"Detached", "NoAlternateRoute", "DataplaneConverged"}) {
            observation.withArray("conditions").addObject().put("type", type).put("status", "True")
                    .put("reason", "Verified").put("message", "verified").put("observedGeneration", Long.toString(generation))
                    .put("lastTransitionTime", timestamp(NOW));
        }
        return observation;
    }

    private GatewayUrlExposureVO view(GatewayUrlExposureBO row) {
        ObjectNode value = json.createObjectNode();
        value.put("exposureId", row.getId().toString()).put("gatewayGroupId", row.getGatewayGroupId().toString())
                .put("routeId", row.getRouteId()).put("url", "https://api.example.com/orders").put("method", "GET")
                .put("desiredState", row.getDesiredState().wireValue()).put("observedState", row.getObservedState())
                .put("desiredGeneration", row.getDesiredGeneration().toString()).put("observedGeneration", row.getObservedGeneration().toString())
                .put("reconcileStatus", row.getReconcileStatus()).put("releaseId", row.getReleaseId().toString())
                .put("contentSha256", row.getContentSha256()).put("terminal", row.getDesiredState().isTerminal())
                .putNull("kubernetesRoute").putNull("lastErrorCode").put("updatedAt", timestamp(row.getUpdateTime()));
        value.set("conditions", row.getObservation() == null ? json.createArrayNode() : row.getObservation().get("conditions"));
        return json.convertValue(value, GatewayUrlExposureVO.class);
    }

    /** Transaction double validates callback participation; database behavior is covered by the parent's real PG test. */
    private final class FixtureTransactionManager extends AbstractPlatformTransactionManager {
        private Map<Long, GatewayUrlExposureBO> savedRows;
        private Map<String, IdempotencyBO> savedReceipts;
        private int savedAudits;
        private int savedMessages;
        private boolean failAfterNextCommit;

        @Override protected Object doGetTransaction() { return new Object(); }

        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            savedRows = new LinkedHashMap<>();
            rows.forEach((id, row) -> savedRows.put(id, copy(row)));
            savedReceipts = new LinkedHashMap<>(receipts);
            savedAudits = auditRows.size();
            savedMessages = messages.size();
        }

        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failAfterNextCommit) {
                failAfterNextCommit = false;
                throw new TransactionSystemException("synthetic commit result unknown");
            }
        }

        @Override protected void doRollback(DefaultTransactionStatus status) {
            rows.clear();
            rows.putAll(savedRows);
            receipts.clear();
            receipts.putAll(savedReceipts);
            while (auditRows.size() > savedAudits) auditRows.removeLast();
            while (messages.size() > savedMessages) messages.removeLast();
        }
    }
}
