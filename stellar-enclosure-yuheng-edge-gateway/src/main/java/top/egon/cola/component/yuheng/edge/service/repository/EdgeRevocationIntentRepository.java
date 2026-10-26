package top.egon.cola.component.yuheng.edge.service.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRevocationClaimBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRevocationIntentBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher.Purpose;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher.SealedSecret;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;

@Repository
@RequiredArgsConstructor
public class EdgeRevocationIntentRepository {
    private final EdgeOAuthStateDatabase database;
    private final EdgeOAuthStateCipher cipher;
    private final EdgeOAuthStateProperties properties;

    public Mono<EdgeRevocationIntentBO> revoke(EdgeRefreshContextBO context, String refreshToken,
                                               String sourceKind, String reasonCode) {
        if (!List.of("LOGOUT", "ACCOUNT_SWITCH", "REPLAY").contains(sourceKind)) {
            throw new IllegalArgumentException("Invalid revocation source");
        }
        EdgeOAuthTransactionRepository.requireFailureCode(reasonCode);
        return database.execute(() -> database.transaction(() -> {
            database.lock(context);
            database.lockCredential(context.target(), EdgeOAuthStateDigests.sha256(refreshToken));
            return insert(context.target(), context.subject(), context.authorizationId(), context.authenticationJti(),
                    context.browserBindingDigest(), refreshToken, context.authorizationExpiresAt(), sourceKind,
                    null, null, "PENDING", reasonCode, database.now());
        }));
    }

    public Mono<EdgeRevocationIntentBO> revokeUnconfirmedExchange(UUID transactionId, EdgeOAuthTargetBO target,
                                                                  String browserBindingDigest, String refreshToken) {
        return revokeUnconfirmedExchange(transactionId, target, browserBindingDigest, refreshToken, null);
    }

    public Mono<EdgeRevocationIntentBO> revokeUnconfirmedExchange(UUID transactionId, EdgeRefreshContextBO context, String refreshToken) {
        return revokeUnconfirmedExchange(transactionId, context.target(), context.browserBindingDigest(), refreshToken, context);
    }

    private Mono<EdgeRevocationIntentBO> revokeUnconfirmedExchange(UUID transactionId, EdgeOAuthTargetBO target,
                                                                   String browserBindingDigest, String refreshToken, EdgeRefreshContextBO context) {
        EdgeOAuthStateDigests.requireDigest(browserBindingDigest);
        return database.execute(() -> database.transaction(() -> {
            if (context != null) database.lock(context);
            database.lockCredential(target, EdgeOAuthStateDigests.sha256(refreshToken));
            var bound = database.jdbc().queryForList("""
                    select transaction_id from edge_oauth_transaction
                     where transaction_id = :id and target_digest = :target and browser_binding_digest = :browser
                    """, Map.of("id", transactionId, "target", target.digest(), "browser", browserBindingDigest));
            if (bound.size() != 1) throw new EdgeOAuthStateException("EDGE_OAUTH_TRANSACTION_INVALID");
            return insert(target, context == null ? null : context.subject(), context == null ? null : context.authorizationId(),
                    context == null ? null : context.authenticationJti(), browserBindingDigest, refreshToken,
                    context == null ? null : context.authorizationExpiresAt(),
                    "CODE_RECOVERY", null, transactionId, "PENDING", "EDGE_CODE_RESPONSE_UNCONFIRMED", database.now());
        }));
    }

    EdgeRevocationIntentBO arm(UUID operationId, EdgeRefreshContextBO context, String refreshToken, Instant now) {
        EdgeOAuthStateDatabase.requireTransaction();
        return insert(context.target(), context.subject(), context.authorizationId(), context.authenticationJti(),
                context.browserBindingDigest(), refreshToken, context.authorizationExpiresAt(), "REFRESH_RECOVERY",
                operationId, null, "ARMED", "EDGE_REFRESH_RECOVERY", now);
    }

    void replay(EdgeRefreshContextBO context, String refreshToken, Instant now) {
        EdgeOAuthStateDatabase.requireTransaction();
        insert(context.target(), context.subject(), context.authorizationId(), context.authenticationJti(),
                context.browserBindingDigest(), refreshToken, context.authorizationExpiresAt(), "REPLAY",
                null, null, "PENDING", "EDGE_REFRESH_REPLAY", now);
    }

