package top.egon.cola.component.yuheng.admin.exposure.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.business.EgonColaMdcUserIdProvider;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdTenantLineHandler;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlManifestBO;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlResult.StatusEnum;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlTargetBO;
import top.egon.cola.component.common.mybatis.ddl.EgonColaPostgreDdlRunner;
import top.egon.cola.component.common.mybatis.handler.EgonColaMetaObjectHandler;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaModelValidationInterceptor;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaOriginalSqlGuardInterceptor;
import top.egon.cola.component.common.mybatis.interceptor.EgonColaTenantIdGuardInnerInterceptor;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationUtils;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyFactory;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaSingleTableShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaStandardTenantIdShardingStrategy;
import top.egon.cola.component.yuheng.admin.config.GatewayManagedDdlFactory;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceConfiguration;
import top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties;
import top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceProperties;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.exposure.converter.GatewayUrlExposurePersistenceConverter;
import top.egon.cola.component.yuheng.admin.exposure.dao.GatewayUrlExposureDAO;
import top.egon.cola.component.yuheng.admin.exposure.domain.bo.GatewayUrlExposureBO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainResolver;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.impl.MpGatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.repository.mp.GatewayUrlExposurePersistenceRepository;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum.CLOSED;
import static top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum.OPEN;
import static top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum.WITHDRAWN;

