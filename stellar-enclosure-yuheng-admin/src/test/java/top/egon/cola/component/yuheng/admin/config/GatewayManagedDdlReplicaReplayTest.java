package top.egon.cola.component.yuheng.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlManifestBO;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlTargetBO;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.ddl.EgonColaPostgreDdlRunner;
import top.egon.cola.component.common.mybatis.exception.EgonColaMybatisPlusConfigurationException;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties.TableProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingYamlLoader;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaShardingStrategyFactory;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaSingleTableShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaStandardTenantIdShardingStrategy;
import top.egon.cola.component.common.mybatis.sharding.strategy.EgonColaTechnicalGroupShardingStrategy;
import top.egon.cola.component.common.mybatis.routing.EgonColaRoutingProfileBO;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Current URL control facts require a single PRIMARY; replica replay cannot make a replica authoritative. */
class GatewayManagedDdlReplicaReplayTest {
    private static final List<String> CONTROL_TABLES = List.of("gateway_group", "gateway_release",
            "gateway_release_content", "gateway_idempotency_record", "gateway_audit_log");

    @Test
    void composedFactoryPreparesBothExactOutboxNodesBeforeBootstrap() throws Exception {
        var topology = properties();
        Class<?> initializerType = Class.forName("top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer");
        Class<?> storageType = Class.forName("top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties");
        var constructor = GatewayManagedDdlFactory.class.getConstructor(EgonColaShardingProperties.class,
                ObjectMapper.class, GatewayPersistenceProperties.class, EgonColaShardingTopologyValidator.class,
                EgonColaPostgreDdlRunner.class, initializerType, storageType);
        constructor.newInstance(topology, new ObjectMapper(), deployment(), mock(EgonColaShardingTopologyValidator.class),
                mock(EgonColaPostgreDdlRunner.class), mock(initializerType), storageType.getConstructor().newInstance());
        var message = topology.getTables().get("egon_cola_outbox_message");
        var history = topology.getTables().get("ddl_history");
        assertThat(message).isNotNull(); assertThat(history).isNotNull();
        assertThat(message.type()).isEqualTo("SINGLE");
        assertThat(message.dataSource()).isEqualTo("primary");
        assertThat(message.shardGroups()).isNullOrEmpty();
        assertThat(message.shardingColumn()).isNull();
        assertThat(message.schema()).isEqualTo("egon_outbox");
        assertThat(history.type()).isEqualTo("SINGLE");
        assertThat(history.dataSource()).isEqualTo("primary");
        assertThat(history.schema()).isEqualTo("egon_outbox");
        assertThatCode(() -> GatewayPersistenceConfiguration.validateUrlExposureControlTopology(topology, actualTopology(topology)))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesReadwriteControlTopologyBeforeRunnerOrReplicaAccess() {
        var topology = properties().setMode(EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE)
                .setDataSources(List.of(source("primary", "shard_0", EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY),
                        source("replica", "shard_0", EgonColaShardingProperties.DataSourceRoleEnum.REPLICA)));
        assertConstructorRejects(topology, "URL_EXPOSURE_CONTROL_PRIMARY_REQUIRED");
    }

    @Test
    void rejectsMultiplePrimariesAndUnprovenNativeXaOrAliasTopologiesBeforeDdl() {
        List<EgonColaShardingProperties> unsupported = List.of(
                properties().setDataSources(List.of(primary(), source("other", "other", EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY))),
                properties().setConfigStyle(EgonColaShardingProperties.ConfigStyleEnum.NATIVE).setNativeRulesResource("classpath:unused.yaml"),
                properties().setTransactionDefaultType("XA"),
                properties().setEnabled(false),
                properties().setDataSources(List.of(source("pool", "primary", EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY))),
                properties().setDataSources(List.of(source("replica", "replica", EgonColaShardingProperties.DataSourceRoleEnum.REPLICA))));
        unsupported.forEach(topology -> assertConstructorRejects(topology, "URL_EXPOSURE_CONTROL_PRIMARY_REQUIRED"));
    }

    @Test
    void registersOnlyTheExactSingleControlRuleAndValidatesRealComponentAddresses() throws Exception {
        var topology = properties();
        Map<String, TableProperties> existing = Map.copyOf(topology.getTables());
        var validator = mock(EgonColaShardingTopologyValidator.class);
        var runner = mock(EgonColaPostgreDdlRunner.class);
        new GatewayManagedDdlFactory(topology, new ObjectMapper(), deployment(), validator, runner);

        assertThat(topology.getTables()).hasSize(existing.size() + 1).containsAllEntriesOf(existing);
        TableProperties exposure = topology.getTables().get("gateway_url_exposure_t0");
        assertThat(exposure.type()).isEqualTo("SINGLE");
        assertThat(exposure.dataSource()).isEqualTo("primary");
        assertThat(exposure.schema()).isEqualTo("public");
        var materialized = actualTopology(topology);
        assertThatCode(() -> GatewayPersistenceConfiguration.validateUrlExposureControlTopology(topology, materialized))
                .doesNotThrowAnyException();
        var route = materialized.profiles().get("gateway_url_exposure_t0");
        assertThat(route.kind()).isEqualTo(EgonColaRoutingProfileBO.TableKindEnum.SINGLE);
        assertThat(route.actualNodes().values().stream().flatMap(List::stream).toList()).singleElement()
                .satisfies(node -> {
                    assertThat(node.group()).isEqualTo("primary");
                    assertThat(node.schema()).isEqualTo("public");
                    assertThat(node.table()).isEqualTo("gateway_url_exposure_t0");
                });
        GatewayPersistenceConfiguration.prepareUrlExposureControlTopology(topology);
        assertThat(topology.getTables()).hasSize(existing.size() + 1);
        verifyNoInteractions(validator, runner);
    }

    @Test
    void rejectsAConflictingExistingExposureRuleInsteadOfOverwritingIt() {
        for (TableProperties conflicting : List.of(standard(),
                new TableProperties("SINGLE", "foreign", null, null, null),
                new TableProperties("SINGLE", "primary", null, null, null, "foreign_schema"))) {
            var topology = properties();
            topology.getTables().put("gateway_url_exposure_t0", conflicting);
            assertConstructorRejects(topology, "URL_EXPOSURE_SINGLE_ROUTE_REQUIRED");
            assertThat(topology.getTables().get("gateway_url_exposure_t0")).isSameAs(conflicting);
        }
    }

    @Test
    void rejectsMissingOrChangedBusinessRoutesBeforeRegisteringTheControlTable() {
        var missing = properties();
        missing.getTables().remove("gateway_audit_log");
        assertConstructorRejects(missing, "URL_EXPOSURE_CONTROL_ROUTES_REQUIRED");
        var changed = properties();
        changed.getTables().put("gateway_release", new TableProperties("SINGLE", "primary", null, null, null));
        assertConstructorRejects(changed, "URL_EXPOSURE_CONTROL_ROUTE_MISMATCH");
        var foreign = properties();
        foreign.getTables().put("gateway_idempotency_record", new TableProperties("STANDARD_TENANT_ID", null,
                "tenant_id", null, null, "public", List.of("other")));
        assertConstructorRejects(foreign, "URL_EXPOSURE_CONTROL_ROUTE_MISMATCH");
    }

    @Test
    void refusesForeignMaterializedBusinessRoutingBeforeAnyDdlOrConnection() throws Exception {
        var properties = properties();
        GatewayPersistenceConfiguration.prepareUrlExposureControlTopology(properties);
        var valid = actualTopology(properties);
        var profiles = new LinkedHashMap<>(valid.profiles());
        profiles.put("gateway_release", new EgonColaStandardTenantIdShardingStrategy().toRoutingProfile(
                "gateway_release", standard(), List.of(source("foreign", "foreign", EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY))));
        assertCreateRejects(properties, new EgonColaShardingTopologyValidator.TopologyBO(valid.yaml(), profiles,
                valid.schemas(), valid.broadcastTables(), valid.fingerprint()));
    }

    @Test
    void refusesAnAbsentOrShardedMaterializedExposureBeforeAnyDdlOrConnection() throws Exception {
        var properties = properties();
        GatewayPersistenceConfiguration.prepareUrlExposureControlTopology(properties);
        var valid = actualTopology(properties);
        var missing = new LinkedHashMap<>(valid.profiles());
        missing.remove("gateway_url_exposure_t0");
        assertCreateRejects(properties, new EgonColaShardingTopologyValidator.TopologyBO(valid.yaml(), missing,
                valid.schemas(), valid.broadcastTables(), valid.fingerprint()));
        var changed = new LinkedHashMap<>(valid.profiles());
        changed.put("gateway_url_exposure_t0", new EgonColaStandardTenantIdShardingStrategy().toRoutingProfile(
                "gateway_url_exposure_t0", standard(), List.of(primary())));
        assertCreateRejects(properties, new EgonColaShardingTopologyValidator.TopologyBO(valid.yaml(), changed,
                valid.schemas(), valid.broadcastTables(), valid.fingerprint()));
    }

    @Test
    void anOptionalOutboxRouteMustAlsoUseTheSamePhysicalPrimary() throws Exception {
        var properties = properties();
        TableProperties outbox = new TableProperties("TECHNICAL_GROUP", "primary", "shard_slot", null, null,
                "egon_outbox", List.of("primary"));
        properties.getTables().put("egon_cola_outbox_message", outbox);
        GatewayPersistenceConfiguration.prepareUrlExposureControlTopology(properties);
        var valid = actualTopology(properties);
        assertThatCode(() -> GatewayPersistenceConfiguration.validateUrlExposureControlTopology(properties, valid))
                .doesNotThrowAnyException();
        var changed = new LinkedHashMap<>(valid.profiles());
        changed.put("egon_cola_outbox_message", new EgonColaTechnicalGroupShardingStrategy().toRoutingProfile(
                "egon_cola_outbox_message", new TableProperties("TECHNICAL_GROUP", "foreign", "shard_slot", null, null,
                        "egon_outbox", List.of("foreign")),
                List.of(source("foreign", "foreign", EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY))));
        assertCreateRejects(properties, new EgonColaShardingTopologyValidator.TopologyBO(valid.yaml(), changed,
                valid.schemas(), valid.broadcastTables(), valid.fingerprint()));
    }

    @Test
    void requiresAdminDdlOwnershipAndRejectsTheSecondGenericInitializerBeforeFactoryCreation() {
        assertAdminDdlFlags(null, true, false, "MANAGED_DDL_FLAG_REQUIRED");
        assertAdminDdlFlags(false, false, false, "MANAGED_DDL_ROLE_MISMATCH");
        assertAdminDdlFlags(false, true, false, "MANAGED_DDL_FLAG_CONFLICT");
        assertAdminDdlFlags(true, false, false, "MANAGED_DDL_FLAG_CONFLICT");
        assertAdminDdlFlags(true, true, true, "MANAGED_DDL_OWNER_CONFLICT");
    }

    @Test
    void passesOnlyTheExactAdditivePredecessorFingerprintToTheSinglePrimaryRunner() throws Exception {
        var properties = properties();
        GatewayPersistenceConfiguration.prepareExposureOutboxTopology(properties);
        var materialized = actualTopology(properties);
        var validator = mock(EgonColaShardingTopologyValidator.class);
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var physical = mock(DataSource.class);
        when(validator.validate(any(), any())).thenReturn(materialized);
        AtomicReference<List<EgonColaDdlTargetBO>> captured = new AtomicReference<>();
        IllegalStateException stopBeforeLogicalDatasource = new IllegalStateException("captured verified DDL target");
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            throw stopBeforeLogicalDatasource;
        }).when(runner).run(any());
        var factory = new GatewayManagedDdlFactory(properties, new ObjectMapper(), deployment(), validator, runner,
                mock(top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer.class),
                new top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties());
        assertThatThrownBy(() -> factory.create(Map.of("primary", physical), materialized.yaml()))
                .isSameAs(stopBeforeLogicalDatasource);
        var previous = new TreeMap<>(materialized.profiles());
        previous.remove("egon_cola_outbox_message"); previous.remove("ddl_history");
        String v003Fingerprint = EgonColaShardingTopologyValidator.fingerprint(previous, materialized.schemas());
        previous.remove("gateway_url_exposure_t0");
        String previousFingerprint = EgonColaShardingTopologyValidator.fingerprint(previous, materialized.schemas());
        assertThat(captured.get()).singleElement().satisfies(target -> {
            assertThat(target.alias()).isEqualTo("primary");
            assertThat(target.schema()).isEqualTo("public");
            assertThat(target.dataSource()).isSameAs(physical);
            assertThat(target.routeFingerprint()).isEqualTo(materialized.fingerprint());
            assertThat(target.compatibleRouteFingerprints()).containsExactlyInAnyOrder(v003Fingerprint, previousFingerprint);
        });
        previous.remove("gateway_audit_log");
        assertThat(captured.get().getFirst().compatibleRouteFingerprints())
                .doesNotContain(EgonColaShardingTopologyValidator.fingerprint(previous, materialized.schemas()));
        verifyNoInteractions(physical);
    }

    @Test
    void latestRuntimeCannotSkipTheOutboxInitializer() throws Exception {
        var properties = properties();
        GatewayPersistenceConfiguration.prepareUrlExposureControlTopology(properties);
        var topology = actualTopology(properties);
        var validator = mock(EgonColaShardingTopologyValidator.class);
        when(validator.validate(any(), any())).thenReturn(topology);
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var physical = mock(DataSource.class);
        var factory = new GatewayManagedDdlFactory(properties, new ObjectMapper(), deployment(), validator, runner);
        assertThatThrownBy(() -> factory.create(Map.of("primary", physical), topology.yaml()))
                .hasMessageContaining("URL_EXPOSURE_OUTBOX_INITIALIZER_REQUIRED");
        verifyNoInteractions(runner, physical);
    }

    @Test
    void outboxNodeConflictAndReadOnlyStorageModeFailBeforeAnyDdl() throws Exception {
        var topology = properties();
        topology.getTables().put("ddl_history", new TableProperties("SINGLE", "primary", null, null, null, "public"));
        assertThatThrownBy(() -> new GatewayManagedDdlFactory(topology, new ObjectMapper(), deployment(),
                mock(EgonColaShardingTopologyValidator.class), mock(EgonColaPostgreDdlRunner.class),
                mock(top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer.class),
                new top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties()))
                .hasMessageContaining("URL_EXPOSURE_OUTBOX_SINGLE_ROUTE_REQUIRED");
        assertThatThrownBy(() -> new GatewayManagedDdlFactory(properties(), new ObjectMapper(), deployment(),
                mock(EgonColaShardingTopologyValidator.class), mock(EgonColaPostgreDdlRunner.class),
                mock(top.egon.cola.component.outbox.migration.OutboxManagedDdlInitializer.class),
                new top.egon.cola.component.outbox.autoconfigure.OutboxMpStorageProperties().setAutoInitializeDdl(false)))
                .hasMessageContaining("URL_EXPOSURE_OUTBOX_STORAGE_REQUIRED");
    }

    private static void assertAdminDdlFlags(Boolean deploymentEnabled, boolean componentEnabled, boolean genericAutoMigrate,
                                             String error) {
        var mybatis = new EgonColaMybatisPlusProperties();
        mybatis.getDdl().setEnabled(componentEnabled);
        mybatis.getDdl().setAutoMigrate(genericAutoMigrate);
        var persistence = new GatewayPersistenceProperties(1L, 2L, "test", "unused", "a".repeat(64), deploymentEnabled);
        var validator = mock(EgonColaShardingTopologyValidator.class);
        var runner = mock(EgonColaPostgreDdlRunner.class);
        assertThatThrownBy(() -> new GatewayManagedDdlConfiguration().egonColaShardingLogicalDataSourceFactory(
                properties(), mybatis, new ObjectMapper(), persistence, validator, runner, null, null))
                .isInstanceOf(EgonColaMybatisPlusConfigurationException.class).hasMessageContaining(error);
        verifyNoInteractions(validator, runner);
    }

    private static void assertConstructorRejects(EgonColaShardingProperties properties, String error) {
        var validator = mock(EgonColaShardingTopologyValidator.class);
        var runner = mock(EgonColaPostgreDdlRunner.class);
        assertThatThrownBy(() -> new GatewayManagedDdlFactory(properties, new ObjectMapper(),
                new GatewayPersistenceProperties(1L, 2L, "test", "unused", "a".repeat(64), true), validator, runner))
                .isInstanceOf(EgonColaMybatisPlusConfigurationException.class).hasMessageContaining(error);
        verifyNoInteractions(validator, runner);
    }

    private static void assertCreateRejects(EgonColaShardingProperties properties,
                                            EgonColaShardingTopologyValidator.TopologyBO materialized) throws Exception {
        var validator = mock(EgonColaShardingTopologyValidator.class);
        var runner = mock(EgonColaPostgreDdlRunner.class);
        var physical = mock(DataSource.class);
        when(validator.validate(any(), any())).thenReturn(materialized);
        var factory = new GatewayManagedDdlFactory(properties, new ObjectMapper(), deployment(), validator, runner);
        assertThatThrownBy(() -> factory.create(Map.of("primary", physical), materialized.yaml()))
                .isInstanceOf(EgonColaMybatisPlusConfigurationException.class).hasMessageContaining("URL_EXPOSURE_");
        verifyNoInteractions(runner, physical);
    }

    private static EgonColaShardingTopologyValidator.TopologyBO actualTopology(EgonColaShardingProperties properties) {
        var validation = new ValidationUtils(Validation.buildDefaultValidatorFactory().getValidator());
        var strategies = new EgonColaShardingStrategyFactory(validation, Map.of(
                "STANDARD_TENANT_ID", new EgonColaStandardTenantIdShardingStrategy(),
                "SINGLE", new EgonColaSingleTableShardingStrategy(),
                "TECHNICAL_GROUP", new EgonColaTechnicalGroupShardingStrategy()));
        byte[] yaml = new EgonColaShardingYamlLoader(strategies, new DefaultResourceLoader(), new StandardEnvironment()).load(properties);
        return new EgonColaShardingTopologyValidator(validation).validate(properties, yaml);
    }

    private static GatewayPersistenceProperties deployment() throws Exception {
        try (var stream = GatewayManagedDdlReplicaReplayTest.class.getClassLoader()
                .getResourceAsStream("db/egon-mp/repository-manifest.json")) {
            var head = new ObjectMapper().readValue(stream, EgonColaDdlManifestBO.class).scripts().getLast();
            return new GatewayPersistenceProperties(1L, 2L, "test", head.version(), head.sha256(), true);
        }
    }

    private static EgonColaShardingProperties properties() {
        Map<String, TableProperties> tables = new LinkedHashMap<>();
        CONTROL_TABLES.forEach(table -> tables.put(table, standard()));
        return EgonColaShardingProperties.builder().mode(EgonColaShardingProperties.ModeEnum.SHARDING)
                .configStyle(EgonColaShardingProperties.ConfigStyleEnum.STRATEGY).transactionDefaultType("LOCAL")
                .dataSources(List.of(primary())).tables(tables).build();
    }

    private static TableProperties standard() { return new TableProperties("STANDARD_TENANT_ID", null, "tenant_id", null, null); }
    private static EgonColaShardingProperties.PhysicalDataSourceProperties primary() {
        return source("primary", "primary", EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY);
    }
    private static EgonColaShardingProperties.PhysicalDataSourceProperties source(String name, String group,
                                                                               EgonColaShardingProperties.DataSourceRoleEnum role) {
        return new EgonColaShardingProperties.PhysicalDataSourceProperties(name, group, role, "org.postgresql.Driver",
                "jdbc:postgresql://127.0.0.1:65432/unused", "test", "test");
    }
}