    boolean activate(UUID operationId, String reason, Instant now) {
        EdgeOAuthStateDatabase.requireTransaction();
        EdgeOAuthTransactionRepository.requireFailureCode(reason);
        return database.jdbc().update("""
                update edge_oauth_revocation_intent set status = 'PENDING', reason_code = :reason,
                   next_attempt_at = :now, updated_at = :now, version = version + 1
                 where source_refresh_operation_id = :operation and status = 'ARMED'
                """, Map.of("operation", operationId, "reason", reason,
                "now", EdgeOAuthStateDatabase.timestamp(now))) == 1;
    }

    boolean disarm(UUID operationId, Instant now) {
        EdgeOAuthStateDatabase.requireTransaction();
        return database.jdbc().update("""
                update edge_oauth_revocation_intent set status = 'DISARMED', terminal_at = :now,
                   updated_at = :now, version = version + 1, token_ciphertext = null,
                   encryption_key_id = null, encryption_version = null
                 where source_refresh_operation_id = :operation and status = 'ARMED'
                """, Map.of("operation", operationId, "now", EdgeOAuthStateDatabase.timestamp(now))) == 1;
    }

    boolean denies(EdgeRefreshContextBO context, Instant now) {
        return denies(context.target(), context.authorizationId(), now);
    }

    private boolean denies(EdgeOAuthTargetBO target, String authorizationId, Instant now) {
        EdgeOAuthStateDatabase.requireTransaction();
        Boolean closed = database.jdbc().queryForObject("""
                select exists (
                  select 1 from edge_oauth_revocation_intent
                   where target_digest = :target and authorization_id = :authorization
                     and status in ('PENDING','IN_PROGRESS','SUCCEEDED','DEAD_LETTER') and deny_until > :now)
                or exists (
                  select 1 from edge_oauth_refresh_operation
                   where target_digest = :target and authorization_id = :authorization
                     and (status in ('UNKNOWN','CLOSED')
                       or (status = 'IN_PROGRESS' and lease_expires_at <= :now)))
                """, Map.of("target", target.digest(), "authorization", authorizationId,
                "now", EdgeOAuthStateDatabase.timestamp(now)), Boolean.class);
        return Boolean.TRUE.equals(closed);
    }

    public Mono<Boolean> isClosed(EdgeRefreshContextBO context) {
        return database.execute(() -> database.transaction(() -> denies(context, database.now())));
    }

    public Mono<Boolean> isClosed(EdgeOAuthTargetBO target, String authorizationId) {
        return database.execute(() -> database.transaction(() -> denies(target, authorizationId, database.now())));
    }