/** Exercises the managed schema and repository on a fresh, explicitly named loopback database. */
@EnabledIfSystemProperty(named = "yuheng.url.test.postgres.url", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayUrlExposureRepositoryIT {

    private static final String URL = "yuheng.url.test.postgres.url";
    private static final String USER = "yuheng.url.test.postgres.user";
    private static final String MANIFEST = "db/egon-mp/repository-manifest.json";
    private static final long OWNER = 7101L;
    private static final long OTHER_OWNER = 7102L;
    private static final long GROUP = 8101L;
    private static final long OTHER_GROUP = 8102L;
    private static final long RELEASE = 9101L;
    private static final long OTHER_RELEASE = 9102L;
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00.123456Z");
    private static final String CLUSTER = "cluster-main";
    private static final String GATEWAY_NAMESPACE = "gateway-system";
    private static final String GATEWAY = "public-gateway";
    private static final String OTHER_GATEWAY = "public-gateway-other";
    private static final String LISTENER = "https-public";
    private static final String HOST = "api.example.com";

    private final PathMatchingResourcePatternResolver resources = new PathMatchingResourcePatternResolver();
    private ValidatorFactory validators;
    private EgonColaModelValidationUtils.Binding validationBinding;
    private DriverManagerDataSource physical;
    private JdbcTemplate sql;
    private DataSource routed;
    private JdbcTemplate routedSql;
    private TransactionTemplate transactions;
    private GatewayUrlExposureRepository repository;

    @BeforeAll
    void prepareManagedDatabase() throws Exception {
        String url = System.getProperty(URL);
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/yuheng_it_[a-z0-9_]{1,40}")) {
            throw new IllegalArgumentException("Expected an isolated loopback yuheng_it_ acceptance database");
        }
        physical = new DriverManagerDataSource(url, System.getProperty(USER), "");
        sql = new JdbcTemplate(physical);
        assertThat(sql.queryForObject(
                "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public'", Long.class))
                .as("require an empty, dedicated test database before any schema change")
                .isZero();
        validators = Validation.buildDefaultValidatorFactory();
        EgonColaDdlManifestBO manifest;
        try (var input = resources.getResource("classpath:" + MANIFEST).getInputStream()) {
            manifest = new ObjectMapper().readValue(input, EgonColaDdlManifestBO.class);
        }
        assertThat(manifest.scripts()).extracting(EgonColaDdlManifestBO.ScriptBO::version)
                .containsExactly("20260922_001", "20261116_002", "20261117_003", "20261117_004");
        var validation = new ValidationUtils(validators.getValidator());
        if (EgonColaModelValidationUtils.current() == null) {
            validationBinding = EgonColaModelValidationUtils.initialize(validation, this);
        }
        var runner = new EgonColaPostgreDdlRunner(validation,
                resources, Clock.systemUTC(), Duration.ofSeconds(10), Duration.ofSeconds(30));
        var topology = controlTopology(url);
        var strategies = new EgonColaShardingStrategyFactory(validation, Map.of(
                "STANDARD_TENANT_ID", new EgonColaStandardTenantIdShardingStrategy(),
                "SINGLE", new EgonColaSingleTableShardingStrategy()));
        var loader = new EgonColaShardingYamlLoader(strategies, resources, new StandardEnvironment());
        var topologyValidator = new EgonColaShardingTopologyValidator(validation);
        String routeFingerprint = topologyValidator.validate(topology, loader.load(topology)).fingerprint();

        var prefix = new EgonColaDdlManifestBO(manifest.family(), manifest.scripts().subList(0, 2));
        var existingTarget = new EgonColaDdlTargetBO("yuheng_control", "public",
                EgonColaDdlTargetBO.RoleEnum.SHARD, physical, prefix, routeFingerprint);
        assertThat(runner.run(List.of(existingTarget))).isNotEmpty().allSatisfy(
                result -> assertThat(result.status()).isEqualTo(StatusEnum.APPLIED));
        insertRelease(OWNER, GROUP, RELEASE);
        insertRelease(OTHER_OWNER, OTHER_GROUP, OTHER_RELEASE);

        // Restore the actual prior V003 state before adding any Outbox route or schema.
        GatewayPersistenceConfiguration.prepareUrlExposureControlTopology(topology);
        String v003Fingerprint = topologyValidator.validate(topology, loader.load(topology)).fingerprint();
        var v003 = new EgonColaDdlManifestBO(manifest.family(), manifest.scripts().subList(0, 3));
        runner.run(List.of(new EgonColaDdlTargetBO("yuheng_control", "public", EgonColaDdlTargetBO.RoleEnum.SHARD,
                physical, v003, v003Fingerprint, java.util.Set.of(routeFingerprint))));
        assertThat(sql.queryForList("SELECT version FROM public.ddl_history ORDER BY version", String.class))
                .containsExactly("20260922_001", "20261116_002", "20261117_003");
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname='egon_outbox'", Long.class)).isZero();

        var latest = manifest.scripts().getLast();
        var deployment = new GatewayPersistenceProperties(OWNER, 100L, "acceptance",
                latest.version(), latest.sha256(), true);
        var outboxStorage = new OutboxMpStorageProperties();
        var initializer = new OutboxManagedDdlInitializer(runner, topologyValidator, validation, topology, outboxStorage, new ObjectMapper());
        var productionFactory = new GatewayManagedDdlFactory(topology, new ObjectMapper(), deployment,
                topologyValidator, runner, initializer, outboxStorage);
        routed = productionFactory.create(Map.of("yuheng_control", physical), loader.load(topology));
        assertThat(sql.queryForList("SELECT version FROM public.ddl_history ORDER BY version", String.class))
                .containsExactly("20260922_001", "20261116_002", "20261117_003", "20261117_004");
        assertThat(sql.queryForList("SELECT route_fingerprint FROM public.ddl_history ORDER BY version", String.class))
                .containsExactly(routeFingerprint, routeFingerprint, v003Fingerprint,
                        topologyValidator.validate(topology, loader.load(topology)).fingerprint());
        String currentFingerprint = topologyValidator.validate(topology, loader.load(topology)).fingerprint();
        assertThat(initializer.isReadyFor(physical, currentFingerprint)).isTrue();
        assertThat(sql.queryForList("SELECT version FROM egon_outbox.ddl_history ORDER BY version", String.class))
                .containsExactly("20260924_001", "20261027_001");
        assertThat(sql.queryForObject("SELECT count(*) FROM egon_outbox.egon_cola_outbox_message", Long.class)).isZero();
        configureRepositorySessions(deployment);
    }

    @AfterAll
    void closeFixture() throws Exception {
        try {
            if (routed instanceof AutoCloseable managed) {
                managed.close();
            }
        } finally {
            if (validationBinding != null) {
                validationBinding.close();
            }
            if (validators != null) {
                validators.close();
            }
        }
    }

    private void configureRepositorySessions(GatewayPersistenceProperties deployment) throws Exception {
        routedSql = new JdbcTemplate(routed);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(routed));

        var properties = new EgonColaMybatisPlusProperties();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(routed);
        var global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        var actor = new EgonColaMdcUserIdProvider(properties);
        global.setMetaObjectHandler(new EgonColaMetaObjectHandler(actor, Clock.systemUTC()));
        factory.setGlobalConfig(global);
        var plus = new MybatisPlusInterceptor();
        plus.addInnerInterceptor(new EgonColaTenantIdGuardInnerInterceptor(actor, properties));
        plus.addInnerInterceptor(new BlockAttackInnerInterceptor());
        plus.addInnerInterceptor(new TenantLineInnerInterceptor(new EgonColaTenantIdTenantLineHandler(properties)));
        plus.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(plus, new EgonColaOriginalSqlGuardInterceptor(properties),
                new EgonColaModelValidationInterceptor(validators.getValidator(), properties));
        factory.setMapperLocations(new ClassPathResource("mybatis/mapper/exposure/GatewayUrlExposureDAO.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        var beans = new StaticListableBeanFactory();
        PublicationDomainResolver resolver = (cluster, binding) -> new PublicationDomainVO(
                cluster, "acceptance-vip-443", "1",
                List.of(new BindingVO(GATEWAY_NAMESPACE, GATEWAY, LISTENER),
                        new BindingVO(GATEWAY_NAMESPACE, OTHER_GATEWAY, LISTENER)), true);
        beans.addBean("gatewayUrlExposurePublicationDomainResolver", resolver);
        repository = new MpGatewayUrlExposureRepository(
                new GatewayUrlExposurePersistenceRepository(sessions.getMapper(GatewayUrlExposureDAO.class), properties),
                new GatewayUrlExposurePersistenceConverter(), properties,
                new GatewayPersistenceContextComponent(deployment, properties),
                beans.getBeanProvider(PublicationDomainResolver.class));
    }

    private static EgonColaShardingProperties controlTopology(String url) {
        var tables = new LinkedHashMap<String, TableProperties>();
        for (String table : List.of("gateway_group", "gateway_release", "gateway_release_content",
                "gateway_idempotency_record", "gateway_audit_log")) {
            tables.put(table, new TableProperties("STANDARD_TENANT_ID", null, "tenant_id", null, null));
        }
        var primary = new EgonColaShardingProperties.PhysicalDataSourceProperties("yuheng_control", "yuheng_control",
                EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY, "org.postgresql.Driver", url,
                System.getProperty(USER), "fixture");
        return EgonColaShardingProperties.builder().mode(EgonColaShardingProperties.ModeEnum.SHARDING)
                .configStyle(EgonColaShardingProperties.ConfigStyleEnum.STRATEGY).transactionDefaultType("LOCAL")
                .dataSources(List.of(primary)).tables(tables).build();
    }

    @Test
    void enforcesCompositeOwnerForeignKeysAndTechnicalTenant() {
        assertSqlState("23503", () -> insertExposure(6101, 0, OTHER_OWNER, GROUP, OTHER_RELEASE, "/fk-owner"));
        assertSqlState("23503", () -> insertExposure(6102, 0, OWNER, GROUP, OTHER_RELEASE, "/fk-release"));
        assertSqlState("23514", () -> insertExposure(6103, OWNER, OWNER, GROUP, RELEASE, "/technical-tenant"));
    }

    @Test
    void retainsWithdrawnReservationAndRejectsSecondOwnerForTheExactKey() {
        insertExposure(6201, 0, OWNER, GROUP, RELEASE, "/withdrawn-sql");
        assertThat(sql.update("""
                UPDATE public.gateway_url_exposure_t0
                SET desired_state = 'WITHDRAWN', withdrawn_at = CURRENT_TIMESTAMP,
                    desired_generation = desired_generation + 1, version = version + 1
                WHERE id = 6201 AND desired_state = 'OPEN' AND version = 0
                """)).isEqualTo(1);
        assertSqlState("23505", () -> insertExposure(6202, 0, OTHER_OWNER, OTHER_GROUP, OTHER_RELEASE,
                "/withdrawn-sql"));
        assertSqlState("23514", () -> sql.update("""
                UPDATE public.gateway_url_exposure_t0 SET deleted_at = CURRENT_TIMESTAMP WHERE id = 6201
                """));
        assertThat(sql.queryForObject("""
                SELECT desired_state FROM public.gateway_url_exposure_t0 WHERE id = 6201 AND deleted_at IS NULL
                """, String.class)).isEqualTo("WITHDRAWN");
    }

    @Test
    void persistsTheMaximumNormalizedAsciiBindingWithoutIndexOverflow() {
        Random input = new Random(6301);
        insertExposure(6301, 0, OWNER, GROUP, RELEASE, "/" + asciiLabel(input, 1023));
        assertThat(sql.update("""
                UPDATE public.gateway_url_exposure_t0
                SET cluster_id = ?, gateway_namespace = ?, gateway_name = ?, listener_name = ?, hostname = ?
                WHERE id = 6301
                """, asciiLabel(input, 128), asciiLabel(input, 63), dnsSubdomain(input),
                asciiLabel(input, 253), dnsSubdomain(input))).isEqualTo(1);
    }

    @Test
    void rejectsForeignOwnersAndStaleObservationsWithoutChangingTheNewerIntent() {
        var registration = registration(6401L, OWNER, GATEWAY, HOST, "GET", "EXACT", "/mapped-cas");
        transaction(OWNER, () -> repository.reserve(OWNER, registration));
        assertThat(asOwner(OTHER_OWNER, () -> repository.findOwned(OTHER_OWNER, 6401L))).isEmpty();
        assertThatThrownBy(() -> asOwner(OTHER_OWNER, () -> repository.findOwned(OWNER, 6401L)))
                .isInstanceOf(RuntimeException.class);
        assertThat(transaction(OWNER, () -> repository.bindRouteUid(OWNER, 6401L, 0L, "route-uid", NOW))).isTrue();
        assertThat(transaction(OWNER, () -> repository.compareAndSetDesired(
                OWNER, 6401L, 1L, 1L, OPEN, CLOSED, "close acceptance", NOW))).isTrue();
        assertThat(transaction(OWNER, () -> repository.recordObservation(OWNER, 6401L, 2L, 1L, "route-uid",
                "OPEN", 1L, "SYNCED", emptyObservation(), null, NOW))).isFalse();
        assertThat(transaction(OWNER, () -> repository.compareAndSetDesired(
                OWNER, 6401L, 2L, 2L, CLOSED, WITHDRAWN, "withdraw acceptance", NOW))).isTrue();
        var withdrawal = asOwner(OWNER, () -> repository.findOwned(OWNER, 6401L)).orElseThrow();
        assertThat(withdrawal.getDesiredState()).isEqualTo(WITHDRAWN);
        assertThat(withdrawal.getDesiredGeneration()).isEqualTo(3L);
        assertThat(withdrawal.getVersion()).isEqualTo(3L);
        assertThat(withdrawal.getWithdrawnAt()).isEqualTo(NOW);
        var byStableRoute = asOwner(OWNER, () -> repository.findOwnedRoute(OWNER, GROUP, registration.getRouteId()))
                .orElseThrow();
        assertThat(byStableRoute.getId()).isEqualTo(withdrawal.getId());
        assertThat(byStableRoute.getDesiredState()).isEqualTo(WITHDRAWN);
        assertThat(asOwner(OWNER, () -> repository.findOwnedRoute(OWNER, OTHER_GROUP, registration.getRouteId()))).isEmpty();
        assertThat(asOwner(OTHER_OWNER, () -> repository.findOwnedRoute(OTHER_OWNER, GROUP, registration.getRouteId()))).isEmpty();
        assertThatThrownBy(() -> asOwner(OTHER_OWNER, () -> repository.findOwnedRoute(OWNER, GROUP, registration.getRouteId())))
                .isInstanceOf(IllegalStateException.class).hasMessage("TENANT_CONTEXT_MISMATCH");
        var replay = transaction(OWNER, () -> repository.reserve(OWNER, registration));
        assertThat(replay.getDesiredState()).isEqualTo(WITHDRAWN);
        assertThat(replay.getVersion()).isEqualTo(3L);
        assertThatThrownBy(() -> transaction(OWNER, () -> repository.compareAndSetDesired(
                OWNER, 6401L, 3L, 3L, WITHDRAWN, OPEN, "obsolete attach", NOW)))
                .isInstanceOf(IllegalStateException.class).hasMessage("WITHDRAWN");
        var confirmed = emptyObservation();
        assertThat(transaction(OWNER, () -> repository.recordObservation(OWNER, 6401L, 3L, 3L, "route-uid",
                "WITHDRAWN", 3L, "SYNCED", confirmed, null, NOW))).isTrue();
        var latest = asOwner(OWNER, () -> repository.findOwned(OWNER, 6401L)).orElseThrow();
        assertThat(latest.getVersion()).isEqualTo(4L);
        assertThat(latest.getObservedGeneration()).isEqualTo(3L);
        assertThat(latest.getObservation()).isEqualTo(confirmed);
    }

    @Test
    void serializesOverlappingBindingsAcrossGatewaysAndOwnersOnTheSameBackend() throws Exception {
        var first = registration(6501L, OWNER, GATEWAY, "*.example.com", "*", "PATH_PREFIX", "/domain");
        var overlapping = registration(6502L, OTHER_OWNER, OTHER_GATEWAY, HOST, "POST", "EXACT", "/domain/a");
        var sibling = registration(6503L, OTHER_OWNER, OTHER_GATEWAY, HOST, "POST", "EXACT", "/domain-other");
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch mayCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstWriter = executor.submit(() -> transaction(OWNER, () -> {
                var row = repository.reserve(OWNER, first);
                Integer pid = routedSql.queryForObject("""
                        SELECT pg_backend_pid() FROM public.gateway_url_exposure_t0 WHERE id = ?
                        """, Integer.class, row.getId());
                assertThat(sql.queryForObject("""
                        SELECT count(*) FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND granted
                        """, Long.class, pid)).isPositive();
                acquired.countDown();
                await(mayCommit);
                return row;
            }));
            try {
                if (!acquired.await(5, TimeUnit.SECONDS)) {
                    firstWriter.get(5, TimeUnit.SECONDS);
                    throw new AssertionError("first transaction did not prove that it owned the advisory lock");
                }
                var challenger = executor.submit(() -> transaction(OTHER_OWNER,
                        () -> repository.reserve(OTHER_OWNER, overlapping)));
                assertThat(awaitBlockedAdvisoryLock()).isTrue();
                assertThat(challenger.isDone()).isFalse();
                mayCommit.countDown();
                assertThat(firstWriter.get(5, TimeUnit.SECONDS).getId()).isEqualTo(6501L);
                assertThatThrownBy(() -> challenger.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasRootCauseMessage("BINDING_CONFLICT");
            } finally {
                mayCommit.countDown();
            }
        }
        assertThat(transaction(OTHER_OWNER, () -> repository.reserve(OTHER_OWNER, sibling)).getId())
                .isEqualTo(6503L);
        assertThat(asOwner(OWNER, () -> repository.hasPublishedOverlap(OWNER, first))).isFalse();
        assertThat(asOwner(OTHER_OWNER, () -> repository.hasPublishedOverlap(OTHER_OWNER, overlapping))).isTrue();
        assertThat(asOwner(OTHER_OWNER, () -> repository.hasPublishedOverlap(OTHER_OWNER, sibling))).isFalse();
        assertThat(asOwner(OTHER_OWNER, () -> repository.findOwned(OTHER_OWNER, 6502L))).isEmpty();
    }

    @Test
    void scopesPendingRecoveryByOwnerAndTraversesTheStableCursorWithTerminalIntent() {
        var first = registration(6701L, OWNER, GATEWAY, HOST, "GET", "EXACT", "/recovery-first");
        var foreign = registration(6702L, OTHER_OWNER, OTHER_GATEWAY, HOST, "GET", "EXACT", "/recovery-foreign");
        var terminal = registration(6703L, OWNER, GATEWAY, HOST, "GET", "EXACT", "/recovery-terminal");
        transaction(OWNER, () -> repository.reserveAll(OWNER, List.of(first, terminal)));
        transaction(OTHER_OWNER, () -> repository.reserve(OTHER_OWNER, foreign));
        assertThat(transaction(OWNER, () -> repository.compareAndSetDesired(
                OWNER, 6703L, 0L, 1L, OPEN, WITHDRAWN, "withdraw and verify route is no longer reachable", NOW))).isTrue();

        assertThat(asOwner(OWNER, () -> repository.findRecoveryIds(OWNER, 6700L, 1))).containsExactly(6701L);
        assertThat(asOwner(OWNER, () -> repository.findRecoveryIds(OWNER, 6701L, 1))).containsExactly(6703L);
        assertThat(asOwner(OWNER, () -> repository.findRecoveryIds(OWNER, 6703L, 1))).isEmpty();
        assertThat(asOwner(OTHER_OWNER, () -> repository.findRecoveryIds(OTHER_OWNER, 6700L, 10))).containsExactly(6702L);
        assertThatThrownBy(() -> asOwner(OTHER_OWNER, () -> repository.findRecoveryIds(OWNER, 6700L, 10)))
                .isInstanceOf(IllegalStateException.class).hasMessage("TENANT_CONTEXT_MISMATCH");
    }

    @Test
    void commitsTheExposureAndItsReleaseWriteUnderTheSamePostgresTransaction() {
        var row = registration(6601L, OWNER, GATEWAY, HOST, "GET", "EXACT", "/same-transaction");
        transaction(OWNER, () -> {
            assertThat(routedSql.update("""
                    UPDATE gateway_release SET version = version + 1 WHERE tenant_id = ? AND id = ?
                    """, OWNER, RELEASE)).isEqualTo(1);
            return repository.reserve(OWNER, row);
        });
        assertThat(sql.queryForObject("SELECT xmin::text FROM public.gateway_url_exposure_t0 WHERE id = 6601",
                String.class)).isNotBlank().isEqualTo(sql.queryForObject(
                "SELECT xmin::text FROM public.gateway_release_t0 WHERE id = ?", String.class, RELEASE));

        var rolledBack = registration(6602L, OWNER, GATEWAY, HOST, "GET", "EXACT", "/rolled-back");
        var failure = new IllegalStateException("acceptance rollback");
        assertThatThrownBy(() -> transaction(OWNER, () -> {
            assertThat(routedSql.update("""
                    UPDATE gateway_release SET version = version + 1 WHERE tenant_id = ? AND id = ?
                    """, OWNER, RELEASE)).isEqualTo(1);
            repository.reserve(OWNER, rolledBack);
            throw failure;
        })).isSameAs(failure);
        assertThat(asOwner(OWNER, () -> repository.findOwned(OWNER, 6602L))).isEmpty();
        assertThat(sql.queryForObject("SELECT version FROM public.gateway_release_t0 WHERE id = ?", Long.class, RELEASE))
                .isEqualTo(1L);
    }

    private <T> T transaction(long owner, Supplier<T> work) {
        return asOwner(owner, () -> transactions.execute(status -> work.get()));
    }

    private static <T> T asOwner(long owner, Supplier<T> work) {
        var original = MDC.getCopyOfContextMap();
        MDC.put("tenantId", Long.toString(owner));
        MDC.put("userId", "acceptance");
        try {
            T result = work.get();
            assertThat(MDC.get("tenantId")).isEqualTo(Long.toString(owner));
            return result;
        } finally {
            if (original == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(original);
            }
        }
    }

    private static GatewayUrlExposureBO registration(long id, long owner, String gateway, String host,
                                                       String method, String matchType, String path) {
        boolean secondaryOwner = owner == OTHER_OWNER;
        return GatewayUrlExposureBO.builder().id(id).ownerTenantId(owner).gatewayGroupId(secondaryOwner ? OTHER_GROUP : GROUP)
                .routeId("orders-" + id).clusterId(CLUSTER).gatewayNamespace(GATEWAY_NAMESPACE).gatewayName(gateway)
                .gatewayUid("trusted-uid-" + gateway).listenerName(LISTENER).hostname(host).httpMethod(method)
                .pathMatchType(matchType).pathValue(path).desiredState(OPEN)
                .routeKind("HTTPRoute").routeNamespace("yuheng-routes")
                .releaseId(secondaryOwner ? OTHER_RELEASE : RELEASE).contentSha256("b".repeat(64))
                .compilerProfileId("acceptance-profile").changeReason("acceptance").build();
    }

    private static com.fasterxml.jackson.databind.JsonNode emptyObservation() {
        var root = new ObjectMapper().createObjectNode();
        root.put("schemaVersion", 1);
        root.putArray("conditions");
        root.putNull("kubernetesRoute");
        return root;
    }

    private boolean awaitBlockedAdvisoryLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(sql.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND NOT granted)
                    """, Boolean.class))) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(8, TimeUnit.SECONDS)).as("transaction did not reach the expected phase").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted transaction fixture", failure);
        }
    }

    private void insertRelease(long owner, long groupId, long releaseId) {
        sql.update("""
                INSERT INTO public.gateway_group_t0
                    (id, tenant_id, create_user_id, create_time, update_user_id, update_time,
                     gateway_group_code, display_name, env, namespace)
                VALUES (?, ?, 'acceptance', CURRENT_TIMESTAMP, 'acceptance', CURRENT_TIMESTAMP,
                        ?, 'Acceptance', 'isolated', 'acceptance')
                """, groupId, owner, "orders-" + owner);
        sql.update("""
                INSERT INTO public.gateway_release_t0
                    (id, tenant_id, create_user_id, create_time, update_user_id, update_time,
                     gateway_group_id, draft_revision, status, validation_report, structured_diff, change_reason)
                VALUES (?, ?, 'acceptance', CURRENT_TIMESTAMP, 'acceptance', CURRENT_TIMESTAMP,
                        ?, 1, 'SUCCEEDED', '{}'::jsonb, '{}'::jsonb, 'acceptance')
                """, releaseId, owner, groupId);
    }

    private void insertExposure(long id, long tenant, long owner, long group, long release, String path) {
        sql.update("""
                INSERT INTO public.gateway_url_exposure_t0
                    (id, tenant_id, create_user_id, create_time, update_user_id, update_time,
                     owner_tenant_id, gateway_group_id, route_id, cluster_id, gateway_namespace,
                     gateway_name, gateway_uid, listener_name, hostname, http_method, path_match_type, path_value,
                     route_kind, route_namespace, route_name, desired_state,
                     release_id, content_sha256, compiler_profile_id, change_reason)
                VALUES (?, ?, 'acceptance', CURRENT_TIMESTAMP, 'acceptance', CURRENT_TIMESTAMP,
                        ?, ?, ?, 'cluster-main', 'gateway-system', 'public-gateway', 'trusted-uid',
                        'https-public', 'api.example.com', 'GET', 'EXACT', ?,
                        'HTTPRoute', 'yuheng-routes', ?, 'OPEN', ?, ?, 'acceptance-profile', 'acceptance')
                """, id, tenant, owner, group, "orders-" + id, path, "yuheng-exposure-" + id,
                release, "b".repeat(64));
    }

    private static void assertSqlState(String state, Runnable operation) {
        assertThatThrownBy(operation::run).hasRootCauseInstanceOf(SQLException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        cause -> assertThat(cause.getSQLState()).isEqualTo(state));
    }

    private static String dnsSubdomain(Random input) {
        return String.join(".", asciiLabel(input, 63), asciiLabel(input, 63), asciiLabel(input, 63),
                asciiLabel(input, 61));
    }

    private static String asciiLabel(Random input, int length) {
        StringBuilder label = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            label.append((char) ('a' + input.nextInt(26)));
        }
        return label.toString();
    }
}
