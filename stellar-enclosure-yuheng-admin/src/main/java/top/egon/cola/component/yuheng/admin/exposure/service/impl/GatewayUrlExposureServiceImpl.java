package top.egon.cola.component.yuheng.admin.exposure.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.outbox.api.OutboxMessage;
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
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureService;
import top.egon.cola.component.yuheng.admin.observability.domain.bo.GatewayAuditLogBO;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminIdempotencyConflictException;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Commits control CAS, exact command receipt, audit and outbox in the same selected LOCAL transaction. */
@Validated
@Service("gatewayUrlExposureServiceImpl")
public class GatewayUrlExposureServiceImpl implements GatewayUrlExposureService {

    private static final String SCOPE = "URL_EXPOSURE_STATE";
    private static final String READ = "yuheng:read";
    private static final String WRITE = "yuheng:exposures:write";
    private static final String REOPEN = "yuheng:exposures:reopen";
    private static final Pattern STRONG_TAG = Pattern.compile("\"exposure-([1-9][0-9]{0,18})-v(0|[1-9][0-9]{0,18})\"");
    private static final TypeReference<Map<String, Object>> RESPONSE_MAP = new TypeReference<>() { };
    private static final Set<String> CLOSE_CONDITIONS = Set.of("Detached", "NoAlternateRoute", "DataplaneConverged");

    private final GatewayUrlExposureRepository exposures;
    private final IdempotencyRepository idempotency;
    private final GatewayAuditLogRepository audits;
    private final TransactionalOutbox outbox;
    private final GatewayUrlExposureViewConverter converter;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectMapper json;
    private final ValidationUtils validation;
    private final ObjectProvider<ReopenValidator> reopenValidators;

    public GatewayUrlExposureServiceImpl(
            @Qualifier("mpGatewayUrlExposureRepository") GatewayUrlExposureRepository exposures,
            @Qualifier("idempotencyRepository") IdempotencyRepository idempotency,
            @Qualifier("gatewayAuditLogRepository") GatewayAuditLogRepository audits,
            @Qualifier("transactionalOutbox") TransactionalOutbox outbox,
            @Qualifier("gatewayUrlExposureViewConverter") GatewayUrlExposureViewConverter converter,
            @Qualifier("gatewayExposureTransactionTemplate") TransactionTemplate transactions,
            @Qualifier("gatewayProjectionClock") Clock clock,
            @Qualifier("jacksonObjectMapper") ObjectMapper json,
            @Qualifier("egonColaValidationUtils") ValidationUtils validation,
            @Qualifier("gatewayExposureReopenValidator") ObjectProvider<ReopenValidator> reopenValidators) {
        this.exposures = Objects.requireNonNull(exposures, "exposures");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.audits = Objects.requireNonNull(audits, "audits");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.reopenValidators = Objects.requireNonNull(reopenValidators, "reopenValidators");
    }

    @Override
    public CommandResult putState(AdminActor actor, GatewayUrlExposureStateCommand command) {
        validation.validate(Objects.requireNonNull(command, "command"));
        long owner = owner();
        authorize(actor, WRITE);
        String fingerprint = fingerprint(command);
        try {
            return Objects.requireNonNull(transactions.execute(status -> accept(owner, actor, command, fingerprint)));
        } catch (DuplicateKeyException concurrentReceipt) {
            // A duplicate can only be interpreted after the failed transaction has rolled back.
            try {
                return Objects.requireNonNull(transactions.execute(status -> {
                    owned(owner, command.exposureId());
                    authorize(actor, WRITE);
                    if (command.state() == GatewayUrlExposureStateEnum.OPEN) authorize(actor, REOPEN);
                    CommandResult replay = replay(owner, command, fingerprint);
                    if (replay == null) throw GatewayUrlExposureException.controlUnavailable(null);
                    return replay;
                }));
            } catch (DataAccessException | TransactionException unavailable) {
                throw GatewayUrlExposureException.controlUnavailable(unavailable);
            }
        } catch (GatewayUrlExposureException | GatewayAdminNotFoundException | GatewayAdminIdempotencyConflictException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw GatewayUrlExposureException.controlUnavailable(unavailable);
        }
    }