    private EdgeRevocationIntentBO insert(EdgeOAuthTargetBO target, String subject, String authorizationId,
                                           String authenticationJti, String browserBindingDigest, String refreshToken,
                                           Instant verifiedExpiry, String sourceKind, UUID refreshOperationId,
                                           UUID transactionId, String status, String reason, Instant now) {
        EdgeOAuthStateDatabase.requireTransaction();
        String tokenDigest = EdgeOAuthStateDigests.sha256(refreshToken);
        String source = refreshOperationId != null ? refreshOperationId.toString()
                : transactionId != null ? transactionId.toString() : "";
        String idempotency = EdgeOAuthStateDigests.fields("edge-revocation-v1", target.digest(), sourceKind, source, tokenDigest);
        String requestDigest = EdgeOAuthStateDigests.fields(idempotency, browserBindingDigest,
                authorizationId == null ? "" : authorizationId, authenticationJti == null ? "" : authenticationJti);
        var existing = database.jdbc().query("""
                select * from edge_oauth_revocation_intent where target_digest = :target and idempotency_digest = :idempotency
                """, Map.of("target", target.digest(), "idempotency", idempotency), (row, index) -> intent(row));
        if (!existing.isEmpty()) {
            var result = existing.getFirst();
            if (!result.target().equals(target) || !EdgeOAuthStateDigests.equal(result.requestDigest(), requestDigest)) {
                throw new EdgeOAuthStateException("EDGE_REVOCATION_CONFLICT");
            }
            return result;
        }
        UUID id = UUID.randomUUID();
        Instant authorizationBound = verifiedExpiry == null ? now.plus(properties.getMaximumAuthorizationLifetime()) : verifiedExpiry;
        Instant deadline = authorizationBound.plus(properties.getMaximumAccessLifetime()).plus(properties.getClockSkew());
        if (!deadline.isAfter(now)) throw new EdgeOAuthStateException("EDGE_AUTHORIZATION_EXPIRED");
        Instant retention = deadline.plus(properties.getReceiptRetention());
        SealedSecret secret = cipher.seal(refreshToken, Purpose.REVOCATION, binding(id, target, tokenDigest));
        var parameters = EdgeOAuthStateDatabase.target(target).addValue("id", id).addValue("idempotency", idempotency)
                .addValue("requestDigest", requestDigest).addValue("sourceKind", sourceKind)
                .addValue("refreshOperation", refreshOperationId).addValue("transaction", transactionId)
                .addValue("subject", subject).addValue("authorization", authorizationId).addValue("authentication", authenticationJti)
                .addValue("browser", browserBindingDigest).addValue("tokenDigest", tokenDigest)
                .addValue("keyId", secret.keyId()).addValue("encryptionVersion", secret.version()).addValue("ciphertext", secret.ciphertext())
                .addValue("status", status).addValue("reason", reason)
                .addValue("nextAttempt", "PENDING".equals(status) ? EdgeOAuthStateDatabase.timestamp(now) : null)
                .addValue("expiry", EdgeOAuthStateDatabase.timestamp(verifiedExpiry))
                .addValue("expirySource", verifiedExpiry == null ? "CONFIGURED_UPPER_BOUND" : "VERIFIED_CREDENTIAL")
                .addValue("deadline", EdgeOAuthStateDatabase.timestamp(deadline))
                .addValue("retention", EdgeOAuthStateDatabase.timestamp(retention))
                .addValue("now", EdgeOAuthStateDatabase.timestamp(now));
        database.jdbc().update("""
                insert into edge_oauth_revocation_intent
                  (intent_id, idempotency_digest, target_digest, request_digest, source_kind,
                   source_refresh_operation_id, source_transaction_id, app_alias, registration_id, issuer_uri,
                   tenant_id, client_id, resource_uri, subject, authorization_id, authentication_jti,
                   browser_binding_digest, token_digest, encryption_key_id, encryption_version, token_ciphertext,
                   status, reason_code, next_attempt_at, authorization_expires_at, expiry_source,
                   dispatch_deadline_at, deny_until, created_at, updated_at, retain_until)
                values (:id, :idempotency, :targetDigest, :requestDigest, :sourceKind, :refreshOperation,
                   :transaction, :appAlias, :registrationId, :issuer, :tenantId, :clientId, :resource,
                   :subject, :authorization, :authentication, :browser, :tokenDigest, :keyId, :encryptionVersion,
                   :ciphertext, :status, :reason, :nextAttempt, :expiry, :expirySource, :deadline, :deadline,
                   :now, :now, :retention)
                """, parameters);
        return new EdgeRevocationIntentBO(id, target, requestDigest, tokenDigest, sourceKind, authorizationId,
                authenticationJti, browserBindingDigest, status, reason, 0, null, null, deadline, deadline, 0);
    }

