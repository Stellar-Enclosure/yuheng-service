package top.egon.cola.component.yuheng.edge.service.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshClaimResultBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshClaimResultBO.Outcome;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshOperationBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;

@Repository
@RequiredArgsConstructor
public class EdgeRefreshOperationRepository {
    private final EdgeOAuthStateDatabase database;
    private final EdgeRevocationIntentRepository revocations;
    private final EdgeOAuthStateProperties properties;

    /** Existing-operation handling precedes the IdP's current-generation eligibility check. */
    public Mono<EdgeRefreshClaimResultBO> inspect(UUID operationId, EdgeRefreshContextBO context, String refreshToken) {
        requireOperation(operationId, refreshToken);
        return database.execute(() -> database.transaction(() -> {
            database.lock(context);
            database.lockCredential(context.target(), EdgeOAuthStateDigests.sha256(refreshToken));
            return existing(operationId, context, refreshToken, database.now());
        }));
    }

    /** Called only after REFRESH_CONTEXT independently confirmed the current credential generation. */
    public Mono<EdgeRefreshClaimResultBO> claim(UUID operationId, EdgeRefreshContextBO context, String refreshToken) {
        requireOperation(operationId, refreshToken);
        return database.execute(() -> database.transaction(() -> {
            database.lock(context);
            String tokenDigest = EdgeOAuthStateDigests.sha256(refreshToken);
            database.lockCredential(context.target(), tokenDigest);
            Instant now = database.now();
            var existing = existing(operationId, context, refreshToken, now);
            if (existing.outcome() != Outcome.MISSING) return existing;
            if (revocations.denies(context, now) || !context.authorizationExpiresAt().isAfter(now.plusSeconds(1))) {
                return new EdgeRefreshClaimResultBO(Outcome.CLOSED, null);
            }
            Instant deadline = now.plus(properties.getOperationLease());
            if (deadline.isAfter(context.authorizationExpiresAt())) deadline = context.authorizationExpiresAt();
            UUID owner = UUID.randomUUID();
            String requestDigest = requestDigest(operationId, context, tokenDigest);
            var parameters = context(context).addValue("id", operationId).addValue("request", requestDigest)
                    .addValue("token", tokenDigest).addValue("owner", owner)
                    .addValue("now", EdgeOAuthStateDatabase.timestamp(now))
                    .addValue("deadline", EdgeOAuthStateDatabase.timestamp(deadline))
                    .addValue("retention", EdgeOAuthStateDatabase.timestamp(context.authorizationExpiresAt()
                            .plus(properties.getMaximumAccessLifetime()).plus(properties.getClockSkew())
                            .plus(properties.getReceiptRetention())));
            database.jdbc().update("""
                    insert into edge_oauth_refresh_operation
                      (operation_id, target_digest, request_digest, app_alias, registration_id, issuer_uri,
                       tenant_id, client_id, resource_uri, subject, authorization_id, authentication_jti,
                       browser_binding_digest, refresh_token_digest, refresh_generation,
                       authorization_expires_at, authentication_expires_at, status, lease_owner, lease_expires_at,
                       deadline_at, created_at, updated_at, retain_until)
                    values (:id, :targetDigest, :request, :appAlias, :registrationId, :issuer, :tenantId,
                       :clientId, :resource, :subject, :authorization, :authentication, :browser, :token,
                       :generation, :authorizationExpiry, :authenticationExpiry, 'IN_PROGRESS', :owner,
                       :deadline, :deadline, :now, :now, :retention)
                    """, parameters);
            revocations.arm(operationId, context, refreshToken, now);
            return new EdgeRefreshClaimResultBO(Outcome.ACQUIRED, new EdgeRefreshOperationBO(operationId, context,
                    requestDigest, tokenDigest, "IN_PROGRESS", owner, deadline, deadline, 0, null, null, null, 0));
        }));
    }

