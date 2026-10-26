package top.egon.cola.component.yuheng.edge.service.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.EdgeOAuthStateException;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthExchangeClaimBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTransactionBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher.Purpose;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher.SealedSecret;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;

@Repository
@RequiredArgsConstructor
public class EdgeOAuthTransactionRepository {
    private static final String REAUTHENTICATION_NONCE_PREFIX = "v1:reauthenticate:";
    private final EdgeOAuthStateDatabase database;
    private final EdgeOAuthStateCipher cipher;
    private final EdgeOAuthStateProperties properties;

    public Mono<EdgeOAuthTransactionBO> create(EdgeOAuthTargetBO target, String redirectUri, String returnPath,
                                               List<String> scopes, String state, String browserBindingDigest,
                                               String verifier, String nonce, boolean reauthenticate) {
        EdgeOAuthTargetBO.requireCanonicalHttps(redirectUri);
        requireRandomValue(state);
        requireRandomValue(verifier);
        requireRandomValue(nonce);
        EdgeOAuthStateDigests.requireDigest(browserBindingDigest);
        if (!safeReturnPath(returnPath) || scopes == null || scopes.isEmpty() || scopes.size() > 64
                || scopes.stream().anyMatch(value -> value == null || !value.matches("[A-Za-z0-9:._-]{1,128}"))) {
            throw new IllegalArgumentException("Invalid EDGE OAuth request");
        }
        List<String> requestedScopes = scopes.stream().distinct().sorted().toList();
        return database.execute(() -> database.transaction(() -> {
            UUID id = UUID.randomUUID();
            Instant now = database.now();
            Instant expiry = now.plus(properties.getInteractionLifetime());
            String context = binding(id, target);
            SealedSecret verifierSecret = cipher.seal(verifier, Purpose.PKCE_VERIFIER, context);
            // The existing one-use encrypted nonce payload also preserves this request's OIDC freshness options.
            SealedSecret nonceSecret = cipher.seal(reauthenticate ? REAUTHENTICATION_NONCE_PREFIX + nonce : nonce, Purpose.OIDC_NONCE, context);
            String stateDigest = EdgeOAuthStateDigests.sha256(state);
            String nonceDigest = EdgeOAuthStateDigests.sha256(nonce);
            String challenge = challenge(verifier);
            var parameters = EdgeOAuthStateDatabase.target(target)
                    .addValue("id", id).addValue("redirect", redirectUri).addValue("returnPath", returnPath)
                    .addValue("scopes", new SqlArrayValue("text", requestedScopes.toArray()))
                    .addValue("state", stateDigest).addValue("browser", browserBindingDigest)
                    .addValue("nonce", nonceDigest).addValue("challenge", challenge)
                    .addValue("keyId", verifierSecret.keyId()).addValue("encryptionVersion", verifierSecret.version())
                    .addValue("verifierCiphertext", verifierSecret.ciphertext()).addValue("nonceCiphertext", nonceSecret.ciphertext())
                    .addValue("now", EdgeOAuthStateDatabase.timestamp(now))
                    .addValue("expiry", EdgeOAuthStateDatabase.timestamp(expiry))
                    .addValue("retention", EdgeOAuthStateDatabase.timestamp(expiry.plus(properties.getDiagnosticRetention())));
            database.jdbc().update("""
                    insert into edge_oauth_transaction
                      (transaction_id, target_digest, app_alias, registration_id, issuer_uri, tenant_id,
                       client_id, resource_uri, redirect_uri, return_path, requested_scopes, state_digest,
                       browser_binding_digest, nonce_digest, code_challenge, encryption_key_id, encryption_version,
                       verifier_ciphertext, nonce_ciphertext, status, created_at, updated_at, expires_at, retain_until)
                    values (:id, :targetDigest, :appAlias, :registrationId, :issuer, :tenantId,
                       :clientId, :resource, :redirect, :returnPath, :scopes, :state, :browser,
                       :nonce, :challenge, :keyId, :encryptionVersion, :verifierCiphertext, :nonceCiphertext,
                       'PENDING', :now, :now, :expiry, :retention)
                    """, parameters);
            return new EdgeOAuthTransactionBO(id, target, redirectUri, returnPath, requestedScopes,
                    stateDigest, browserBindingDigest, nonceDigest, challenge, "PENDING", expiry, 0);
        }));
    }