    public Mono<List<EdgeRevocationClaimBO>> claimDue(int batchSize) {
        EdgeOAuthTransactionRepository.requireBatchSize(batchSize);
        return database.execute(() -> database.transaction(() -> {
            Instant now = database.now();
            List<StoredIntent> rows = database.jdbc().query("""
                    select * from edge_oauth_revocation_intent
                     where (status = 'PENDING' and next_attempt_at <= :now)
                        or (status = 'IN_PROGRESS' and lease_expires_at <= :now)
                     order by created_at limit :batch for update skip locked
                    """, Map.of("now", EdgeOAuthStateDatabase.timestamp(now), "batch", batchSize),
                    (row, index) -> new StoredIntent(intent(row), new SealedSecret(row.getString("encryption_key_id"),
                            row.getInt("encryption_version"), row.getBytes("token_ciphertext"))));
            List<EdgeRevocationClaimBO> claims = new ArrayList<>();
            for (StoredIntent stored : rows) {
                var intent = stored.intent();
                if (!intent.dispatchDeadlineAt().isAfter(now)) {
                    deadLetter(intent.intentId(), "EDGE_REVOCATION_DEADLINE", now);
                    continue;
                }
                String token;
                try {
                    token = cipher.open(stored.secret(), Purpose.REVOCATION,
                            binding(intent.intentId(), intent.target(), intent.tokenDigest()));
                } catch (EdgeOAuthStateException secretFailure) {
                    deadLetter(intent.intentId(), secretFailure.getCode(), now);
                    continue;
                }
                UUID owner = UUID.randomUUID();
                Instant lease = now.plus(properties.getOperationLease());
                if (lease.isAfter(intent.dispatchDeadlineAt())) lease = intent.dispatchDeadlineAt();
                database.jdbc().update("""
                        update edge_oauth_revocation_intent set status = 'IN_PROGRESS', lease_owner = :owner,
                           lease_expires_at = :lease, attempts = attempts + 1,
                           first_dispatched_at = coalesce(first_dispatched_at, :now), last_dispatched_at = :now,
                           updated_at = :now, version = version + 1
                         where intent_id = :id and version = :version
                        """, Map.of("id", intent.intentId(), "version", intent.version(), "owner", owner,
                        "lease", EdgeOAuthStateDatabase.timestamp(lease), "now", EdgeOAuthStateDatabase.timestamp(now)));
                claims.add(new EdgeRevocationClaimBO(new EdgeRevocationIntentBO(intent.intentId(), intent.target(),
                        intent.requestDigest(), intent.tokenDigest(), intent.sourceKind(), intent.authorizationId(),
                        intent.authenticationJti(), intent.browserBindingDigest(), "IN_PROGRESS", intent.reasonCode(),
                        intent.attempts() + 1, owner, lease, intent.dispatchDeadlineAt(), intent.denyUntil(), intent.version() + 1), token));
            }
            return List.copyOf(claims);
        }));
    }

    public Mono<Boolean> confirm(EdgeRevocationClaimBO claim) {
        return database.execute(() -> database.transaction(() -> {
            Instant now = database.now();
            var intent = currentClaim(claim.intent(), now);
            if (intent == null) return false;
            var parameters = new MapSqlParameterSource().addValue("id", intent.intentId())
                    .addValue("receipt", UUID.randomUUID()).addValue("target", intent.target().digest())
                    .addValue("request", intent.requestDigest()).addValue("token", intent.tokenDigest())
                    .addValue("attempt", intent.attempts()).addValue("now", EdgeOAuthStateDatabase.timestamp(now))
                    .addValue("retention", EdgeOAuthStateDatabase.timestamp(intent.denyUntil().plus(properties.getReceiptRetention())));
            database.jdbc().update("""
                    insert into edge_oauth_revocation_receipt
                     (receipt_id, intent_id, target_digest, request_digest, token_digest, attempt_number,
                      http_status, confirmed_at, created_at, retain_until)
                    values (:receipt, :id, :target, :request, :token, :attempt, 200, :now, :now, :retention)
                    """, parameters);
            database.jdbc().update("""
                    update edge_oauth_revocation_intent set status = 'SUCCEEDED', confirmed_at = :now,
                       terminal_at = :now, updated_at = :now, last_http_status = 200, failure_code = null,
                       lease_owner = null, lease_expires_at = null, next_attempt_at = null,
                       token_ciphertext = null, encryption_key_id = null, encryption_version = null, version = version + 1
                     where intent_id = :id
                    """, parameters);
            return true;
        }));
    }