    private EdgeRefreshClaimResultBO existing(UUID operationId, EdgeRefreshContextBO context,
                                              String refreshToken, Instant now) {
        String tokenDigest = EdgeOAuthStateDigests.sha256(refreshToken);
        var sameId = read(operationId, false);
        if (sameId.isPresent()) {
            var operation = sameId.orElseThrow();
            if (!sameContext(operation.context(), context)
                    || !EdgeOAuthStateDigests.equal(operation.requestDigest(), requestDigest(operationId, context, tokenDigest))) {
                return new EdgeRefreshClaimResultBO(Outcome.CONFLICT, null);
            }
            if ("IN_PROGRESS".equals(operation.status()) && !operation.deadlineAt().isAfter(now)) {
                close(operation, "EDGE_REFRESH_LEASE_EXPIRED", now);
                return new EdgeRefreshClaimResultBO(Outcome.CLOSED, read(operationId, false).orElseThrow());
            }
            return new EdgeRefreshClaimResultBO(Outcome.EXISTING, operation);
        }
        var rows = database.jdbc().query("""
                select * from edge_oauth_refresh_operation
                 where target_digest = :target and refresh_token_digest = :token for update
                """, Map.of("target", context.target().digest(), "token", tokenDigest), (row, index) -> operation(row));
        if (rows.isEmpty()) return new EdgeRefreshClaimResultBO(Outcome.MISSING, null);
        var operation = rows.getFirst();
        if (!sameContext(operation.context(), context)) return new EdgeRefreshClaimResultBO(Outcome.CONFLICT, null);
        if ("IN_PROGRESS".equals(operation.status()) && operation.deadlineAt().isAfter(now)) {
            return new EdgeRefreshClaimResultBO(Outcome.IN_PROGRESS, null);
        }
        if ("IN_PROGRESS".equals(operation.status())) close(operation, "EDGE_REFRESH_LEASE_EXPIRED", now);
        revocations.replay(context, refreshToken, now);
        return new EdgeRefreshClaimResultBO(Outcome.REPLAY, null);
    }

    public Mono<Optional<EdgeRefreshOperationBO>> dispatch(EdgeRefreshOperationBO expected) {
        return database.execute(() -> database.transaction(() -> {
            database.lock(expected.context());
            database.lockCredential(expected.context().target(), expected.refreshTokenDigest());
            Instant now = database.now();
            var current = read(expected.operationId(), true);
            if (current.isEmpty() || !owned(current.orElseThrow(), expected)) return Optional.empty();
            var operation = current.orElseThrow();
            if (!operation.deadlineAt().isAfter(now) || !operation.leaseExpiresAt().isAfter(now)
                    || revocations.denies(operation.context(), now)) {
                close(operation, "EDGE_REFRESH_CONTEXT_CLOSED", now);
                return Optional.empty();
            }
            if (operation.upstreamAttempts() != 0) return Optional.empty();
            database.jdbc().update("""
                    update edge_oauth_refresh_operation set upstream_attempts = 1, upstream_started_at = :now,
                       updated_at = :now, version = version + 1
                     where operation_id = :id and version = :version and upstream_attempts = 0
                    """, Map.of("id", operation.operationId(), "version", operation.version(),
                    "now", EdgeOAuthStateDatabase.timestamp(now)));
            return read(operation.operationId(), false);
        }));
    }

    public Mono<Boolean> complete(EdgeRefreshOperationBO expected, long successorGeneration, Instant accessExpiresAt) {
        if (successorGeneration != expected.context().generation() + 1 || accessExpiresAt == null
                || accessExpiresAt.isAfter(expected.context().authorizationExpiresAt())) {
            throw new IllegalArgumentException("Invalid refresh successor");
        }
        return database.execute(() -> database.transaction(() -> {
            database.lock(expected.context());
            database.lockCredential(expected.context().target(), expected.refreshTokenDigest());
            Instant now = database.now();
            var current = read(expected.operationId(), true);
            if (current.isEmpty() || !owned(current.orElseThrow(), expected)) return false;
            var operation = current.orElseThrow();
            if (operation.upstreamAttempts() != 1 || !operation.deadlineAt().isAfter(now) || !operation.leaseExpiresAt().isAfter(now)
                    || !accessExpiresAt.isAfter(now) || revocations.denies(operation.context(), now)) {
                close(operation, "EDGE_REFRESH_RESPONSE_UNCONFIRMED", now);
                return false;
            }
            if (!revocations.disarm(operation.operationId(), now)) {
                throw new EdgeOAuthStateException("EDGE_REFRESH_RECOVERY_INTENT_INVALID");
            }
            return database.jdbc().update("""
                    update edge_oauth_refresh_operation set status = 'SUCCEEDED', upstream_completed_at = :now,
                       completed_at = :now, updated_at = :now, access_expires_at = :accessExpiry,
                       successor_generation = :successor, lease_owner = null, lease_expires_at = null,
                       version = version + 1 where operation_id = :id and version = :version
                    """, Map.of("id", operation.operationId(), "version", operation.version(),
                    "now", EdgeOAuthStateDatabase.timestamp(now), "accessExpiry", EdgeOAuthStateDatabase.timestamp(accessExpiresAt),
                    "successor", successorGeneration)) == 1;
        }));
    }