    public Mono<EdgeOAuthExchangeClaimBO> claim(EdgeOAuthTargetBO target, String state,
                                                String browserBindingDigest, String authorizationCode) {
        requireRandomValue(state);
        EdgeOAuthStateDigests.requireDigest(browserBindingDigest);
        if (authorizationCode == null || authorizationCode.isBlank() || authorizationCode.length() > 8192) {
            return Mono.error(new EdgeOAuthStateException("EDGE_OAUTH_CALLBACK_INVALID"));
        }
        return database.execute(() -> database.transaction(() -> {
            var parameters = Map.of("target", target.digest(), "state", EdgeOAuthStateDigests.sha256(state));
            List<PendingTransaction> rows = database.jdbc().query("""
                    select * from edge_oauth_transaction
                     where target_digest = :target and state_digest = :state for update
                    """, parameters, (row, index) -> pending(row));
            if (rows.size() != 1) return new ClaimResult(null, "EDGE_OAUTH_TRANSACTION_INVALID");
            PendingTransaction stored = rows.getFirst();
            EdgeOAuthTransactionBO transaction = stored.transaction();
            if (!transaction.target().equals(target)
                    || !EdgeOAuthStateDigests.equal(transaction.browserBindingDigest(), browserBindingDigest)
                    || !"PENDING".equals(transaction.status())) {
                return new ClaimResult(null, "EDGE_OAUTH_TRANSACTION_INVALID");
            }
            Instant now = database.now();
            if (!transaction.expiresAt().isAfter(now.plus(properties.getOperationLease()))) {
                close(transaction.transactionId(), "EXPIRED", "EDGE_OAUTH_TRANSACTION_EXPIRED", now);
                return new ClaimResult(null, "EDGE_OAUTH_TRANSACTION_EXPIRED");
            }
            String context = binding(transaction.transactionId(), target);
            String verifier;
            String nonce;
            boolean reauthenticate;
            try {
                verifier = cipher.open(stored.verifier(), Purpose.PKCE_VERIFIER, context);
                nonce = cipher.open(stored.nonce(), Purpose.OIDC_NONCE, context);
                reauthenticate = nonce.startsWith(REAUTHENTICATION_NONCE_PREFIX);
                if (reauthenticate) nonce = nonce.substring(REAUTHENTICATION_NONCE_PREFIX.length());
            } catch (EdgeOAuthStateException invalidSecret) {
                close(transaction.transactionId(), "CLOSED", invalidSecret.getCode(), now);
                return new ClaimResult(null, invalidSecret.getCode());
            }
            UUID attempt = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            Instant deadline = now.plus(properties.getOperationLease());
            var claim = EdgeOAuthStateDatabase.target(target)
                    .addValue("id", transaction.transactionId()).addValue("version", transaction.version())
                    .addValue("attempt", attempt).addValue("owner", owner)
                    .addValue("code", EdgeOAuthStateDigests.sha256(authorizationCode))
                    .addValue("now", EdgeOAuthStateDatabase.timestamp(now))
                    .addValue("deadline", EdgeOAuthStateDatabase.timestamp(deadline));
            int changed = database.jdbc().update("""
                    update edge_oauth_transaction set status = 'EXCHANGING', version = version + 1,
                       authorization_code_digest = :code, exchange_attempt_id = :attempt,
                       lease_owner = :owner, lease_expires_at = :deadline, exchange_started_at = :now,
                       exchange_deadline_at = :deadline, updated_at = :now,
                       encryption_key_id = null, encryption_version = null,
                       verifier_ciphertext = null, nonce_ciphertext = null
                     where transaction_id = :id and target_digest = :targetDigest
                       and version = :version and status = 'PENDING'
                    """, claim);
            if (changed != 1) throw new EdgeOAuthStateException("EDGE_OAUTH_TRANSACTION_CONFLICT");
            var claimed = new EdgeOAuthTransactionBO(transaction.transactionId(), target, transaction.redirectUri(),
                    transaction.returnPath(), transaction.requestedScopes(), transaction.stateDigest(),
                    browserBindingDigest, transaction.nonceDigest(), transaction.codeChallenge(),
                    "EXCHANGING", transaction.expiresAt(), transaction.version() + 1);
            return new ClaimResult(new EdgeOAuthExchangeClaimBO(claimed, attempt, owner, deadline, verifier, nonce, reauthenticate), null);
        })).flatMap(result -> result.failureCode() == null ? Mono.just(result.claim())
                : Mono.error(new EdgeOAuthStateException(result.failureCode())));
    }

