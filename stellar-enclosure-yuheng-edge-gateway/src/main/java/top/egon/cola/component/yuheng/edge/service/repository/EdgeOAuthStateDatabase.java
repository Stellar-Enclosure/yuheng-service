package top.egon.cola.component.yuheng.edge.service.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;

/** One JDBC transaction stays on one bounded worker, including all state and intent writes. */
public final class EdgeOAuthStateDatabase {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Scheduler scheduler;

    public EdgeOAuthStateDatabase(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
                                   Scheduler scheduler) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.scheduler = scheduler;
    }

    public <T> Mono<T> execute(Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(scheduler);
    }

    <T> T transaction(Supplier<T> operation) {
        requireWorker();
        return transactions.execute(status -> operation.get());
    }

    NamedParameterJdbcTemplate jdbc() {
        requireWorker();
        return jdbc;
    }

    Instant now() {
        requireTransaction();
        return jdbc.queryForObject("select clock_timestamp()", Map.of(),
                (row, index) -> row.getTimestamp(1).toInstant());
    }

    void lock(EdgeRefreshContextBO context) {
        lock(context.target(), context.authenticationJti(), context.authorizationId());
    }

    void lock(EdgeOAuthTargetBO target, String authenticationJti, String authorizationId) {
        requireTransaction();
        if (authenticationJti != null) lock(EdgeOAuthStateDigests.lockKey(
                "edge-authentication-lock-v1", target.issuer(), authenticationJti));
        if (authorizationId != null) lock(EdgeOAuthStateDigests.lockKey(
                "edge-authorization-lock-v1", target.digest(), authorizationId));
    }

    void lockCredential(EdgeOAuthTargetBO target, String credentialDigest) {
        requireTransaction();
        lock(EdgeOAuthStateDigests.lockKey("edge-credential-lock-v1", target.digest(), credentialDigest));
    }

    private void lock(long key) {
        jdbc.query("select pg_advisory_xact_lock(:key)", Map.of("key", key), row -> { });
    }

    static MapSqlParameterSource target(EdgeOAuthTargetBO target) {
        return new MapSqlParameterSource()
                .addValue("targetDigest", target.digest()).addValue("appAlias", target.appAlias())
                .addValue("registrationId", target.registrationId()).addValue("issuer", target.issuer())
                .addValue("tenantId", target.tenantId()).addValue("clientId", target.clientId())
                .addValue("resource", target.resource());
    }

    static EdgeOAuthTargetBO target(ResultSet row) throws SQLException {
        return new EdgeOAuthTargetBO(row.getString("app_alias"), row.getString("registration_id"),
                row.getString("issuer_uri"), row.getString("tenant_id"),
                row.getString("client_id"), row.getString("resource_uri"));
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static void requireWorker() {
        if (Schedulers.isInNonBlockingThread()) {
            throw new IllegalStateException("EDGE OAuth JDBC must not run on the event loop");
        }
    }

    static void requireTransaction() {
        requireWorker();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("EDGE OAuth state requires a database transaction");
        }
    }
}