    public Mono<Boolean> closeUnknown(EdgeRefreshOperationBO expected, String failureCode) {
        EdgeOAuthTransactionRepository.requireFailureCode(failureCode);
        return database.execute(() -> database.transaction(() -> {
            database.lock(expected.context());
            database.lockCredential(expected.context().target(), expected.refreshTokenDigest());
            var current = read(expected.operationId(), true);
            if (current.isEmpty() || !owned(current.orElseThrow(), expected)) return false;
            close(current.orElseThrow(), failureCode, database.now());
            return true;
        }));
    }

    public Mono<Optional<EdgeRefreshOperationBO>> status(UUID id, EdgeRefreshContextBO context) {
        return database.execute(() -> database.transaction(() -> read(id, false)
                .filter(operation -> sameContext(operation.context(), context))));
    }

    /** Read-only proof of a prior operation, including a spent credential; cannot enable another dispatch. */
    public Mono<Boolean> hasBoundCredential(EdgeRefreshContextBO context, String refreshTokenDigest) {
        EdgeOAuthStateDigests.requireDigest(refreshTokenDigest);
        return database.execute(() -> database.transaction(() -> database.jdbc().query("""
                select * from edge_oauth_refresh_operation where target_digest = :target and refresh_token_digest = :token
                """, Map.of("target", context.target().digest(), "token", refreshTokenDigest),
                (row, index) -> operation(row)).stream().anyMatch(operation -> sameContext(operation.context(), context)
                    && operation.context().generation() == context.generation())));
    }

    public Mono<Integer> recoverExpired(int batchSize) {
        EdgeOAuthTransactionRepository.requireBatchSize(batchSize);
        return database.execute(() -> {
            var ids = database.jdbc().query("""
                    select operation_id from edge_oauth_refresh_operation
                     where status = 'IN_PROGRESS' and lease_expires_at <= clock_timestamp()
                     order by lease_expires_at limit :batch
                    """, Map.of("batch", batchSize), (row, index) -> row.getObject(1, UUID.class));
            int count = 0;
            for (UUID id : ids) {
                Boolean changed = database.transaction(() -> {
                    var snapshot = read(id, false);
                    if (snapshot.isEmpty()) return false;
                    database.lock(snapshot.orElseThrow().context());
                    database.lockCredential(snapshot.orElseThrow().context().target(), snapshot.orElseThrow().refreshTokenDigest());
                    var current = read(id, true);
                    Instant now = database.now();
                    if (current.isEmpty() || !"IN_PROGRESS".equals(current.orElseThrow().status())
                            || current.orElseThrow().leaseExpiresAt().isAfter(now)) return false;
                    close(current.orElseThrow(), "EDGE_REFRESH_LEASE_EXPIRED", now);
                    return true;
                });
                if (Boolean.TRUE.equals(changed)) count++;
            }
            return count;
        });
    }

    public Mono<Integer> purgeExpired(int batchSize) {
        EdgeOAuthTransactionRepository.requireBatchSize(batchSize);
        return database.execute(() -> database.transaction(() -> database.jdbc().update("""
                delete from edge_oauth_refresh_operation where operation_id in (
                  select o.operation_id from edge_oauth_refresh_operation o
                   where status <> 'IN_PROGRESS' and retain_until < clock_timestamp()
                     and not exists (select 1 from edge_oauth_revocation_intent i where i.source_refresh_operation_id = o.operation_id)
                   order by retain_until limit :batch for update skip locked)
                """, Map.of("batch", batchSize))));
    }