    public Mono<Optional<EdgeOAuthTransactionBO>> findPending(String appAlias, String state, String browserBindingDigest) {
        requireRandomValue(state);
        EdgeOAuthStateDigests.requireDigest(browserBindingDigest);
        if (appAlias == null || !appAlias.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid application alias");
        }
        return database.execute(() -> database.transaction(() -> {
            var rows = database.jdbc().query("""
                    select * from edge_oauth_transaction where app_alias = :app and state_digest = :state
                     and browser_binding_digest = :browser and status = 'PENDING' and expires_at > clock_timestamp()
                    """, Map.of("app", appAlias, "state", EdgeOAuthStateDigests.sha256(state), "browser", browserBindingDigest),
                    (row, index) -> pending(row).transaction());
            return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
        }));
    }

    public Mono<Boolean> cancel(EdgeOAuthTransactionBO transaction, String failureCode) {
        EdgeOAuthTransactionRepository.requireFailureCode(failureCode);
        return database.execute(() -> database.transaction(() -> {
            Instant now = database.now();
            return database.jdbc().update("""
                    update edge_oauth_transaction set status = 'CLOSED', failure_code = :failure,
                       completed_at = :now, updated_at = :now, version = version + 1,
                       encryption_key_id = null, encryption_version = null, verifier_ciphertext = null, nonce_ciphertext = null
                     where transaction_id = :id and target_digest = :target and browser_binding_digest = :browser
                       and version = :version and status = 'PENDING'
                    """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                    .addValue("id", transaction.transactionId()).addValue("target", transaction.target().digest())
                    .addValue("browser", transaction.browserBindingDigest()).addValue("version", transaction.version())
                    .addValue("failure", failureCode).addValue("now", EdgeOAuthStateDatabase.timestamp(now))) == 1;
        }));
    }

    public Mono<Boolean> complete(EdgeOAuthExchangeClaimBO claim, boolean success, String failureCode) {
        requireFailureCode(failureCode);
        return database.execute(() -> database.transaction(() -> {
            Instant now = database.now();
            var parameters = EdgeOAuthStateDatabase.target(claim.transaction().target())
                    .addValue("id", claim.transaction().transactionId()).addValue("owner", claim.owner())
                    .addValue("version", claim.transaction().version()).addValue("status", success ? "SUCCEEDED" : "FAILED")
                    .addValue("failure", failureCode).addValue("now", EdgeOAuthStateDatabase.timestamp(now));
            return database.jdbc().update("""
                    update edge_oauth_transaction set status = :status, completed_at = :now, updated_at = :now,
                       lease_owner = null, lease_expires_at = null, failure_code = :failure, version = version + 1
                     where transaction_id = :id and target_digest = :targetDigest and status = 'EXCHANGING'
                       and lease_owner = :owner and version = :version and exchange_deadline_at > :now
                    """, parameters) == 1;
        }));
    }

