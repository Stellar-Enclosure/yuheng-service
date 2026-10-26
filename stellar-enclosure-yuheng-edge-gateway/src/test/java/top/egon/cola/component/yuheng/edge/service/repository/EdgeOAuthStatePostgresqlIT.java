package top.egon.cola.component.yuheng.edge.service.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import top.egon.cola.component.yuheng.edge.config.EdgeOAuthStateProperties;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeOAuthTargetBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshClaimResultBO;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshClaimResultBO.Outcome;
import top.egon.cola.component.yuheng.edge.service.domain.bo.EdgeRefreshContextBO;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateCipher;
import top.egon.cola.component.yuheng.edge.service.impl.EdgeOAuthStateDigests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit profile only: connects to an operator-provided DB; never starts PostgreSQL or containers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeOAuthStatePostgresqlIT {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private final EdgeOAuthTargetBO target = new EdgeOAuthTargetBO("orders", "edge-orders",
            "https://idp.example/tenants/1001", "1001", "edge-client", "https://api.example/orders");
    private final String browser = EdgeOAuthStateDigests.sha256("test-browser-binding");
    private String schema;
    private JdbcTemplate bootstrap;
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private EdgeOAuthTransactionRepository transactions;
    private EdgeRefreshOperationRepository refresh;
    private EdgeRevocationIntentRepository revocations;

    @BeforeAll
    void connectToExplicitlyProvidedDatabase(@TempDir Path directory) throws Exception {
        String url = System.getenv("EGON_EDGE_TEST_PG_URL");
        if (url == null || !url.startsWith("jdbc:postgresql:")
                || !"true".equals(System.getenv("EGON_EDGE_TEST_PG_ALLOW_DDL"))) {
            throw new IllegalStateException("EDGE PostgreSQL IT requires an existing isolated database and EGON_EDGE_TEST_PG_ALLOW_DDL=true");
        }
        String credentialFile = System.getenv("EGON_EDGE_TEST_PG_CREDENTIAL_FILE");
        String expectedDatabase = System.getenv("EGON_EDGE_TEST_PG_EXPECTED_DATABASE");
        if (credentialFile == null || expectedDatabase == null || !expectedDatabase.matches("[A-Za-z0-9_-]{1,128}")
                || url.matches("(?is).*[?&](?:user|password|currentSchema|options)=.*")) {
            throw new IllegalStateException("EDGE PostgreSQL IT requires private credential file and explicit database identity");
        }
        Properties credentials = readCredentials(Path.of(credentialFile));
        var source = new DriverManagerDataSource(url, credentials.getProperty("user"), credentials.getProperty("password"));
        bootstrap = new JdbcTemplate(source);
        if (!expectedDatabase.equals(bootstrap.queryForObject("select current_database()", String.class))) {
            throw new IllegalStateException("EDGE PostgreSQL IT database identity mismatch");
        }
        schema = "edge_oauth_it_" + UUID.randomUUID().toString().replace("-", "");
        bootstrap.execute("create schema " + schema);
        var connectionProperties = new Properties();
        connectionProperties.setProperty("currentSchema", schema);
        var scoped = new DriverManagerDataSource(url, credentials.getProperty("user"), credentials.getProperty("password"));
        scoped.setConnectionProperties(connectionProperties);
        Flyway.configure().dataSource(scoped).locations("classpath:db/migration").defaultSchema(schema)
                .schemas(schema).createSchemas(false).table("flyway_edge_oauth_history")
                .cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(scoped);
        jdbc.setQueryTimeout(5);
        var template = new TransactionTemplate(new DataSourceTransactionManager(scoped));
        template.setTimeout(5);
        scheduler = Schedulers.newBoundedElastic(8, 100, "edge-oauth-db-it");
        var database = new EdgeOAuthStateDatabase(new NamedParameterJdbcTemplate(jdbc), template, scheduler);
        Path key = directory.resolve("edge-test-key");
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        Files.write(key, bytes);
        Files.setPosixFilePermissions(key, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        var properties = new EdgeOAuthStateProperties();
        properties.getEncryption().setActiveKeyId("test");
        properties.getEncryption().setKeyFiles(Map.of("test", key));
        var cipher = new EdgeOAuthStateCipher(properties.getEncryption());
        transactions = new EdgeOAuthTransactionRepository(database, cipher, properties);
        revocations = new EdgeRevocationIntentRepository(database, cipher, properties);
        refresh = new EdgeRefreshOperationRepository(database, revocations, properties);
    }

    @AfterEach
    void clearOnlyThisTestOwnedSchema() {
        if (jdbc == null) return;
        jdbc.update("delete from edge_oauth_revocation_receipt");
        jdbc.update("delete from edge_oauth_revocation_intent");
        jdbc.update("delete from edge_oauth_refresh_operation");
        jdbc.update("delete from edge_oauth_transaction");
    }

    @AfterAll
    void releaseOwnedResources() {
        if (scheduler != null) scheduler.dispose();
        if (bootstrap != null && schema != null && schema.matches("edge_oauth_it_[a-f0-9]{32}")) {
            bootstrap.execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void callbackConsumesOneTransactionAndErasesVerifierAndNonceBeforeAnyExchange() {
        var created = transactions.create(target, "https://edge.example/apps/orders/auth/callback", "/orders",
                List.of("openid", "profile"), "s".repeat(43), browser, "v".repeat(43), "n".repeat(43), true).block(WAIT);
        var claimed = transactions.claim(target, "s".repeat(43), browser, "one-authorization-code").block(WAIT);
        assertThat(claimed.codeVerifier()).isEqualTo("v".repeat(43));
        assertThat(claimed.nonce()).isEqualTo("n".repeat(43));
        assertThat(jdbc.queryForObject("select verifier_ciphertext is null and nonce_ciphertext is null from edge_oauth_transaction where transaction_id = ?",
                Boolean.class, created.transactionId())).isTrue();
        assertThatThrownBy(() -> transactions.claim(target, "s".repeat(43), browser, "one-authorization-code").block(WAIT))
                .hasMessageContaining("EDGE_OAUTH_TRANSACTION_INVALID");
        assertThat(transactions.complete(claimed, true, null).block(WAIT)).isTrue();
        assertThat(transactions.complete(claimed, true, null).block(WAIT)).isFalse();
    }

    @Test
    void concurrentNodesClaimOneDispatchAndSpentCredentialReplayClosesTheFamily() throws Exception {
        var context = context(0);
        String token = "refresh-credential-for-one-generation";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> claimTogether(context, token, ready, start), callers);
            var second = CompletableFuture.supplyAsync(() -> claimTogether(context, token, ready, start), callers);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().map(EdgeRefreshClaimResultBO::outcome)).containsExactlyInAnyOrder(Outcome.ACQUIRED, Outcome.IN_PROGRESS);
            var acquired = results.stream().filter(value -> value.outcome() == Outcome.ACQUIRED).findFirst().orElseThrow().operation();
            var dispatched = refresh.dispatch(acquired).block(WAIT).orElseThrow();
            assertThat(refresh.dispatch(acquired).block(WAIT)).isEmpty();
            assertThat(refresh.complete(dispatched, 1, Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS)).block(WAIT)).isTrue();
            assertThat(jdbc.queryForObject("select status from edge_oauth_revocation_intent where source_refresh_operation_id = ?",
                    String.class, acquired.operationId())).isEqualTo("DISARMED");
            assertThat(refresh.inspect(acquired.operationId(), context, token).block(WAIT).outcome()).isEqualTo(Outcome.EXISTING);
            assertThat(refresh.inspect(UUID.randomUUID(), context, token).block(WAIT).outcome()).isEqualTo(Outcome.REPLAY);
            assertThat(revocations.isClosed(context).block(WAIT)).isTrue();
            assertThat(jdbc.queryForObject("select upstream_attempts from edge_oauth_refresh_operation where operation_id = ?",
                    Integer.class, acquired.operationId())).isEqualTo(1);
        }
    }

    @Test
    void unknownOutcomeCommitsRevocationIntentAndOnlyAConfirmedWorkerCreatesReceipt() {
        var context = context(0);
        var acquired = refresh.claim(UUID.randomUUID(), context, "refresh-for-unknown-outcome").block(WAIT).operation();
        var dispatched = refresh.dispatch(acquired).block(WAIT).orElseThrow();
        assertThat(refresh.closeUnknown(dispatched, "EDGE_REFRESH_RESPONSE_UNCONFIRMED").block(WAIT)).isTrue();
        assertThat(revocations.isClosed(context).block(WAIT)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from edge_oauth_revocation_receipt", Integer.class)).isZero();
        var delivery = revocations.claimDue(10).block(WAIT).getFirst();
        assertThat(delivery.tokenValue()).isEqualTo("refresh-for-unknown-outcome");
        assertThat(delivery.toString()).doesNotContain("refresh-for-unknown-outcome");
        assertThat(revocations.confirm(delivery).block(WAIT)).isTrue();
        assertThat(revocations.confirm(delivery).block(WAIT)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from edge_oauth_revocation_receipt", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select token_ciphertext is null from edge_oauth_revocation_intent where intent_id = ?",
                Boolean.class, delivery.intent().intentId())).isTrue();
        assertThat(refresh.complete(dispatched, 1, Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS)).block(WAIT)).isFalse();
    }

    @Test
    void expiredLeaseCannotBeReusedAndOperationStatusCannotCrossBrowserBinding() {
        var context = context(0);
        var operation = refresh.claim(UUID.randomUUID(), context, "refresh-before-worker-loss").block(WAIT).operation();
        jdbc.update("update edge_oauth_refresh_operation set lease_expires_at = created_at + interval '1 microsecond' where operation_id = ?",
                operation.operationId());
        assertThat(revocations.isClosed(context).block(WAIT)).isTrue();
        assertThat(refresh.recoverExpired(10).block(WAIT)).isEqualTo(1);
        assertThat(refresh.dispatch(operation).block(WAIT)).isEmpty();
        assertThat(revocations.isClosed(context).block(WAIT)).isTrue();
        var otherBrowser = new EdgeRefreshContextBO(context.target(), context.subject(), context.authorizationId(),
                context.authenticationJti(), context.generation(), context.authorizationExpiresAt(),
                context.authenticationExpiresAt(), EdgeOAuthStateDigests.sha256("other-browser"));
        assertThat(refresh.status(operation.operationId(), otherBrowser).block(WAIT)).isEmpty();
    }

    private EdgeRefreshClaimResultBO claimTogether(EdgeRefreshContextBO context, String token,
                                                   CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out");
            return refresh.claim(UUID.randomUUID(), context, token).block(WAIT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted", interrupted);
        }
    }

    static Properties readCredentials(Path path) throws Exception {
        if (!path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > 16 * 1024 || !Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                || !Files.getOwner(path).equals(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name")))) {
            throw new IllegalStateException("EDGE_IT_PRIVATE_CREDENTIAL_FILE_REQUIRED");
        }
        var parent = Files.readAttributes(path.getParent(), java.nio.file.attribute.PosixFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!parent.isDirectory() || parent.permissions().contains(PosixFilePermission.GROUP_WRITE)
                || parent.permissions().contains(PosixFilePermission.OTHERS_WRITE) || !parent.owner().equals(Files.getOwner(path))) {
            throw new IllegalStateException("EDGE_IT_PRIVATE_CREDENTIAL_DIRECTORY_REQUIRED");
        }
        Properties values = new Properties();
        try (var input = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
        if (!values.stringPropertyNames().equals(Set.of("user", "password")) || values.getProperty("user").isBlank() || values.getProperty("password").isEmpty()) {
            throw new IllegalStateException("EDGE_IT_CREDENTIAL_PROPERTIES_INVALID");
        }
        return values;
    }

    private EdgeRefreshContextBO context(long generation) {
        Instant expiry = Instant.now().plusSeconds(1800).truncatedTo(ChronoUnit.SECONDS);
        return new EdgeRefreshContextBO(target, "user-1", "101", "authentication-jti-1", generation, expiry, expiry, browser);
    }
}