    private void close(EdgeRefreshOperationBO operation, String failure, Instant now) {
        EdgeOAuthStateDatabase.requireTransaction();
        if (!revocations.activate(operation.operationId(), failure, now)) {
            var rows = database.jdbc().queryForList("""
                    select intent_id from edge_oauth_revocation_intent
                     where source_refresh_operation_id = :id and status in ('PENDING','IN_PROGRESS','SUCCEEDED','DEAD_LETTER')
                    """, Map.of("id", operation.operationId()));
            if (rows.size() != 1) throw new EdgeOAuthStateException("EDGE_REFRESH_RECOVERY_INTENT_INVALID");
        }
        database.jdbc().update("""
                update edge_oauth_refresh_operation set status = 'CLOSED', completed_at = :now, closed_at = :now,
                   updated_at = :now, failure_code = :failure, lease_owner = null, lease_expires_at = null,
                   version = version + 1 where operation_id = :id and status = 'IN_PROGRESS'
                """, Map.of("id", operation.operationId(), "failure", failure,
                "now", EdgeOAuthStateDatabase.timestamp(now)));
    }

    private Optional<EdgeRefreshOperationBO> read(UUID id, boolean lock) {
        return database.jdbc().query("select * from edge_oauth_refresh_operation where operation_id = :id"
                        + (lock ? " for update" : ""), Map.of("id", id), (row, index) -> operation(row)).stream().findFirst();
    }

    private static boolean owned(EdgeRefreshOperationBO actual, EdgeRefreshOperationBO expected) {
        return "IN_PROGRESS".equals(actual.status()) && actual.version() == expected.version()
                && actual.leaseOwner().equals(expected.leaseOwner()) && sameContext(actual.context(), expected.context())
                && EdgeOAuthStateDigests.equal(actual.requestDigest(), expected.requestDigest());
    }

    private static boolean sameContext(EdgeRefreshContextBO left, EdgeRefreshContextBO right) {
        return left.target().equals(right.target()) && left.subject().equals(right.subject())
                && left.authorizationId().equals(right.authorizationId()) && left.authenticationJti().equals(right.authenticationJti())
                && left.authorizationExpiresAt().equals(right.authorizationExpiresAt())
                && left.authenticationExpiresAt().equals(right.authenticationExpiresAt())
                && EdgeOAuthStateDigests.equal(left.browserBindingDigest(), right.browserBindingDigest());
    }

    private static String requestDigest(UUID id, EdgeRefreshContextBO context, String tokenDigest) {
        return EdgeOAuthStateDigests.fields("edge-refresh-operation-v1", id.toString(), context.target().digest(),
                context.subject(), context.authorizationId(), context.authenticationJti(), Long.toString(context.generation()),
                Long.toString(context.authorizationExpiresAt().getEpochSecond()), context.browserBindingDigest(), tokenDigest);
    }

    private static MapSqlParameterSource context(EdgeRefreshContextBO context) {
        return EdgeOAuthStateDatabase.target(context.target()).addValue("subject", context.subject())
                .addValue("authorization", context.authorizationId()).addValue("authentication", context.authenticationJti())
                .addValue("browser", context.browserBindingDigest()).addValue("generation", context.generation())
                .addValue("authorizationExpiry", EdgeOAuthStateDatabase.timestamp(context.authorizationExpiresAt()))
                .addValue("authenticationExpiry", EdgeOAuthStateDatabase.timestamp(context.authenticationExpiresAt()));
    }

    private static EdgeRefreshOperationBO operation(ResultSet row) throws SQLException {
        var context = new EdgeRefreshContextBO(EdgeOAuthStateDatabase.target(row), row.getString("subject"),
                row.getString("authorization_id"), row.getString("authentication_jti"), row.getLong("refresh_generation"),
                EdgeOAuthStateDatabase.instant(row, "authorization_expires_at"),
                EdgeOAuthStateDatabase.instant(row, "authentication_expires_at"), row.getString("browser_binding_digest"));
        return new EdgeRefreshOperationBO(row.getObject("operation_id", UUID.class), context, row.getString("request_digest"),
                row.getString("refresh_token_digest"), row.getString("status"), row.getObject("lease_owner", UUID.class),
                EdgeOAuthStateDatabase.instant(row, "lease_expires_at"), EdgeOAuthStateDatabase.instant(row, "deadline_at"),
                row.getInt("upstream_attempts"), EdgeOAuthStateDatabase.instant(row, "access_expires_at"),
                row.getObject("successor_generation", Long.class), row.getString("failure_code"), row.getLong("version"));
    }

    private static void requireOperation(UUID id, String refreshToken) {
        if (id == null || refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 8192) {
            throw new IllegalArgumentException("Invalid refresh operation");
        }
    }
}
