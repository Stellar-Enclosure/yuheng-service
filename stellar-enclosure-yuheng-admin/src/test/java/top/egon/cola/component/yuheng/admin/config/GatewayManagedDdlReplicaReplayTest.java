package top.egon.cola.component.yuheng.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.egon.cola.component.common.mybatis.ddl.EgonColaDdlManifestBO;
import top.egon.cola.component.common.mybatis.ddl.EgonColaPostgreDdlRunner;
import top.egon.cola.component.common.mybatis.exception.EgonColaMybatisPlusConfigurationException;
import top.egon.cola.component.common.mybatis.sharding.EgonColaShardingProperties;
import top.egon.cola.component.common.mybatis.sharding.bootstrap.EgonColaShardingTopologyValidator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayManagedDdlReplicaReplayTest {

    @Test
    void refusesToCreateTheLogicalSourceWhenReplicaReplayCannotBeVerified() throws Exception {
        var mapper = new ObjectMapper();
        EgonColaDdlManifestBO manifest;
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/egon-mp/repository-manifest.json")) {
            manifest = mapper.readValue(stream, EgonColaDdlManifestBO.class);
        }
        var script = manifest.scripts().getLast();
        var deployment = new GatewayPersistenceProperties(1L, 2L, "test", script.version(), script.sha256(), true);
        var primaryConfig = new EgonColaShardingProperties.PhysicalDataSourceProperties("primary", "shard_0",
                EgonColaShardingProperties.DataSourceRoleEnum.PRIMARY, "org.postgresql.Driver", "jdbc:postgresql://localhost/primary", "test", "test");
        var replicaConfig = new EgonColaShardingProperties.PhysicalDataSourceProperties("replica", "shard_0",
                EgonColaShardingProperties.DataSourceRoleEnum.REPLICA, "org.postgresql.Driver", "jdbc:postgresql://localhost/replica", "test", "test");
        var sharding = EgonColaShardingProperties.builder().mode(EgonColaShardingProperties.ModeEnum.SHARDING_READWRITE)
                .configStyle(EgonColaShardingProperties.ConfigStyleEnum.STRATEGY)
                .dataSources(List.of(primaryConfig, replicaConfig)).build();
        var primary = mock(DataSource.class);
        var replica = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var row = mock(ResultSet.class);
        when(primary.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT pg_current_wal_lsn()::text")).thenReturn(row);
        when(row.next()).thenReturn(true);
        when(row.getString(1)).thenReturn("0/1");
        when(replica.getConnection()).thenThrow(new SQLException("replica unavailable"));
        var validator = mock(EgonColaShardingTopologyValidator.class);
        when(validator.validate(any(), any())).thenReturn(new EgonColaShardingTopologyValidator.TopologyBO(
                new byte[]{1}, Map.of(), Map.of("shard_0", "public"), Set.of(), "a".repeat(64)));
        var runner = mock(EgonColaPostgreDdlRunner.class);
        when(runner.run(any())).thenReturn(List.of());
        var factory = new GatewayManagedDdlFactory(sharding, mapper, deployment, validator, runner, Duration.ofSeconds(1));

        assertThatThrownBy(() -> factory.create(Map.of("primary", primary, "replica", replica), new byte[]{1}))
                .isInstanceOf(EgonColaMybatisPlusConfigurationException.class)
                .hasMessageContaining("DDL_REPLICA_REPLAY_FAILED");
        var order = inOrder(runner, replica);
        order.verify(runner).run(any());
        order.verify(replica).getConnection();
    }
}