    public Mono<Integer> recoverExpired(int batchSize) {
        requireBatchSize(batchSize);
        return database.execute(() -> database.transaction(() -> {
            Instant now = database.now();
            List<UUID> pending = database.jdbc().query("""
                    select transaction_id from edge_oauth_transaction
                     where (status = 'PENDING' and expires_at <= :now)
                        or (status = 'EXCHANGING' and lease_expires_at <= :now)
                     order by created_at limit :batch for update skip locked
                    """, Map.of("now", EdgeOAuthStateDatabase.timestamp(now), "batch", batchSize),
                    (row, index) -> row.getObject(1, UUID.class));
            for (UUID id : pending) close(id, "CLOSED", "EDGE_OAUTH_EXCHANGE_UNCONFIRMED", now);
            return pending.size();
        }));
    }

    public Mono<Integer> purgeExpired(int batchSize) {
        requireBatchSize(batchSize);
        return database.execute(() -> database.transaction(() -> database.jdbc().update("""
                delete from edge_oauth_transaction where transaction_id in (
                    select t.transaction_id from edge_oauth_transaction t
                     where status in ('SUCCEEDED','FAILED','EXPIRED','CLOSED') and retain_until < clock_timestamp()
                       and not exists (select 1 from edge_oauth_revocation_intent i where i.source_transaction_id = t.transaction_id)
                     order by retain_until limit :batch for update skip locked)
                """, Map.of("batch", batchSize))));
    }

    private void close(UUID id, String status, String failure, Instant now) {
        database.jdbc().update("""
                update edge_oauth_transaction set status = :status, failure_code = :failure,
                   completed_at = :now, updated_at = :now, version = version + 1,
                   lease_owner = null, lease_expires_at = null, encryption_key_id = null,
                   encryption_version = null, verifier_ciphertext = null, nonce_ciphertext = null
                 where transaction_id = :id
                """, Map.of("id", id, "status", status, "failure", failure,
                "now", EdgeOAuthStateDatabase.timestamp(now)));
    }

    private static PendingTransaction pending(ResultSet row) throws SQLException {
        var transaction = new EdgeOAuthTransactionBO(row.getObject("transaction_id", UUID.class),
                EdgeOAuthStateDatabase.target(row), row.getString("redirect_uri"), row.getString("return_path"),
                List.of((String[]) row.getArray("requested_scopes").getArray()), row.getString("state_digest"),
                row.getString("browser_binding_digest"), row.getString("nonce_digest"), row.getString("code_challenge"),
                row.getString("status"), EdgeOAuthStateDatabase.instant(row, "expires_at"), row.getLong("version"));
        byte[] verifier = row.getBytes("verifier_ciphertext");
        byte[] nonce = row.getBytes("nonce_ciphertext");
        return new PendingTransaction(transaction,
                verifier == null ? null : new SealedSecret(row.getString("encryption_key_id"), row.getInt("encryption_version"), verifier),
                nonce == null ? null : new SealedSecret(row.getString("encryption_key_id"), row.getInt("encryption_version"), nonce));
    }

    static String binding(UUID id, EdgeOAuthTargetBO target) {
        return EdgeOAuthStateDigests.fields(id.toString(), target.digest());
    }

    static void requireBatchSize(int size) {
        if (size < 1 || size > 1000) throw new IllegalArgumentException("Invalid EDGE state batch size");
    }

    static void requireFailureCode(String code) {
        if (code != null && !code.matches("[A-Z0-9_]{1,128}")) throw new IllegalArgumentException("Invalid safe failure code");
    }

    private static void requireRandomValue(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43,128}")) {
            throw new IllegalArgumentException("Invalid OAuth random value");
        }
    }

    private static boolean safeReturnPath(String path) {
        return path != null && path.matches("/[A-Za-z0-9/._~-]{0,2047}") && !path.startsWith("//")
                && java.util.Arrays.stream(path.split("/", -1)).noneMatch(segment -> segment.equals(".") || segment.equals(".."));
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    private record PendingTransaction(EdgeOAuthTransactionBO transaction, SealedSecret verifier, SealedSecret nonce) { }
    private record ClaimResult(EdgeOAuthExchangeClaimBO claim, String failureCode) { }
}