    public Mono<Boolean> retry(EdgeRevocationClaimBO claim, String failureCode, Integer httpStatus, boolean permanent) {
        EdgeOAuthTransactionRepository.requireFailureCode(failureCode);
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) throw new IllegalArgumentException("Invalid HTTP status");
        return database.execute(() -> database.transaction(() -> {
            Instant now = database.now();
            var intent = currentClaim(claim.intent(), now);
            if (intent == null) return false;
            if (permanent || !intent.dispatchDeadlineAt().isAfter(now.plusSeconds(1))) {
                deadLetter(intent.intentId(), failureCode, now);
                return true;
            }
            Instant retry = now.plusSeconds(Math.min(60, 1L << Math.min(intent.attempts(), 6)));
            if (retry.isAfter(intent.dispatchDeadlineAt())) retry = intent.dispatchDeadlineAt();
            database.jdbc().update("""
                    update edge_oauth_revocation_intent set status = 'PENDING', next_attempt_at = :retry,
                       failure_code = :failure, last_http_status = :httpStatus, lease_owner = null,
                       lease_expires_at = null, updated_at = :now, version = version + 1 where intent_id = :id
                    """, new MapSqlParameterSource().addValue("id", intent.intentId()).addValue("failure", failureCode)
                    .addValue("httpStatus", httpStatus).addValue("retry", EdgeOAuthStateDatabase.timestamp(retry))
                    .addValue("now", EdgeOAuthStateDatabase.timestamp(now)));
            return true;
        }));
    }

    private EdgeRevocationIntentBO currentClaim(EdgeRevocationIntentBO expected, Instant now) {
        var rows = database.jdbc().query("select * from edge_oauth_revocation_intent where intent_id = :id for update",
                Map.of("id", expected.intentId()), (row, index) -> intent(row));
        if (rows.size() != 1) return null;
        var current = rows.getFirst();
        return "IN_PROGRESS".equals(current.status()) && current.version() == expected.version()
                && current.target().equals(expected.target()) && current.leaseOwner().equals(expected.leaseOwner())
                && current.leaseExpiresAt().isAfter(now)
                && EdgeOAuthStateDigests.equal(current.requestDigest(), expected.requestDigest())
                && EdgeOAuthStateDigests.equal(current.tokenDigest(), expected.tokenDigest()) ? current : null;
    }

    private void deadLetter(UUID id, String failure, Instant now) {
        database.jdbc().update("""
                update edge_oauth_revocation_intent set status = 'DEAD_LETTER', terminal_at = :now,
                   failure_code = :failure, updated_at = :now, lease_owner = null, lease_expires_at = null,
                   next_attempt_at = null, token_ciphertext = null, encryption_key_id = null,
                   encryption_version = null, version = version + 1 where intent_id = :id
                """, Map.of("id", id, "failure", failure, "now", EdgeOAuthStateDatabase.timestamp(now)));
    }

    public Mono<Integer> purgeExpired(int batchSize) {
        EdgeOAuthTransactionRepository.requireBatchSize(batchSize);
        return database.execute(() -> database.transaction(() -> {
            int receipts = database.jdbc().update("""
                    delete from edge_oauth_revocation_receipt where receipt_id in (
                      select receipt_id from edge_oauth_revocation_receipt where retain_until < clock_timestamp()
                      order by retain_until limit :batch for update skip locked)
                    """, Map.of("batch", batchSize));
            int intents = database.jdbc().update("""
                    delete from edge_oauth_revocation_intent where intent_id in (
                      select i.intent_id from edge_oauth_revocation_intent i
                       where status in ('SUCCEEDED','DISARMED','DEAD_LETTER') and retain_until < clock_timestamp()
                         and not exists (select 1 from edge_oauth_revocation_receipt r where r.intent_id = i.intent_id)
                       order by retain_until limit :batch for update skip locked)
                    """, Map.of("batch", batchSize));
            return receipts + intents;
        }));
    }

    private static String binding(UUID id, EdgeOAuthTargetBO target, String digest) {
        return EdgeOAuthStateDigests.fields(id.toString(), target.digest(), digest);
    }

    private static EdgeRevocationIntentBO intent(ResultSet row) throws SQLException {
        return new EdgeRevocationIntentBO(row.getObject("intent_id", UUID.class), EdgeOAuthStateDatabase.target(row),
                row.getString("request_digest"), row.getString("token_digest"), row.getString("source_kind"),
                row.getString("authorization_id"), row.getString("authentication_jti"), row.getString("browser_binding_digest"),
                row.getString("status"), row.getString("reason_code"), row.getInt("attempts"),
                row.getObject("lease_owner", UUID.class), EdgeOAuthStateDatabase.instant(row, "lease_expires_at"),
                EdgeOAuthStateDatabase.instant(row, "dispatch_deadline_at"), EdgeOAuthStateDatabase.instant(row, "deny_until"),
                row.getLong("version"));
    }

    private record StoredIntent(EdgeRevocationIntentBO intent, SealedSecret secret) { }
}