    @Override
    public QueryResult get(AdminActor actor, GatewayUrlExposureQuery query) {
        validation.validate(Objects.requireNonNull(query, "query"));
        long owner = owner();
        authorize(actor, READ);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                GatewayUrlExposureBO row = owned(owner, query.exposureId());
                return new QueryResult(converter.toView(row), etag(row));
            }));
        } catch (GatewayUrlExposureException | GatewayAdminNotFoundException | GatewayAdminIdempotencyConflictException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw GatewayUrlExposureException.controlUnavailable(unavailable);
        }
    }

    private CommandResult accept(long owner, AdminActor actor, GatewayUrlExposureStateCommand command, String fingerprint) {
        GatewayUrlExposureBO before = owned(owner, command.exposureId());
        // No durable response is disclosed until current owner and permissions have been checked.
        authorize(actor, WRITE);
        if (command.state() == GatewayUrlExposureStateEnum.OPEN) authorize(actor, REOPEN);
        CommandResult replay = replay(owner, command, fingerprint);
        if (replay != null) return replay;
        validateTag(command.ifMatch());
        if (!command.ifMatch().equals(etag(before))) throw GatewayUrlExposureException.preconditionFailed(before.getVersion());
        if (before.getDesiredState().isTerminal() && command.state() != GatewayUrlExposureStateEnum.WITHDRAWN) {
            throw GatewayUrlExposureException.withdrawn(before.getVersion());
        }
        if (command.state() == GatewayUrlExposureStateEnum.OPEN) {
            ReopenValidator validator = reopenValidators.getIfAvailable();
            if (validator == null) throw GatewayUrlExposureException.bindingConflict();
            validator.validate(owner, before);
        }
        Instant now = clock.instant();
        if (before.getDesiredState() == command.state()) {
            GatewayUrlExposureVO view = converter.toView(before);
            return remember(owner, command, fingerprint,
                    new CommandResult(converged(view) ? 200 : 202, view, etag(before)), now);
        }
        if (!exposures.compareAndSetDesired(owner, before.getId(), before.getVersion(), before.getDesiredGeneration(),
                before.getDesiredState(), command.state(), command.changeReason(), now)) {
            CommandResult concurrent = replay(owner, command, fingerprint);
            if (concurrent != null) return concurrent;
            throw GatewayUrlExposureException.preconditionFailed(owned(owner, command.exposureId()).getVersion());
        }
        GatewayUrlExposureBO after = owned(owner, command.exposureId());
        if (after.getDesiredState() != command.state()
                || after.getDesiredGeneration() != Math.addExact(before.getDesiredGeneration(), 1L)
                || after.getVersion() <= before.getVersion()) {
            throw GatewayUrlExposureException.controlUnavailable(null);
        }
        RequestAuditContext request = RequestAuditContext.current();
        audits.save(GatewayAuditLogBO.builder().actorId(actor.actorId()).actorType(actor.actorType().wireValue())
                .source("MANAGEMENT_API").requestId(request.requestId()).traceId(request.traceId())
                .resourceType("YUHENG_URL_EXPOSURE").resourceId(Long.toString(before.getId())).action("REPLACE_STATE")
                .beforeSummary(summary(before)).afterSummary(summary(after)).draftRevision(after.getVersion())
                .releaseId(Long.toString(after.getReleaseId())).successful(true).occurredAt(now).build());
        String eventId = UUID.randomUUID().toString();
        ExposureDesiredStateChangedEvent event = new ExposureDesiredStateChangedEvent(eventId, owner,
                after.getId(), after.getDesiredGeneration(), after.getDesiredState(), now);
        outbox.enqueue(OutboxMessage.builder().messageId(eventId)
                .idempotencyKey(TenantScopedOutboxKey.of(owner, "exposure-desired-state",
                        after.getId() + ":" + after.getDesiredGeneration()))
                .channel(ExposureDesiredStateChangedEvent.CHANNEL).destination(ExposureDesiredStateChangedEvent.DESTINATION)
                .schemaVersion(ExposureDesiredStateChangedEvent.SCHEMA_VERSION).payload(event)
                .header("tenant-id", Long.toString(owner)).header("event-id", eventId).traceId(request.traceId()).build());
        return remember(owner, command, fingerprint,
                new CommandResult(202, converter.toView(after), etag(after)), now);
    }

    private CommandResult remember(long owner, GatewayUrlExposureStateCommand command, String fingerprint,
                                   CommandResult result, Instant at) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status());
        response.put("etag", result.etag());
        response.put("view", json.convertValue(result.view(), RESPONSE_MAP));
        idempotency.save(new IdempotencyBO(SCOPE, scopeId(owner, command.exposureId()), command.idempotencyKey(),
                fingerprint, Long.toString(command.exposureId()), response, at, at.plus(Duration.ofDays(7))));
        return result;
    }

    private CommandResult replay(long owner, GatewayUrlExposureStateCommand command, String fingerprint) {
        var stored = idempotency.find(SCOPE, scopeId(owner, command.exposureId()), command.idempotencyKey());
        if (stored.isEmpty()) return null;
        IdempotencyBO receipt = stored.orElseThrow();
        if (!fingerprint.equals(receipt.getPayloadSha256())) throw new GatewayAdminIdempotencyConflictException();
        try {
            Map<String, Object> response = receipt.getResponse();
            int status = ((Number) response.get("status")).intValue();
            if ((status != 200 && status != 202) || !(response.get("etag") instanceof String tag)) {
                throw GatewayUrlExposureException.controlUnavailable(null);
            }
            GatewayUrlExposureVO view = json.convertValue(response.get("view"), GatewayUrlExposureVO.class);
            if (view == null || !Long.toString(command.exposureId()).equals(view.getExposureId())) {
                throw GatewayUrlExposureException.controlUnavailable(null);
            }
            return new CommandResult(status, view, tag);
        } catch (GatewayUrlExposureException failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw GatewayUrlExposureException.controlUnavailable(corrupt);
        }
    }

    private GatewayUrlExposureBO owned(long owner, long exposureId) {
        return exposures.findOwned(owner, exposureId)
                .filter(row -> Objects.equals(row.getOwnerTenantId(), owner) && Objects.equals(row.getId(), exposureId))
                .orElseThrow(() -> new GatewayAdminNotFoundException("URL exposure is unavailable"));
    }

    private static long owner() {
        try {
            Long owner = EgonColaTenantIdProvider.currentTenantId();
            if (owner != null && owner > 0L) return owner;
        } catch (IllegalStateException invalid) {
            throw GatewayUrlExposureException.accessDenied();
        }
        throw GatewayUrlExposureException.accessDenied();
    }

    private static void authorize(AdminActor actor, String permission) {
        if (actor == null || !actor.oauthScopes().contains(permission)
                || (!actor.scopes().contains(permission) && !actor.scopes().contains("*"))) {
            throw GatewayUrlExposureException.accessDenied();
        }
    }

    private static void validateTag(String tag) {
        if (tag == null || tag.isBlank()) throw GatewayUrlExposureException.preconditionRequired();
        var match = STRONG_TAG.matcher(tag);
        if (!match.matches()) throw GatewayUrlExposureException.requestInvalid("If-Match");
        try {
            Long.parseLong(match.group(1));
            Long.parseLong(match.group(2));
        } catch (NumberFormatException overflow) {
            throw GatewayUrlExposureException.requestInvalid("If-Match");
        }
    }

    private static String etag(GatewayUrlExposureBO row) {
        return "\"exposure-" + row.getId() + "-v" + row.getVersion() + "\"";
    }

    private static String scopeId(long owner, long exposureId) {
        return owner + ":" + exposureId;
    }

    private String fingerprint(GatewayUrlExposureStateCommand command) {
        ObjectNode value = json.createObjectNode();
        value.put("exposureId", Long.toString(command.exposureId()));
        value.put("ifMatch", command.ifMatch());
        value.put("state", command.state().wireValue());
        value.put("changeReason", command.changeReason());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
        } catch (JsonProcessingException | NoSuchAlgorithmException unavailable) {
            throw GatewayUrlExposureException.controlUnavailable(unavailable);
        }
    }

    private static Map<String, Object> summary(GatewayUrlExposureBO row) {
        return Map.of("state", row.getDesiredState().wireValue(), "generation", Long.toString(row.getDesiredGeneration()),
                "version", Long.toString(row.getVersion()), "changeReason", row.getChangeReason() == null ? "" : row.getChangeReason());
    }

    private static boolean converged(GatewayUrlExposureVO view) {
        if (!"SYNCED".equals(view.getReconcileStatus()) || !Objects.equals(view.getDesiredGeneration(), view.getObservedGeneration())
                || !view.getDesiredState().wireValue().equals(view.getObservedState())) return false;
        if (view.getDesiredState() == GatewayUrlExposureStateEnum.OPEN) return true;
        return CLOSE_CONDITIONS.stream().allMatch(type -> view.getConditions().stream().anyMatch(condition ->
                type.equals(condition.getType()) && "True".equals(condition.getStatus())
                        && Objects.equals(view.getDesiredGeneration(), condition.getObservedGeneration())));
    }
}
